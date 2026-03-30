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
import com.serotonin.modbus4j.ModbusMaster

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
    private val portJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
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
            portJobs.values.forEach { it.cancel() }
            portJobs.clear()
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
        val activePorts = meters.map { it.serialPort }.toSet()

        meterRegisters.keys.filter { it !in meterIds }.forEach { removedId ->
            meterRegisters.remove(removedId)
        }
        portJobs.keys.filter { it !in activePorts }.forEach { removedPort ->
            portJobs.remove(removedPort)?.cancel()
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

            MeterState(
                meter = meter,
                connectionStatus = existing?.connectionStatus ?: "CONNECTING",
                registers = registerStates,
                lastError = existing?.lastError
            )
        }
        ensurePortPolling(transformer.id, meters)

        _state.update {
            it.copy(
                status = "Konfiguracja OK",
                transformer = transformer,
                meters = updatedMeterStates
            )
        }
    }

    private fun ensurePortPolling(transformerId: String, meters: List<MeterDto>) {
        val metersByPort = meters.groupBy { it.serialPort }
        metersByPort.forEach { (serialPort, portMeters) ->
            if (portJobs.containsKey(serialPort)) return@forEach
            validatePortMeters(serialPort, portMeters)
            val job = scope.launch {
                pollPort(transformerId, serialPort)
            }
            portJobs[serialPort] = job
        }
    }

    private fun validatePortMeters(serialPort: String, meters: List<MeterDto>) {
        val reference = meters.firstOrNull() ?: return
        meters.forEach { meter ->
            check(
                meter.baudRate == reference.baudRate &&
                    meter.dataBits == reference.dataBits &&
                    meter.stopBits == reference.stopBits &&
                    meter.parity == reference.parity
            ) {
                "Meters na porcie $serialPort maja rozne parametry transmisji"
            }
        }
    }

    private suspend fun resolveTransformer(): TransformerDto? {
        val transformers = api.fetchTransformers()
        return if (config.transformerId != null) {
            transformers.firstOrNull { it.id == config.transformerId }
        } else {
            transformers.firstOrNull()
        }
    }

    private suspend fun pollPort(transformerId: String, serialPort: String) {
        while (true) {
            val meters = _state.value.meters.map { it.meter }.filter { it.serialPort == serialPort }
            if (meters.isEmpty()) return

            val templateMeter = meters.first()
            var master: ModbusMaster? = null
            try {
                master = modbus.connect(templateMeter)
                meters.forEach { meter -> updateMeterStatus(meter.id, "CONNECTED", null) }
                while (true) {
                    val loopStart = Instant.now()
                    val currentMeters = _state.value.meters.map { it.meter }.filter { it.serialPort == serialPort }
                    if (currentMeters.isEmpty()) break
                    currentMeters.forEach { meter ->
                        updateMeterStatus(meter.id, "CONNECTED", null)
                        val registers = meterRegisters[meter.id]?.get().orEmpty()
                        registers.forEach { register ->
                            try {
                                val value = modbus.readValue(master, meter, register)
                                updateRegisterValue(meter.id, register.id, value, loopStart)
                                val metric = MetricPointRequest(
                                    key = "${meter.deviceCode}.${register.name}",
                                    value = value,
                                    timestamp = loopStart.toString(),
                                    unit = register.unit,
                                    label = register.name
                                )
                                api.sendMetricWs(transformerId, metric)
                            } catch (ex: Exception) {
                                if (ex is CancellationException) throw ex
                                if (shouldReconnectModbus(ex)) {
                                    throw ModbusReconnectException(
                                        "Port ${meter.serialPort} wymaga reconnect po bledzie odczytu: ${ex.message}",
                                        ex
                                    )
                                }
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
                        val meterDelay = meter.pollIntervalMs ?: config.pollIntervalMs
                        if (meterDelay > 0) {
                            delay(meterDelay)
                        }
                    }
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                logError("Blad polaczenia Modbus: port=$serialPort", ex)
                meters.forEach { meter ->
                    updateMeterStatus(meter.id, "ERROR", ex.message)
                    postTransformerError(
                        transformerId,
                        code = "MODBUS_CONNECT",
                        message = "Meter ${meter.id} port ${meter.serialPort}: ${ex.message}",
                        status = "ERROR"
                    )
                }
                delay(config.reconnectDelayMs)
            } finally {
                master?.destroy()
            }
        }
    }

    private fun shouldReconnectModbus(ex: Throwable): Boolean {
        generateSequence(ex) { it.cause }.forEach { cause ->
            val message = cause.message?.lowercase().orEmpty()
            val className = cause::class.qualifiedName.orEmpty()
            val simpleName = cause::class.simpleName.orEmpty()
            if (
                "modbustransportexception" in simpleName.lowercase() ||
                "ioexception" in simpleName.lowercase() ||
                "serialport" in className.lowercase() ||
                "timeout" in message ||
                "listener" in message ||
                "broken pipe" in message ||
                "port closed" in message ||
                "reset" in message ||
                "cannot invoke" in message
            ) {
                return true
            }
        }
        return false
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

private class ModbusReconnectException(message: String, cause: Throwable) : RuntimeException(message, cause)
