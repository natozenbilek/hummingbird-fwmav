package com.natozenbilek.remotecontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

class BleManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var isScanning = false

    @Volatile
    private var connected = false

    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    var onTelemetry: ((BleTelemetry) -> Unit)? = null

    val isConnected: Boolean
        get() = connected

    private val serviceUuid = UUID.fromString("12345678-1234-5678-90ab-cdefcafebabe")
    private val commandUuid = UUID.fromString("12345678-1234-5678-90ab-cdefc00d0001")
    private val telemetryUuid = UUID.fromString("12345678-1234-5678-90ab-cdefc0dead02")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 9-byte komut + 20-byte telemetri varsayılan MTU 23'e (20 B payload) sığar ama
    // sınıra çok yakın. 64 ister, ESP istediğini desteklerse hız ve kararlılık artar.
    private val MTU_REQUEST = 64

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasRequiredPermissions()) {
                log("Scan result unavailable: missing permission")
                stopScan()
                notifyConnection(false)
                return
            }
            val device = result.device ?: return
            if (!hasServiceUuid(result)) {
                return
            }
            stopScan()
            log("Device found: ${deviceLabel(device)}")
            connectGatt(device)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            log("Scan failed: $errorCode")
            notifyConnection(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("BLE connect error (status=$status), aborting")
                    gatt.safeDisconnect("Connect status non-zero")
                    return
                }
                if (!hasRequiredPermissions()) {
                    log("Service discovery failed: missing permission")
                    return
                }
                log("BLE connected, requesting MTU $MTU_REQUEST...")
                // MTU önce iste, sonra onMtuChanged → discoverServices.
                // Bu seri akış pek çok Android sürümünde race'i önler.
                val mtuRequested = try {
                    gatt.requestMtu(MTU_REQUEST)
                } catch (security: SecurityException) {
                    log("requestMtu permission error: ${security.message}")
                    false
                }
                if (!mtuRequested) {
                    log("MTU request not started, going directly to service discovery")
                    try {
                        gatt.discoverServices()
                    } catch (security: SecurityException) {
                        log("discoverServices permission error: ${security.message}")
                    }
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                log("BLE connection lost (status=$status)")
                cleanupGatt()
                notifyConnection(false)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu (status=$status), discovering services")
            try {
                gatt.discoverServices()
            } catch (security: SecurityException) {
                log("discoverServices permission error: ${security.message}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                gatt.safeDisconnect("Service discovery failed")
                return
            }

            val service = gatt.getService(serviceUuid)
            if (service == null) {
                log("Expected service not found")
                gatt.safeDisconnect("Service not found")
                return
            }

            commandCharacteristic = service.getCharacteristic(commandUuid)
            telemetryCharacteristic = service.getCharacteristic(telemetryUuid)

            if (commandCharacteristic == null || telemetryCharacteristic == null) {
                log("Command or telemetry characteristic missing")
                gatt.safeDisconnect("Characteristic missing")
                return
            }

            if (!enableTelemetryNotifications(gatt, telemetryCharacteristic!!)) {
                log("Failed to enable telemetry notification")
                gatt.safeDisconnect("Failed to enable notifications")
                return
            }

            connected = true
            notifyConnection(true)
            log("BLE services ready")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != telemetryUuid) {
                return
            }
            val payload = characteristic.value ?: return
            val telemetry = BleProtocol.decodeTelemetry(payload)
            if (telemetry != null) {
                mainHandler.post {
                    onTelemetry?.invoke(telemetry)
                }
            } else {
                log("Telemetry packet invalid")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Command write failed: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        if (connected) {
            log("Already connected")
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log("Bluetooth is not enabled")
            notifyConnection(false)
            return
        }
        if (!hasRequiredPermissions()) {
            log("Bluetooth permissions not granted")
            notifyConnection(false)
            return
        }
        if (isScanning) {
            log("Scan already in progress")
            return
        }
        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            log("BLE scanner unavailable")
            notifyConnection(false)
            return
        }

        log("Searching for BLE device...")
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(serviceUuid)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
        isScanning = true

        mainHandler.postDelayed({
            if (isScanning) {
                log("Scan timed out")
                stopScan()
                notifyConnection(false)
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        cleanupGatt()
        notifyConnection(false)
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(payload: ByteArray): Boolean {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic
        if (!connected || gatt == null || characteristic == null) {
            log("Command not sent: no connection")
            return false
        }
        if (!hasRequiredPermissions()) {
            log("Command not sent: missing permission")
            return false
        }

        val (success, statusCode) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            (status == BluetoothStatusCodes.SUCCESS) to status
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.value = payload
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                gatt.writeCharacteristic(characteristic) to BluetoothGatt.GATT_SUCCESS
            }
        }
        if (!success) {
            log("Command write request failed (status=$statusCode)")
        }
        return success
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        if (!hasRequiredPermissions()) {
            return
        }
        scanner?.stopScan(scanCallback)
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) {
            log("Unable to establish GATT connection: missing permission")
            return
        }
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private fun cleanupGatt() {
        connected = false
        commandCharacteristic = null
        telemetryCharacteristic = null
        if (bluetoothGatt != null) {
            if (!hasRequiredPermissions()) {
                log("Unable to close GATT: missing permission")
            } else {
                try {
                    bluetoothGatt?.close()
                } catch (security: SecurityException) {
                    log("Error closing GATT: ${security.message}")
                }
            }
        }
        bluetoothGatt = null
    }

    @SuppressLint("MissingPermission")
    private fun enableTelemetryNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            return false
        }
        val descriptor = characteristic.getDescriptor(cccdUuid) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (status != BluetoothStatusCodes.SUCCESS) {
                log("Notify descriptor write failed (status=$status)")
            }
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun deviceLabel(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return "BLE device"
        }
        return try {
            device.name ?: device.address ?: "BLE device"
        } catch (security: SecurityException) {
            "BLE device"
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.safeDisconnect(reason: String) {
        if (!hasRequiredPermissions()) {
            log("Disconnect skipped ($reason): missing permission")
            return
        }
        try {
            disconnect()
        } catch (security: SecurityException) {
            log("Disconnect failed ($reason): ${security.message}")
        }
    }

    private fun notifyConnection(state: Boolean) {
        mainHandler.post {
            onConnectionChanged?.invoke(state)
        }
    }

    private fun log(message: String) {
        mainHandler.post {
            onLog?.invoke(message)
        }
    }

    private fun hasServiceUuid(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        val uuids = record.serviceUuids ?: return false
        return uuids.any { it.uuid == serviceUuid }
    }
}

