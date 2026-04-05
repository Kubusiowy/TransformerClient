package com.client

import java.io.IOException
import kotlin.math.roundToInt

data class StepperMotorCommand(
    val running: Boolean,
    val direction: MotorDirection,
    val speedHz: Double
)

interface StepperMotorDriver : AutoCloseable {
    fun apply(command: StepperMotorCommand)
}

class PigpioStepperMotorDriver(
    private val config: RaspberryPiGpioConfig
) : StepperMotorDriver {
    @Volatile
    private var initialized = false

    @Synchronized
    override fun apply(command: StepperMotorCommand) {
        initializeIfNeeded()
        setDirection(command.direction)
        if (!command.running || command.speedHz <= 0.0) {
            stopPulse()
            setEnabled(false)
            return
        }
        setEnabled(true)
        val frequency = command.speedHz.roundToInt().coerceAtLeast(1)
        runPigs("pfs", config.stepPin.toString(), frequency.toString())
        runPigs("p", config.stepPin.toString(), config.pwmDutyCycle.coerceIn(0, 255).toString())
    }

    @Synchronized
    override fun close() {
        if (!initialized) return
        stopPulse()
        setEnabled(false)
    }

    private fun initializeIfNeeded() {
        if (initialized) return
        runPigs("m", config.stepPin.toString(), "w")
        runPigs("m", config.directionPin.toString(), "w")
        config.enablePin?.let { runPigs("m", it.toString(), "w") }
        runPigs("prs", config.stepPin.toString(), "255")
        stopPulse()
        setDirection(MotorDirection.FORWARD)
        setEnabled(false)
        initialized = true
    }

    private fun setDirection(direction: MotorDirection) {
        val level = if (direction == MotorDirection.FORWARD) 1 else 0
        runPigs("w", config.directionPin.toString(), level.toString())
    }

    private fun setEnabled(enabled: Boolean) {
        val enablePin = config.enablePin ?: return
        val activeLevel = if (config.enableActiveLow) 0 else 1
        val inactiveLevel = if (config.enableActiveLow) 1 else 0
        val level = if (enabled) activeLevel else inactiveLevel
        runPigs("w", enablePin.toString(), level.toString())
    }

    private fun stopPulse() {
        runPigs("p", config.stepPin.toString(), "0")
    }

    private fun runPigs(vararg args: String) {
        val process = ProcessBuilder(listOf("pigs", *args))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IOException("Polecenie pigs ${args.joinToString(" ")} nie powiodlo sie: $output")
        }
    }
}
