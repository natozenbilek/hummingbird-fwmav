package com.natozenbilek.remotecontrol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class ControlCommands(
    val throttle: Float = 0.0f,
    val rollTrim: Float = 0.0f,
    val pitchTrim: Float = 0.0f,
    val yawTrim: Float = 0.0f,
    val emergencyArmed: Boolean = false,
    val emergencyCleared: Boolean = false,
    val riseCommand: Boolean = false
)

object BleProtocol {
    private const val COMMAND_SCALE = 10.0f
    private const val TELEMETRY_SCALE = 10.0f
    private const val CRC_POLY = 0x07

    const val COMMAND_PACKET_SIZE = 9
    const val TELEMETRY_PACKET_SIZE = 20

    /**
     * Legacy ESP32 command encoding.
     * Currently unused by the new FlightCommand / FlightLink pipeline,
     * but kept for compatibility with the original BLE protocol.
     */
    fun encodeCommand(command: ControlCommands): ByteArray {
        val buffer = ByteBuffer.allocate(COMMAND_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putShort(scaleDeg(command.rollTrim))
        buffer.putShort(scaleDeg(command.pitchTrim))
        buffer.putShort(scaleDeg(command.yawTrim))

        val throttlePercent = (command.throttle.coerceIn(0.0f, 1.0f) * 100.0f).roundToInt()
        buffer.put(throttlePercent.coerceIn(0, 100).toByte())

        var flags = 0
        if (command.emergencyArmed) {
            flags = flags or 0x01
        }
        if (command.emergencyCleared) {
            flags = flags or 0x02
        }
        if (command.riseCommand) {
            flags = flags or 0x04
        }
        buffer.put(flags.toByte())

        val array = buffer.array()
        array[array.lastIndex] = crc8(array, COMMAND_PACKET_SIZE - 1).toByte()
        return array
    }

    fun decodeTelemetry(payload: ByteArray): BleTelemetry? {
        if (payload.size != TELEMETRY_PACKET_SIZE) {
            return null
        }

        val expectedCrc = crc8(payload, TELEMETRY_PACKET_SIZE - 1)
        val actualCrc = payload.last().toInt() and 0xFF
        if (expectedCrc != actualCrc) {
            return null
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val roll = buffer.short.toFloat() / TELEMETRY_SCALE
        val pitch = buffer.short.toFloat() / TELEMETRY_SCALE
        val servoRoll = buffer.short.toFloat() / TELEMETRY_SCALE
        val servoPitch = buffer.short.toFloat() / TELEMETRY_SCALE
        val servoYaw = buffer.short.toFloat() / TELEMETRY_SCALE
        val yaw = buffer.short.toFloat() / TELEMETRY_SCALE

        val motorThrottlePercent = buffer.get().toInt() and 0xFF
        val flags = buffer.get().toInt() and 0xFF
        val loopCounter = buffer.int.toLong() and 0xFFFFFFFFL
        val statusFlags = buffer.get().toInt() and 0xFF
        // CRC byte is appended at the end; calculated above

        val linkActive = (statusFlags and 0x02) != 0
        val notifyEnabled = (statusFlags and 0x04) != 0

        return BleTelemetry(
            rollDeg = roll,
            pitchDeg = pitch,
            yawDeg = yaw,
            servoRollDeg = servoRoll,
            servoPitchDeg = servoPitch,
            servoYawDeg = servoYaw,
            motorThrottle = motorThrottlePercent / 100.0f,
            emergencyActive = (flags and 0x01) != 0,
            loopCounter = loopCounter,
            statusFlags = statusFlags,
            rawFlags = flags,
            linkActive = linkActive,
            notifyEnabled = notifyEnabled
        )
    }

    private fun crc8(data: ByteArray, length: Int): Int {
        var crc = 0x00
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) {
                    ((crc shl 1) xor CRC_POLY) and 0xFF
                } else {
                    (crc shl 1) and 0xFF
                }
            }
        }
        return crc and 0xFF
    }

    private fun scaleDeg(value: Float): Short {
        val clamped = value.coerceIn(-3276.8f, 3276.7f)
        return (clamped * COMMAND_SCALE).roundToInt().toShort()
    }
}

data class BleTelemetry(
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val servoRollDeg: Float,
    val servoPitchDeg: Float,
    val servoYawDeg: Float,
    val motorThrottle: Float,
    val emergencyActive: Boolean,
    val loopCounter: Long,
    val statusFlags: Int,
    val rawFlags: Int,
    val linkActive: Boolean,
    val notifyEnabled: Boolean
)

