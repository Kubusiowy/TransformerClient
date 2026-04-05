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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.PrintWriter
import java.io.StringWriter
import com.serotonin.modbus4j.ModbusMaster
import kotlin.math.abs

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
    private val motorMutex = Mutex()
    private var stepperMotorDriver: StepperMotorDriver? = null
    private var activeGpioConfig: RaspberryPiGpioConfig? = null

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
        stepperMotorDriver?.close()
        stepperMotorDriver = null
        activeGpioConfig = null
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
            stepperMotorDriver?.close()
            stepperMotorDriver = null
            activeGpioConfig = null
            api.clearSession()
            _state.update {
                it.copy(
                    status = "Wylogowano.",
                    transformer = null,
                    meters = emptyList(),
                    motorControl = null,
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
                val registerWithLocalSettings = applyLocalSettings(meter.id, reg)
                existingRegs[reg.id]?.copy(register = registerWithLocalSettings)
                    ?: RegisterState(meter.id, registerWithLocalSettings, null, null)
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
                meters = updatedMeterStates,
                motorControl = mergeMotorControlState(updatedMeterStates, it.motorControl)
            )
        }
        if (config.motorControl?.gpio == null) {
            stepperMotorDriver?.close()
            stepperMotorDriver = null
            activeGpioConfig = null
        }
    }

    fun startMotor() {
        scope.launch {
            executeMotorCommand("Start silnika") { current ->
                applyMotorHardware(
                    current = current,
                    running = true,
                    direction = current.direction,
                    speed = current.speedSetpoint
                )
            }
        }
    }

    fun stopMotor() {
        scope.launch {
            executeMotorCommand("Stop silnika") { current ->
                applyMotorHardware(
                    current = current,
                    running = false,
                    direction = current.direction,
                    speed = current.speedSetpoint
                )
            }
        }
    }

    fun setMotorDirection(direction: MotorDirection) {
        scope.launch {
            executeMotorCommand(
                label = if (direction == MotorDirection.FORWARD) "Kierunek przod" else "Kierunek tyl",
                updateStateOnly = false
            ) { current ->
                applyMotorHardware(
                    current = current,
                    running = current.isRunning,
                    direction = direction,
                    speed = current.speedSetpoint
                )
            }
        }
    }

    fun setMotorSpeed(speedText: String) {
        scope.launch {
            val speed = speedText.replace(',', '.').toDoubleOrNull()
            if (speed == null) {
                updateMotorStatus("Nieprawidlowa wartosc predkosci")
                return@launch
            }
            executeMotorCommand("Zadanie predkosci $speed", updateStateOnly = false) { current ->
                applyMotorHardware(
                    current = current,
                    running = current.isRunning,
                    direction = current.direction,
                    speed = speed
                )
            }
        }
    }

    fun saveRegisterThresholds(meterId: Long, registerId: Long, targetText: String, thresholdText: String) {
        scope.launch {
            val targetResult = parseOptionalDouble(targetText)
            val thresholdResult = parseOptionalDouble(thresholdText)
            if (!targetResult.valid || !thresholdResult.valid) {
                _state.update { it.copy(status = "Nieprawidlowa wartosc docelowa lub progowa.") }
                return@launch
            }

            val updatedSettings = config.localRegisterSettings
                .filterNot { it.meterId == meterId && it.registerId == registerId }
                .toMutableList()

            if (targetResult.value != null || thresholdResult.value != null) {
                updatedSettings.add(
                    LocalRegisterSettings(
                        meterId = meterId,
                        registerId = registerId,
                        targetValue = targetResult.value,
                        thresholdValue = thresholdResult.value
                    )
                )
            }

            config = config.copy(localRegisterSettings = updatedSettings)
            ConfigLoader.save(config)
            applyLocalRegisterSettings(meterId, registerId)
            _state.value.meters
                .firstOrNull { it.meter.id == meterId }
                ?.registers
                ?.firstOrNull { it.register.id == registerId }
                ?.value
                ?.let { currentValue -> scheduleAutomaticMotorAdjustment(meterId, registerId, currentValue) }
            _state.update { it.copy(status = "Zapisano lokalne ustawienia rejestru.") }
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
        var alarmTransition: RegisterAlarmTransition? = null
        _state.update { current ->
            current.copy(
                meters = current.meters.map { meterState ->
                    if (meterState.meter.id == meterId) {
                        meterState.copy(
                            registers = meterState.registers.map { regState ->
                                if (regState.register.id == registerId) {
                                    val alarmActive = isAlarmActive(regState.register, value)
                                    if (regState.alarmActive != alarmActive) {
                                        alarmTransition = RegisterAlarmTransition(
                                            meter = meterState.meter,
                                            register = regState.register,
                                            value = value,
                                            active = alarmActive
                                        )
                                    }
                                    regState.copy(
                                        value = value,
                                        lastUpdate = time,
                                        alarmActive = alarmActive,
                                        alarmMessage = buildAlarmMessage(regState.register, value, alarmActive)
                                    )
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
        scheduleAutomaticMotorAdjustment(meterId, registerId, value)
        val transition = alarmTransition ?: return
        val status = if (transition.active) "ALARM" else "OK"
        val message = if (transition.active) {
            "${transition.meter.name}/${transition.register.name}: przekroczono prog ${transition.register.thresholdValue} (odczyt ${transition.value})"
        } else {
            "${transition.meter.name}/${transition.register.name}: alarm skasowany, odczyt ${transition.value}"
        }
        postTransformerError(
            transformerId = _state.value.transformer?.id ?: return,
            code = "REGISTER_THRESHOLD",
            message = message,
            status = status
        )
    }

    private fun applyLocalRegisterSettings(meterId: Long, registerId: Long) {
        _state.update { current ->
            current.copy(
                meters = current.meters.map { meterState ->
                    if (meterState.meter.id != meterId) {
                        meterState
                    } else {
                        meterState.copy(
                            registers = meterState.registers.map { regState ->
                                if (regState.register.id != registerId) {
                                    regState
                                } else {
                                    val updatedRegister = applyLocalSettings(meterId, regState.register)
                                    val alarmActive = regState.value?.let { isAlarmActive(updatedRegister, it) } ?: false
                                    regState.copy(
                                        register = updatedRegister,
                                        alarmActive = alarmActive,
                                        alarmMessage = regState.value?.let {
                                            buildAlarmMessage(updatedRegister, it, alarmActive)
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    private fun mergeMotorControlState(
        meters: List<MeterState>,
        existing: MotorControlState?
    ): MotorControlState? {
        val config = config.motorControl?.takeIf { it.enabled } ?: return null
        val meter = resolveMotorMeter(config, meters)
            ?: meters.firstOrNull()?.meter
        val available = config.gpio != null || meter != null
        return if (existing == null || existing.config != config || existing.meter?.id != meter?.id) {
            MotorControlState(
                config = config,
                meter = meter,
                available = available,
                isRunning = existing?.isRunning ?: false,
                direction = existing?.direction ?: MotorDirection.FORWARD,
                speedSetpoint = existing?.speedSetpoint ?: config.defaultSpeed,
                lastCommandStatus = existing?.lastCommandStatus,
                lastCommandAt = existing?.lastCommandAt
            )
        } else {
            existing.copy(config = config, meter = meter, available = available)
        }
    }

    private fun resolveMotorMeter(config: MotorControlConfig, meters: List<MeterState>): MeterDto? {
        val preferredId = config.feedback?.meterId ?: config.meterId
        return preferredId?.let { targetId -> meters.firstOrNull { it.meter.id == targetId }?.meter }
    }

    private fun scheduleAutomaticMotorAdjustment(meterId: Long, registerId: Long, value: Double) {
        val motorState = _state.value.motorControl ?: return
        val feedback = motorState.config.feedback ?: return
        val feedbackMeterId = feedback.meterId ?: motorState.meter?.id ?: meterId
        if (meterId != feedbackMeterId || registerId != feedback.registerId) return

        scope.launch {
            executeMotorCommand("Automatyczna korekta", updateStateOnly = false) adjustment@{ current ->
                val registerState = _state.value.meters
                    .firstOrNull { it.meter.id == meterId }
                    ?.registers
                    ?.firstOrNull { it.register.id == registerId }
                    ?: return@adjustment current

                val target = registerState.register.targetValue
                if (target == null) {
                    if (!current.isRunning) return@adjustment current
                    return@adjustment applyMotorHardware(
                        current = current,
                        running = false,
                        direction = current.direction,
                        speed = current.speedSetpoint
                    )
                }

                val tolerance = feedback.tolerance ?: registerState.register.thresholdValue ?: 0.0
                val error = target - value
                if (abs(error) <= tolerance) {
                    if (!current.isRunning) return@adjustment current
                    return@adjustment applyMotorHardware(
                        current = current,
                        running = false,
                        direction = current.direction,
                        speed = current.speedSetpoint
                    )
                }

                val direction = resolveAutomaticDirection(error, feedback)
                val speed = calculateAutomaticSpeed(abs(error), feedback)
                val running = feedback.autoStart || current.isRunning
                applyMotorHardware(
                    current = current,
                    running = running,
                    direction = direction,
                    speed = speed
                )
            }
        }
    }

    private suspend fun applyMotorHardware(
        current: MotorControlState,
        running: Boolean,
        direction: MotorDirection,
        speed: Double
    ): MotorControlState {
        val gpio = current.config.gpio
        if (gpio != null) {
            if (activeGpioConfig != gpio) {
                stepperMotorDriver?.close()
                stepperMotorDriver = null
                activeGpioConfig = gpio
            }
            val driver = stepperMotorDriver ?: PigpioStepperMotorDriver(gpio).also { stepperMotorDriver = it }
            driver.apply(
                StepperMotorCommand(
                    running = running,
                    direction = direction,
                    speedHz = speed
                )
            )
            return current.copy(
                isRunning = running,
                direction = direction,
                speedSetpoint = speed
            )
        }

        val meter = current.meter ?: error("Brak przypisanego metra dla sterowania silnikiem")
        var master: ModbusMaster? = null
        try {
            master = modbus.connect(meter)
            current.config.directionPin?.let { pin ->
                val directionValue = when (direction) {
                    MotorDirection.FORWARD -> current.config.forwardValue
                    MotorDirection.REVERSE -> current.config.reverseValue
                }
                modbus.writeValue(master, meter, pin, directionValue)
            }
            current.config.speedPin?.let { pin ->
                modbus.writeValue(master, meter, pin, speed)
            }
            current.config.runPin?.let { pin ->
                modbus.writeValue(master, meter, pin, if (running) pin.activeValue else pin.inactiveValue)
            }
            return current.copy(
                isRunning = running,
                direction = direction,
                speedSetpoint = speed
            )
        } finally {
            master?.destroy()
        }
    }

    private fun calculateAutomaticSpeed(error: Double, feedback: MotorFeedbackConfig): Double {
        val requested = error * feedback.proportionalGain
        return requested.coerceIn(feedback.minSpeedHz, feedback.maxSpeedHz)
    }

    private fun resolveAutomaticDirection(error: Double, feedback: MotorFeedbackConfig): MotorDirection {
        val positiveDirection = if (feedback.invertDirection) MotorDirection.REVERSE else MotorDirection.FORWARD
        return if (error >= 0.0) positiveDirection else oppositeDirection(positiveDirection)
    }

    private fun oppositeDirection(direction: MotorDirection): MotorDirection {
        return if (direction == MotorDirection.FORWARD) MotorDirection.REVERSE else MotorDirection.FORWARD
    }

    private suspend fun executeMotorCommand(
        label: String,
        updateStateOnly: Boolean = true,
        action: suspend (MotorControlState) -> MotorControlState
    ) {
        motorMutex.withLock {
            val current = _state.value.motorControl
            if (current == null) {
                _state.update { it.copy(status = "Sterowanie silnikiem nie jest skonfigurowane.") }
                return
            }
            try {
                val updated = action(current)
                _state.update {
                    it.copy(
                        motorControl = updated.copy(
                            lastCommandStatus = "$label OK",
                            lastCommandAt = Instant.now()
                        ),
                        status = if (updateStateOnly) "Sterowanie silnikiem gotowe" else "$label OK"
                    )
                }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                logError("Blad sterowania silnikiem", ex)
                updateMotorStatus("$label: ${ex.message}")
            }
        }
    }

    private fun updateMotorStatus(status: String) {
        _state.update { current ->
            current.copy(
                status = status,
                motorControl = current.motorControl?.copy(
                    lastCommandStatus = status,
                    lastCommandAt = Instant.now()
                )
            )
        }
    }

    private fun isAlarmActive(register: RegisterDto, value: Double): Boolean {
        val threshold = register.thresholdValue ?: return false
        return value > threshold
    }

    private fun applyLocalSettings(meterId: Long, register: RegisterDto): RegisterDto {
        val local = config.localRegisterSettings.firstOrNull {
            it.meterId == meterId && it.registerId == register.id
        } ?: return register.copy(targetValue = null, thresholdValue = null)
        return register.copy(
            targetValue = local.targetValue,
            thresholdValue = local.thresholdValue
        )
    }

    private fun parseOptionalDouble(text: String): ParsedOptionalDouble {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ParsedOptionalDouble(valid = true, value = null)
        val parsed = normalized.replace(',', '.').toDoubleOrNull()
        return ParsedOptionalDouble(valid = parsed != null, value = parsed)
    }

    private fun buildAlarmMessage(register: RegisterDto, value: Double, active: Boolean): String? {
        val threshold = register.thresholdValue ?: return null
        return if (active) {
            "Alarm: $value > prog $threshold"
        } else {
            null
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

private data class RegisterAlarmTransition(
    val meter: MeterDto,
    val register: RegisterDto,
    val value: Double,
    val active: Boolean
)

private data class ParsedOptionalDouble(
    val valid: Boolean,
    val value: Double?
)
