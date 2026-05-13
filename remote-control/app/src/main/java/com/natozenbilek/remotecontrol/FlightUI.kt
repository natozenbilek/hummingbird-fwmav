package com.natozenbilek.remotecontrol

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun FlightScreen(
    settings: FlightSettings,
    link: FlightLink,
    connected: Boolean,
    telemetry: FlightTelemetry,
    logLines: List<String>
) {
    var lastCommand by remember { mutableStateOf(FlightCommand()) }

    fun sendCommand(cmd: FlightCommand) {
        lastCommand = cmd
        link.sendCommand(cmd)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // TOP BAND – single row with 4 cards: Status / Telemetry / Angles / Log
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(
                modifier = Modifier.weight(1f),
                connected = connected,
                telemetry = telemetry
            )
            TelemetryMiniCard(
                modifier = Modifier.weight(1f),
                telemetry = telemetry,
                lastCommand = lastCommand
            )
            AnglesCard(
                modifier = Modifier.weight(1f),
                telemetry = telemetry
            )
            LogCard(
                modifier = Modifier.weight(1f),
                logLines = logLines
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // BELOW THE INFO ROW – Two joystick rows + emergency button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Joysticks: left Throttle/Yaw, right-aligned Roll/Pitch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left stick: Throttle / Yaw
                Joystick(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 360.dp),
                    title = "Throttle / Yaw",
                    helper = "LEFT STICK – THROTTLE / YAW",
                    settings = settings,
                    isThrottleStick = true
                ) { xNorm, yNorm ->
                    val yaw = xNorm
                    // Stick yukarı = throttle 1, merkez/aşağı = throttle 0.
                    // Demo için "parmak çekilince motor dursun" davranışı; drone-hover semantiği
                    // istenirse ((-yNorm + 1f) / 2f) ile değiştirilebilir.
                    val throttle = (-yNorm).coerceIn(0f, 1f)
                    sendCommand(
                        lastCommand.copy(
                            yaw = yaw,
                            throttle = throttle,
                            emergency = false
                        )
                    )
                }

                // Right stick: Roll / Pitch – right-aligned
                Joystick(
                    modifier = Modifier
                        .widthIn(max = 360.dp),
                    title = "Roll / Pitch",
                    helper = "RIGHT STICK – ROLL / PITCH",
                    settings = settings,
                    isThrottleStick = false
                ) { xNorm, yNorm ->
                    val roll = xNorm
                    val pitch = -yNorm
                    sendCommand(
                        lastCommand.copy(
                            roll = roll,
                            pitch = pitch,
                            emergency = false
                        )
                    )
                }
            }

            // BELOW THE JOYSTICKS – Emergency button
            Button(
                onClick = {
                    val emergencyCmd = lastCommand.copy(
                        throttle = 0f,
                        emergency = true
                    )
                    sendCommand(emergencyCmd)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB00020),
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "EMERGENCY STOP",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: FlightSettings,
    onSettingsChange: (FlightSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Control Scheme",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Mode 2 (Left: Throttle/Yaw, Right: Roll/Pitch)",
                    color = Color(0xFFB3B3B3),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Throttle Behavior",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(throttleBehavior = ThrottleBehavior.RETURN_TO_CENTER))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.throttleBehavior == ThrottleBehavior.RETURN_TO_CENTER)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        )
                    ) {
                        Text("Return to center")
                    }
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(throttleBehavior = ThrottleBehavior.HOLD_POSITION))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.throttleBehavior == ThrottleBehavior.HOLD_POSITION)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        )
                    ) {
                        Text("Hold position")
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Joystick",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sensitivity: ${"%.1f".format(settings.joystickSensitivity)}x",
                    color = Color(0xFFB3B3B3),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(joystickSensitivity = 0.7f))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.joystickSensitivity == 0.7f)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Low") }
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(joystickSensitivity = 1.0f))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.joystickSensitivity == 1.0f)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Normal") }
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(joystickSensitivity = 1.3f))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.joystickSensitivity == 1.3f)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("High") }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Deadzone: ${"%.2f".format(settings.joystickDeadzone)}",
                    color = Color(0xFFB3B3B3),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(joystickDeadzone = 0.03f))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.joystickDeadzone == 0.03f)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Small") }
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(joystickDeadzone = 0.08f))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.joystickDeadzone == 0.08f)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Medium") }
                    Button(
                        onClick = {
                            onSettingsChange(settings.copy(joystickDeadzone = 0.15f))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.joystickDeadzone == 0.15f)
                                Color(0xFF1DB954) else Color(0xFF303030)
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Large") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF181818))) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Link",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Current: Simulation (Mock link). BLE/STM32 links will be added here later.",
                    color = Color(0xFFB3B3B3),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(
            onClick = { onSettingsChange(FlightSettings()) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303030))
        ) {
            Text(
                text = "Restore defaults",
                color = Color.White
            )
        }
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier = Modifier,
    connected: Boolean,
    telemetry: FlightTelemetry
) {
    Card(
        modifier = Modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1F1F1F),
                        Color(0xFF181818)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 1000f)
                )
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Column(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Status",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Link: " + if (connected) "Connected" else "Disconnected",
                color = if (connected) Color(0xFF1DB954) else Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "ESP Motor: ${(telemetry.motorThrottle * 100).toInt()}%",
                color = when {
                    telemetry.motorThrottle <= 0.001f -> Color(0xFFB3B3B3)
                    telemetry.motorThrottle < 0.5f -> Color(0xFF1DB954)
                    telemetry.motorThrottle < 0.85f -> Color(0xFFFFA000)
                    else -> Color(0xFFFF5252)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Emergency: " + if (telemetry.emergencyActive) "ACTIVE" else "Normal",
                color = if (telemetry.emergencyActive) Color(0xFFFF5252) else Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AnglesCard(
    modifier: Modifier = Modifier,
    telemetry: FlightTelemetry
) {
    Card(
        modifier = Modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1F1F1F),
                        Color(0xFF181818)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 1000f)
                )
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Column(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Angles",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Roll: ${"%.1f".format(telemetry.rollDeg)}°",
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Pitch: ${"%.1f".format(telemetry.pitchDeg)}°",
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Yaw: ${"%.1f".format(telemetry.yawDeg)}°",
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TelemetryMiniCard(
    modifier: Modifier = Modifier,
    telemetry: FlightTelemetry,
    lastCommand: FlightCommand
) {
    Card(
        modifier = Modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1F1F1F),
                        Color(0xFF181818)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1000f, 1000f)
                )
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Column(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Telemetry",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Battery: ${"%.2f".format(telemetry.batteryVoltage)} V",
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Roll/Pitch/Yaw: " +
                    "${"%.1f".format(telemetry.rollDeg)} / " +
                    "${"%.1f".format(telemetry.pitchDeg)} / " +
                    "${"%.1f".format(telemetry.yawDeg)}",
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Throttle cmd: ${"%.0f".format(lastCommand.throttle * 100)}%",
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LogCard(
    modifier: Modifier = Modifier,
    logLines: List<String>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101010))
    ) {
        Column(
            modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = "Log",
                color = Color(0xFFEEEEEE),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            logLines.forEachIndexed { index, line ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF1A1A1A))
                    )
                }
                Text(
                    text = line,
                    color = Color(0xFFB3B3B3),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun Joystick(
    modifier: Modifier = Modifier,
    title: String,
    helper: String,
    settings: FlightSettings,
    isThrottleStick: Boolean,
    onChange: (xNorm: Float, yNorm: Float) -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var targetX by remember { mutableStateOf(0f) }
    var targetY by remember { mutableStateOf(0f) }

    val deadzone = settings.joystickDeadzone
    val sensitivity = settings.joystickSensitivity

    // Animated values for smooth joystick movement
    val animatedX by animateFloatAsState(
        targetValue = targetX,
        animationSpec = tween(durationMillis = 150),
        label = "joystick_x"
    )
    val animatedY by animateFloatAsState(
        targetValue = targetY,
        animationSpec = tween(durationMillis = 150),
        label = "joystick_y"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )

            // Joystick base size and pixel radius based on screen density
            val baseDiameter = 128.dp
            val density = LocalDensity.current
            val radiusPx = with(density) { (baseDiameter / 2).toPx() }

            Box(
                modifier = Modifier
                    // Stick base: radial gradient + subtle blur effect
                    .size(baseDiameter)
                    .shadow(8.dp, shape = CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF202020),
                                Color(0xFF101010)
                            )
                        ),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFF2F2F2F),
                        shape = CircleShape
                    )
                    .drawBehind {
                        // N / E / S / W tick marks
                        val r = size.minDimension / 2f
                        val tickLength = r * 0.12f
                        val strokeWidth = 3f

                        // North
                        drawLine(
                            color = Color(0xFF3A3A3A),
                            start = Offset(x = size.width / 2f, y = r - tickLength),
                            end = Offset(x = size.width / 2f, y = r + tickLength),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // South
                        drawLine(
                            color = Color(0xFF3A3A3A),
                            start = Offset(x = size.width / 2f, y = size.height - r - tickLength),
                            end = Offset(x = size.width / 2f, y = size.height - r + tickLength),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // West
                        drawLine(
                            color = Color(0xFF3A3A3A),
                            start = Offset(x = r - tickLength, y = size.height / 2f),
                            end = Offset(x = r + tickLength, y = size.height / 2f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                        // East
                        drawLine(
                            color = Color(0xFF3A3A3A),
                            start = Offset(x = size.width - r - tickLength, y = size.height / 2f),
                            end = Offset(x = size.width - r + tickLength, y = size.height / 2f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }
                    .pointerInput(settings.throttleBehavior, isThrottleStick) {
                        detectDragGestures(
                            onDragEnd = {
                                if (!isThrottleStick || settings.throttleBehavior == ThrottleBehavior.RETURN_TO_CENTER) {
                                    targetX = 0f
                                    targetY = 0f
                                    offsetX = 0f
                                    offsetY = 0f
                                    onChange(0f, 0f)
                                }
                            },
                            onDragCancel = {
                                if (!isThrottleStick || settings.throttleBehavior == ThrottleBehavior.RETURN_TO_CENTER) {
                                    targetX = 0f
                                    targetY = 0f
                                    offsetX = 0f
                                    offsetY = 0f
                                    onChange(0f, 0f)
                                }
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val newX = (offsetX + dragAmount.x).coerceIn(-radiusPx, radiusPx)
                            val newY = (offsetY + dragAmount.y).coerceIn(-radiusPx, radiusPx)
                            offsetX = newX
                            offsetY = newY
                            targetX = newX
                            targetY = newY

                            var normX = (newX / radiusPx).coerceIn(-1f, 1f)
                            var normY = (newY / radiusPx).coerceIn(-1f, 1f)

                            if (abs(normX) < deadzone) normX = 0f
                            if (abs(normY) < deadzone) normY = 0f

                            normX *= sensitivity
                            normY *= sensitivity

                            onChange(normX.coerceIn(-1f, 1f), normY.coerceIn(-1f, 1f))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        // Thin green ring around center dot
                        .size(40.dp)
                        .shadow(6.dp, shape = CircleShape)
                        .offset {
                            IntOffset(
                                x = animatedX.roundToInt(),
                                y = animatedY.roundToInt()
                            )
                        }
                        .background(Color(0xFF121212), shape = CircleShape)
                        .border(
                            width = 2.dp,
                            color = Color(0xFF1DB954),
                            shape = CircleShape
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(24.dp)
                            .background(Color(0xFF1DB954), shape = CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * Sade kontrol ekranı: tek bir yatay slider motor hızını 0..1 arası oransal sürer.
 * Slider'ı orta noktaya çekersen ~%50 güç motora gider. STOP butonu slider'ı sıfırlar.
 *
 * Motor sürücü / motor bağlı olmasa bile pipeline gözle doğrulanabilir:
 * ESP onboard LED slider değeri >0 iken yanar, serial monitor MOTOR ON/OFF basar.
 */
@Composable
fun SimpleControlScreen(
    link: FlightLink,
    connected: Boolean,
    telemetry: FlightTelemetry,
    logLines: List<String>
) {
    var throttle by remember { mutableStateOf(0f) }

    // Link koparsa slider'ı sıfırla; firmware'de zaten emergency tetikleniyor.
    LaunchedEffect(connected) {
        if (!connected) throttle = 0f
    }

    fun setThrottle(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        throttle = clamped
        link.sendCommand(
            FlightCommand(
                roll = 0f,
                pitch = 0f,
                yaw = 0f,
                throttle = clamped,
                emergency = false
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Üst durum şeridi — toplam yüksekliğin ~%35'i (log uzasa da büyümesin)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.35f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusCard(
                modifier = Modifier.weight(1f),
                connected = connected,
                telemetry = telemetry
            )
            LogCard(
                modifier = Modifier.weight(2f),
                logLines = logLines
            )
        }

        // Slider + etiketler bölgesi — kalan dikey alanı doldurur
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${(throttle * 100).toInt()}%",
                style = MaterialTheme.typography.displayLarge,
                color = when {
                    !connected -> Color(0xFF707070)
                    throttle <= 0.001f -> Color(0xFFB3B3B3)
                    throttle < 0.5f -> Color(0xFF1DB954)
                    throttle < 0.85f -> Color(0xFFFFA000)
                    else -> Color(0xFFFF5252)
                },
                fontWeight = FontWeight.Black,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Slider(
                value = throttle,
                onValueChange = { newValue ->
                    if (connected) setThrottle(newValue)
                },
                valueRange = 0f..1f,
                enabled = connected,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF1DB954),
                    activeTrackColor = Color(0xFF1DB954),
                    inactiveTrackColor = Color(0xFF303030),
                    disabledThumbColor = Color(0xFF505050),
                    disabledActiveTrackColor = Color(0xFF303030),
                    disabledInactiveTrackColor = Color(0xFF202020)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0%", color = Color(0xFFB3B3B3), style = MaterialTheme.typography.bodySmall)
                Text("50%", color = Color(0xFFB3B3B3), style = MaterialTheme.typography.bodySmall)
                Text("100%", color = Color(0xFFB3B3B3), style = MaterialTheme.typography.bodySmall)
            }
        }


        // STOP butonu — slider'ı sıfırlar ve tek emergency komutu yollar.
        Button(
            onClick = {
                throttle = 0f
                link.sendCommand(
                    FlightCommand(throttle = 0f, emergency = true)
                )
            },
            enabled = connected,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFB00020),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF303030),
                disabledContentColor = Color(0xFF707070)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text(
                text = "STOP",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )
        }
    }
}
