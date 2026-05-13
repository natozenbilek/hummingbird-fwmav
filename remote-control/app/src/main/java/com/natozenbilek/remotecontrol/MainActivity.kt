package com.natozenbilek.remotecontrol

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.natozenbilek.remotecontrol.ui.theme.RemoteControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteControlTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { SettingsRepository(context) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = FlightSettings())
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Basit mod = tek tuş motor on/off testi (default). Gelişmiş = orijinal joystick UI.
    var simpleMode by rememberSaveable { mutableStateOf(true) }

    // Gerçek BLE bağlantısı. Tek instance, AppRoot'un ömrü boyunca yaşar.
    val link = remember { BleFlightLink(context) }
    val connected by link.connectionState.collectAsState()
    val telemetry by link.telemetryState.collectAsState()
    val logLines by link.logState.collectAsState()

    // İzin ve Bluetooth durumu — runtime'da değişebilir, bu yüzden mutableState.
    var permissionsGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    var bluetoothEnabled by remember { mutableStateOf(isBluetoothEnabled(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted && bluetoothEnabled) {
            link.connect()
        }
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        bluetoothEnabled = isBluetoothEnabled(context)
        if (permissionsGranted && bluetoothEnabled) {
            link.connect()
        }
    }

    // Uygulama foreground'a geldiğinde Bluetooth/izin durumunu tazele
    // (kullanıcı ayarlardan değiştirip dönmüş olabilir).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasBluetoothPermissions(context)
                bluetoothEnabled = isBluetoothEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            link.disconnect()
        }
    }

    fun handleConnectTap() {
        if (connected) {
            link.disconnect()
            return
        }
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredBluetoothPermissions())
            return
        }
        if (!bluetoothEnabled) {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        link.connect()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181818))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Remote Flight",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            showSettings -> "Settings"
                            simpleMode -> "Simple — One-tap Test"
                            else -> "Advanced — Joystick"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3B3B3)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConnectionPill(
                        connected = connected,
                        bluetoothEnabled = bluetoothEnabled,
                        permissionsGranted = permissionsGranted
                    )
                    Button(
                        onClick = { handleConnectTap() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connected) Color(0xFFB00020) else Color(0xFF1DB954),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = when {
                                connected -> "Disconnect"
                                !permissionsGranted -> "Grant Permission"
                                !bluetoothEnabled -> "Enable Bluetooth"
                                else -> "Connect"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (!showSettings) {
                        TextButton(onClick = { simpleMode = !simpleMode }) {
                            Text(
                                text = if (simpleMode) "Advanced" else "Simple",
                                color = Color(0xFF1DB954),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(
                            text = if (showSettings) "Back" else "Settings",
                            color = Color(0xFF1DB954),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF121212))
        ) {
            when {
                showSettings -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = { newSettings ->
                        scope.launch(Dispatchers.IO) {
                            settingsRepository.saveSettings(newSettings)
                        }
                    }
                )
                simpleMode -> SimpleControlScreen(
                    link = link,
                    connected = connected,
                    telemetry = telemetry,
                    logLines = logLines
                )
                else -> FlightScreen(
                    settings = settings,
                    link = link,
                    connected = connected,
                    telemetry = telemetry,
                    logLines = logLines
                )
            }
        }
    }
}

@Composable
private fun ConnectionPill(
    connected: Boolean,
    bluetoothEnabled: Boolean,
    permissionsGranted: Boolean
) {
    val (label, color) = when {
        connected -> "CONNECTED" to Color(0xFF1DB954)
        !permissionsGranted -> "NO PERMISSION" to Color(0xFFFFC107)
        !bluetoothEnabled -> "BT OFF" to Color(0xFFFFC107)
        else -> "DISCONNECTED" to Color(0xFF808080)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = color, shape = CircleShape)
        )
        Text(
            text = label,
            color = Color(0xFFB3B3B3),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun hasBluetoothPermissions(context: Context): Boolean {
    return requiredBluetoothPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun isBluetoothEnabled(context: Context): Boolean {
    val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
    return manager.adapter?.isEnabled == true
}
