package com.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.PrintWriter
import java.io.StringWriter

class ClientController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppState.initial())
    val state = _state.asStateFlow()

    private lateinit var config: ClientConfig
    private lateinit var api: BackendApi
    private lateinit var modbus: ModbusReader
    private val loginInProgress = AtomicBoolean(false)
    private val configWatcherStarted = AtomicBoolean(false)
    private val configWatcherRunning = AtomicBoolean(false)
    private val meterJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val meterRegisters = ConcurrentHashMap<Long, AtomicReference<List<RegisterDto>>>()

    fun start() {
        scope.launch {
            try {
                config = ConfigLoader.load()
                api = BackendApi(config)
                modbus = ModbusReader(config)
                val prefillEmail = if (config.rememberCredentials) config.email else ""
                val prefillPassword = if (config.rememberCredentials) config.password else ""
                _state.update {
                    it.copy(
                        status = "Czeka na logowanie...",
                        isLoggedIn = false,
                        loginError = null,
                        loginPrefillEmail = prefillEmail,
                        loginPrefillPassword = prefillPassword,
                        rememberPrefill = config.rememberCredentials
                    )
                }
            } catch (ex: Exception) {
                logError("Blad startu", ex)
                _state.update { it.copy(status = "Blad startu: ${ex.message}") }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    fun login(email: String, password: String, remember: Boolean) {
        scope.launch {
            if (!loginInProgress.compareAndSet(false, true)) return@launch
            try {
                _state.update { it.copy(status = "Logowanie do backendu...", loginError = null) }
                api.login(email, password)
                saveCredentialsIfNeeded(email, password, remember)
                _state.update { it.copy(status = "Pobieranie konfiguracji...", isLoggedIn = true) }
                if (configWatcherStarted.compareAndSet(false, true)) {
                    scope.launch { runConfigWatcher() }
                } else {
                    configWatcherRunning.set(true)
                }
            } catch (ex: Exception) {
                logError("Blad logowania", ex)
                _state.update { it.copy(status = "Blad logowania: ${ex.message}", loginError = ex.message, isLoggedIn = false) }
            } finally {
                loginInProgress.set(false)
            }
        }
    }

    fun logout() {
        scope.launch {
            configWatcherRunning.set(false)
            meterJobs.values.forEach { it.cancel() }
            meterJobs.clear()
            meterRegisters.clear()
            api.clearSession()
            _state.update {
                it.copy(
                    status = "Wylogowano.",
                    transformer = null,
                    meters = emptyList(),
                    isLoggedIn = false,
                    loginError = null
                )
            }
        }
    }

    private suspend fun runConfigWatcher() {
        configWatcherRunning.set(true)
        while (true) {
            if (!configWatcherRunning.get()) break
            try {
                syncConfiguration()
            } catch (ex: Exception) {
                logError("Blad odswiezania konfiguracji", ex)
                _state.update { it.copy(status = "Blad odswiezania konfiguracji: ${ex.message}") }
            }
            delay(config.configRefreshMs)
        }
    }

    private suspend fun syncConfiguration() {
        val transformer = resolveTransformer()
        if (transformer == null) {
            _state.update { it.copy(status = "Brak transformatorow w backendzie.") }
            return
        }
        val meters = api.fetchMeters(transformer.id).filter { it.enabled }
        val meterIds = meters.map { it.id }.toSet()

        meterJobs.keys.filter { it !in meterIds }.forEach { removedId ->
            meterJobs.remove(removedId)?.cancel()
            meterRegisters.remove(removedId)
        }

        val currentMeters = _state.value.meters.associateBy { it.meter.id }
        val updatedMeterStates = meters.map { meter ->
            val registers = api.fetchRegisters(meter.id).filter { it.enabled }
            meterRegisters.computeIfAbsent(meter.id) { AtomicReference(registers) }.set(registers)

            val existing = currentMeters[meter.id]
            val existingRegs = existing?.registers?.associateBy { it.register.id } ?: emptyMap()
            val registerStates = registers.map { reg ->
                existingRegs[reg.id]?.copy(register = reg) ?: RegisterState(reg, null, null)
            }

            ensureMeterPolling(transformer.id, meter)
            MeterState(
                meter = meter,
                connectionStatus = existing?.connectionStatus ?: "CONNECTING",
                registers = registerStates,
                lastError = existing?.lastError
            )
        }

        _state.update {
            it.copy(
                status = "Konfiguracja OK",
                transformer = transformer,
                meters = updatedMeterStates
            )
        }
    }

    private fun ensureMeterPolling(transformerId: String, meter: MeterDto) {
        if (meterJobs.containsKey(meter.id)) return
        val job = scope.launch {
            pollMeter(transformerId, meter)
        }
        meterJobs[meter.id] = job
    }

    private suspend fun resolveTransformer(): TransformerDto? {
        val transformers = api.fetchTransformers()
        return if (config.transformerId != null) {
            transformers.firstOrNull { it.id == config.transformerId }
        } else {
            transformers.firstOrNull()
        }
    }

    private suspend fun pollMeter(transformerId: String, meter: MeterDto) {
        while (true) {
            var master: com.serotonin.modbus4j.ModbusMaster? = null
            try {
                updateMeterStatus(meter.id, "CONNECTING", null)
                master = modbus.connect(meter)
                updateMeterStatus(meter.id, "CONNECTED", null)

                while (true) {
                    val now = Instant.now()
                    val registers = meterRegisters[meter.id]?.get().orEmpty()
                    registers.forEach { register ->
                        try {
                            val value = modbus.readValue(master, meter, register)
                            updateRegisterValue(meter.id, register.id, value, now)
                            val metric = MetricPointRequest(
                                key = "${meter.deviceCode}.${register.name}",
                                value = value,
                                timestamp = now.toString(),
                                unit = register.unit,
                                label = register.name
                            )
                            api.sendMetricWs(transformerId, metric)
                        } catch (ex: Exception) {
                            if (ex is CancellationException) throw ex
                            logError(
                                "Blad odczytu/wysylki: meterId=${meter.id}, registerId=${register.id}, " +
                                    "port=${meter.serialPort}, addr=${register.address}, type=${register.registerType}",
                                ex
                            )
                            updateMeterStatus(meter.id, "ERROR", ex.message)
                            postTransformerError(
                                transformerId,
                                code = "MODBUS_READ",
                                message = "Meter ${meter.id} reg ${register.id}: ${ex.message}",
                                status = "ERROR"
                            )
                        }
                        if (config.interRegisterDelayMs > 0) {
                            delay(config.interRegisterDelayMs)
                        }
                    }
                    delay(meter.pollIntervalMs ?: config.pollIntervalMs)
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                logError("Blad polaczenia Modbus: meterId=${meter.id}, port=${meter.serialPort}", ex)
                updateMeterStatus(meter.id, "ERROR", ex.message)
                postTransformerError(
                    transformerId,
                    code = "MODBUS_CONNECT",
                    message = "Meter ${meter.id} port ${meter.serialPort}: ${ex.message}",
                    status = "ERROR"
                )
                delay(config.reconnectDelayMs)
            } finally {
                master?.destroy()
            }
        }
    }

    private fun updateMeterStatus(meterId: Long, status: String, error: String?) {
        _state.update { current ->
            current.copy(
                meters = current.meters.map { meterState ->
                    if (meterState.meter.id == meterId) {
                        meterState.copy(connectionStatus = status, lastError = error)
                    } else {
                        meterState
                    }
                }
            )
        }
    }

    private fun updateMeterRegisters(meterId: Long, registers: List<RegisterState>) {
        _state.update { current ->
            current.copy(
                meters = current.meters.map { meterState ->
                    if (meterState.meter.id == meterId) {
                        meterState.copy(registers = registers)
                    } else {
                        meterState
                    }
                }
            )
        }
    }

    private fun updateRegisterValue(meterId: Long, registerId: Long, value: Double, time: Instant) {
        _state.update { current ->
            current.copy(
                meters = current.meters.map { meterState ->
                    if (meterState.meter.id == meterId) {
                        meterState.copy(
                            registers = meterState.registers.map { regState ->
                                if (regState.register.id == registerId) {
                                    regState.copy(value = value, lastUpdate = time)
                                } else {
                                    regState
                                }
                            }
                        )
                    } else {
                        meterState
                    }
                }
            )
        }
    }

    private fun logError(context: String, ex: Throwable) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        val details = sw.toString()
        println("[$context] ${ex::class.simpleName}: ${ex.message}\n$details")
    }

    private fun postTransformerError(transformerId: String, code: String, message: String, status: String) {
        scope.launch {
            try {
                api.postTransformerError(transformerId, TransformerErrorRequest(code, message, status))
            } catch (ex: Exception) {
                logError("Blad wysylki errora do backendu", ex)
            }
        }
    }

    private fun saveCredentialsIfNeeded(email: String, password: String, remember: Boolean) {
        val savedPassword = if (remember) password else ""
        val updated = config.copy(
            email = email,
            password = savedPassword,
            rememberCredentials = remember
        )
        config = updated
        ConfigLoader.save(updated)
        _state.update {
            it.copy(
                loginPrefillEmail = if (remember) email else "",
                loginPrefillPassword = if (remember) password else "",
                rememberPrefill = remember
            )
        }
    }
}
