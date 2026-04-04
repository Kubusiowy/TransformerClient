package com.client

import com.fazecast.jSerialComm.SerialPort
import com.serotonin.modbus4j.ModbusFactory
import com.serotonin.modbus4j.ModbusMaster
import com.serotonin.modbus4j.code.DataType
import com.serotonin.modbus4j.code.RegisterRange
import com.serotonin.modbus4j.locator.BaseLocator
import com.serotonin.modbus4j.serial.SerialPortWrapper
import java.io.PrintWriter
import java.io.StringWriter

class ModbusReader(private val config: ClientConfig) {
    fun connect(meter: MeterDto): ModbusMaster {
        val wrapper = JSerialCommWrapper(
            portName = meter.serialPort,
            baudRate = meter.baudRate,
            dataBits = meter.dataBits,
            stopBits = meter.stopBits,
            parity = meter.parity,
            timeoutMs = config.modbusTimeoutMs
        )
        val master = ModbusFactory().createRtuMaster(wrapper)
        master.timeout = config.modbusTimeoutMs
        master.retries = config.modbusRetries
        master.setDiscardDataDelay(config.modbusDiscardDelayMs)
        try {
            master.init()
        } catch (ex: Exception) {
            logError("Modbus init: port=${meter.serialPort}, baud=${meter.baudRate}, parity=${meter.parity}, stopBits=${meter.stopBits}", ex)
            throw ex
        }
        return master
    }

    fun readValue(master: ModbusMaster, meter: MeterDto, register: RegisterDto): Double {
        val offset = normalizeAddress(register.address)
        val locator = createLocator(meter, register.registerType, register.dataType, offset)
        return try {
            val raw = master.getValue(locator)
            val value = when (raw) {
                is Number -> raw.toDouble()
                else -> raw.toString().toDoubleOrNull() ?: Double.NaN
            }
            val scale = register.scale ?: 1.0
            value * scale
        } catch (ex: Exception) {
            logError(
                "Modbus read: slaveId=${meter.slaveId}, port=${meter.serialPort}, " +
                    "regId=${register.id}, regType=${register.registerType}, dataType=${register.dataType}, addr=${register.address}",
                ex
            )
            throw ex
        }
    }

    fun writeValue(master: ModbusMaster, meter: MeterDto, point: WritePointConfig, value: Double) {
        val offset = normalizeAddress(point.address)
        val locator = createLocator(meter, point.registerType, point.dataType, offset)
        val scaledValue = if (point.scale == 0.0) value else value / point.scale
        val payload: Any = when (point.dataType.uppercase()) {
            "BOOLEAN", "BOOL" -> scaledValue >= 0.5
            "FLOAT32" -> scaledValue.toFloat()
            "INT32" -> scaledValue.toLong().toInt()
            else -> scaledValue.toInt()
        }
        try {
            master.setValue(locator, payload)
        } catch (ex: Exception) {
            logError(
                "Modbus write: slaveId=${meter.slaveId}, port=${meter.serialPort}, " +
                    "pin=${point.pinName}, regType=${point.registerType}, dataType=${point.dataType}, addr=${point.address}",
                ex
            )
            throw ex
        }
    }

    private fun createLocator(meter: MeterDto, registerType: String, dataTypeName: String, offset: Int): BaseLocator<*> {
        val type = registerType.uppercase()
        return when (type) {
            "COIL" -> BaseLocator.coilStatus(meter.slaveId, offset)
            "INPUT" -> BaseLocator.inputRegister(meter.slaveId, offset, resolveDataType(meter, dataTypeName))
            else -> BaseLocator.holdingRegister(meter.slaveId, offset, resolveDataType(meter, dataTypeName))
        }
    }

    private fun resolveDataType(meter: MeterDto, dataTypeName: String): Int {
        return when (dataTypeName.uppercase()) {
            "INT16" -> DataType.TWO_BYTE_INT_SIGNED
            "INT32" -> if (meter.byteOrder.uppercase() == "LITTLE_ENDIAN") {
                DataType.FOUR_BYTE_INT_SIGNED_SWAPPED
            } else {
                DataType.FOUR_BYTE_INT_SIGNED
            }
            "FLOAT32" -> if (meter.byteOrder.uppercase() == "LITTLE_ENDIAN") {
                DataType.FOUR_BYTE_FLOAT_SWAPPED
            } else {
                DataType.FOUR_BYTE_FLOAT
            }
            else -> DataType.TWO_BYTE_INT_SIGNED
        }
    }

    private fun normalizeAddress(address: Int): Int {
        return when {
            address >= 40001 -> address - 40001
            address >= 30001 -> address - 30001
            else -> address
        }
    }

    private fun logError(context: String, ex: Throwable) {
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        val details = sw.toString()
        println("[$context] ${ex::class.simpleName}: ${ex.message}\n$details")
    }
}

private class JSerialCommWrapper(
    private val portName: String,
    private val baudRate: Int,
    private val dataBits: Int,
    private val stopBits: Int,
    private val parity: String,
    private val timeoutMs: Int
) : SerialPortWrapper {
    private var port: SerialPort? = null

    fun getCommPortId(): String = portName

    override fun getBaudRate(): Int = baudRate

    fun getFlowControlIn(): Int = 0

    fun getFlowControlOut(): Int = 0

    override fun getDataBits(): Int = dataBits

    override fun getStopBits(): Int = when (stopBits) {
        2 -> SerialPort.TWO_STOP_BITS
        else -> SerialPort.ONE_STOP_BIT
    }

    override fun getParity(): Int = when (parity.uppercase()) {
        "EVEN" -> SerialPort.EVEN_PARITY
        "ODD" -> SerialPort.ODD_PARITY
        else -> SerialPort.NO_PARITY
    }

    override fun open() {
        val serial = SerialPort.getCommPort(portName)
        serial.setComPortParameters(baudRate, dataBits, getStopBits(), getParity())
        serial.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_BLOCKING,
            timeoutMs,
            timeoutMs
        )
        port = serial
        if (!serial.openPort()) {
            throw IllegalStateException("Nie mozna otworzyc portu $portName")
        }
    }

    override fun close() {
        port?.closePort()
    }

    override fun getInputStream() = requireNotNull(port).inputStream

    override fun getOutputStream() = requireNotNull(port).outputStream
}
