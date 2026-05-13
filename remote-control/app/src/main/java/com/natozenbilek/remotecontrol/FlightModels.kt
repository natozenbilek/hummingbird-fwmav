package com.natozenbilek.remotecontrol

/**
 * High-level flight command model used by the UI.
 *
 * All values are normalized and later mapped to the underlying
 * protocol (e.g. RC channels or custom STM32 inputs).
 *
 * - roll, pitch, yaw: range [-1.0, 1.0]
 * - throttle: range [0.0, 1.0]
 */
data class FlightCommand(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f,
    val throttle: Float = 0f,
    val emergency: Boolean = false
)

/**
 * Minimal telemetry model that the UI cares about.
 * Can be extended as the flight firmware evolves.
 */
data class FlightTelemetry(
    val batteryVoltage: Float = 0f,
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val motorThrottle: Float = 0f,  // ESP'nin uyguladığı motor seviyesi (0..1), tanı için kritik
    val linkActive: Boolean = false,
    val emergencyActive: Boolean = false
)

/**
 * Supported control schemes.
 *
 * MODE2 is the common layout:
 * - Left stick: Throttle / Yaw
 * - Right stick: Roll / Pitch
 */
enum class ControlScheme {
    MODE2
}

/**
 * Defines how throttle behaves when the user releases the stick.
 */
enum class ThrottleBehavior {
    /**
     * Joystick returns to center visually; center is mapped
     * to a configurable hover point in the protocol mapping.
     */
    RETURN_TO_CENTER,

    /**
     * Joystick position is held (RC-transmitter like).
     * Visually the knob stays where the user leaves it.
     */
    HOLD_POSITION
}

/**
 * User-facing configuration for controls and link behavior.
 * In a next step this can be persisted via DataStore.
 */
data class FlightSettings(
    val controlScheme: ControlScheme = ControlScheme.MODE2,
    val throttleBehavior: ThrottleBehavior = ThrottleBehavior.RETURN_TO_CENTER,
    val joystickSensitivity: Float = 1.0f,
    val joystickDeadzone: Float = 0.05f
)

/**
 * Abstraction over how commands/telemetry are transported.
 *
 * Implementations can be:
 * - MockFlightLink (local simulation)
 * - BleFlightLinkForEsp32 / BleFlightLinkForStm32 in the future
 */
interface FlightLink {
    fun connect()
    fun disconnect()

    fun sendCommand(command: FlightCommand)

    var onConnectionChanged: ((Boolean) -> Unit)?
    var onTelemetry: ((FlightTelemetry) -> Unit)?
    var onLog: ((String) -> Unit)?
}

/**
 * Simple in-app simulation used while no real BLE / STM32 link is present.
 */
class MockFlightLink : FlightLink {
    override var onConnectionChanged: ((Boolean) -> Unit)? = null
    override var onTelemetry: ((FlightTelemetry) -> Unit)? = null
    override var onLog: ((String) -> Unit)? = null

    private var connected = false
    private var lastCommand: FlightCommand = FlightCommand()

    override fun connect() {
        connected = true
        onLog?.invoke("Mock link connected")
        onConnectionChanged?.invoke(true)
    }

    override fun disconnect() {
        connected = false
        onLog?.invoke("Mock link disconnected")
        onConnectionChanged?.invoke(false)
    }

    override fun sendCommand(command: FlightCommand) {
        lastCommand = command
        if (!connected) {
            onLog?.invoke("Mock: command ignored (not connected)")
            return
        }

        // Very small fake telemetry echo so UI has something to show.
        val telemetry = FlightTelemetry(
            batteryVoltage = 7.4f,
            rollDeg = command.roll * 30f,
            pitchDeg = command.pitch * 30f,
            yawDeg = command.yaw * 45f,
            linkActive = true,
            emergencyActive = command.emergency
        )
        onTelemetry?.invoke(telemetry)
    }
}








