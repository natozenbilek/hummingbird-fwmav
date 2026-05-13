package com.natozenbilek.remotecontrol

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BleManager'ı FlightLink arayüzüne adapte eden köprü.
 *
 * UI normalize edilmiş [FlightCommand] gönderir; bu sınıf onu
 * eski [ControlCommands] / [BleProtocol] formatına çevirip
 * ESP32 firmware'ine yazdırır. Telemetri ters yönde dönüştürülür.
 *
 * Compose tarafı için [connectionState] / [telemetryState] / [logState]
 * StateFlow'ları açılır; klasik callback API'si (FlightLink) de korunur.
 */
class BleFlightLink(context: Context) : FlightLink {

    override var onConnectionChanged: ((Boolean) -> Unit)? = null
    override var onTelemetry: ((FlightTelemetry) -> Unit)? = null
    override var onLog: ((String) -> Unit)? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _telemetryState = MutableStateFlow(FlightTelemetry())
    val telemetryState: StateFlow<FlightTelemetry> = _telemetryState.asStateFlow()

    private val _logState = MutableStateFlow<List<String>>(listOf("Waiting: tap Connect"))
    val logState: StateFlow<List<String>> = _logState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var currentCommand: FlightCommand = FlightCommand()

    private val manager = BleManager(context).also { ble ->
        ble.onConnectionChanged = { connected ->
            _connectionState.value = connected
            if (connected) {
                startHeartbeat()
            } else {
                stopHeartbeat()
                // Bağlantı koptuğunda son komut belleğini temizle: yeniden bağlanıldığında
                // motor kendiliğinden eski hızında dönmesin, kullanıcı tekrar tıklasın.
                currentCommand = FlightCommand()
            }
            onConnectionChanged?.invoke(connected)
        }
        ble.onLog = { message ->
            _logState.value = (_logState.value + message).takeLast(MAX_LOG_LINES)
            onLog?.invoke(message)
        }
        ble.onTelemetry = { bleTelemetry ->
            val flightTelemetry = FlightTelemetry(
                batteryVoltage = 0f,
                rollDeg = bleTelemetry.rollDeg,
                pitchDeg = bleTelemetry.pitchDeg,
                yawDeg = bleTelemetry.yawDeg,
                motorThrottle = bleTelemetry.motorThrottle,
                linkActive = bleTelemetry.linkActive,
                emergencyActive = bleTelemetry.emergencyActive
            )
            _telemetryState.value = flightTelemetry
            onTelemetry?.invoke(flightTelemetry)
        }
    }

    override fun connect() {
        appendLog("Connecting...")
        manager.connect()
    }

    override fun disconnect() {
        appendLog("Disconnecting")
        manager.disconnect()
    }

    override fun sendCommand(command: FlightCommand) {
        currentCommand = command
        sendPacket(command)

        if (command.emergency) {
            appendLog("EMERGENCY STOP sent")
        }
    }

    private fun sendPacket(command: FlightCommand) {
        val controlCmd = ControlCommands(
            rollTrim = command.roll * MAX_ROLL_DEG,
            pitchTrim = command.pitch * MAX_PITCH_DEG,
            yawTrim = command.yaw * MAX_YAW_DEG,
            throttle = command.throttle,
            emergencyArmed = command.emergency,
            // Acil durum çağrılmadığı sürece her komut emergency'yi temizler.
            emergencyCleared = !command.emergency,
            riseCommand = false
        )
        manager.sendCommand(BleProtocol.encodeCommand(controlCmd))
    }

    // Heartbeat: en son komutu HEARTBEAT_INTERVAL_MS aralıkla tekrar gönderir.
    // ESP32 firmware'inde 500 ms komut timeout'u var; link koparsa motor otomatik durur,
    // ama link sağlamken kullanıcı bir kez tıklayıp bekleyebilsin diye komut canlı tutulmalı.
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (manager.isConnected) {
                    sendPacket(currentCommand)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    val isConnected: Boolean
        get() = manager.isConnected

    private fun appendLog(message: String) {
        _logState.value = (_logState.value + message).takeLast(MAX_LOG_LINES)
        onLog?.invoke(message)
    }

    companion object {
        // FlightCommand normalize değerlerini (-1..+1) firmware'in beklediği
        // derece aralığına ölçekle. Firmware flight_control_set_targets içinde
        // ayrıca ±15° / ±10° clamp yapıyor — burada aynı limitleri kullanıyoruz.
        private const val MAX_ROLL_DEG = 15f
        private const val MAX_PITCH_DEG = 15f
        private const val MAX_YAW_DEG = 10f

        private const val MAX_LOG_LINES = 8

        // Heartbeat aralığı: 150 ms. Firmware timeout (500 ms) içinde rahatça komut geliyor,
        // BLE write akışı da bunaltılmıyor (yaklaşık 6.6 Hz).
        private const val HEARTBEAT_INTERVAL_MS = 150L
    }
}
