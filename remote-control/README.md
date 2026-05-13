# Remote Flight Control App

Modern Android application designed to control ESP32/STM32-based flight systems over Bluetooth Low Energy (BLE).  
Built with Jetpack Compose, featuring a Spotify-like dark theme, ergonomic controls, and real-time telemetry feedback.

## Features

- **Modular Architecture**: Clear separation between UI, domain model, and link layers
- **Ergonomic Controls**: Mode 2 control scheme (Left: Throttle/Yaw, Right: Roll/Pitch)
- **Persistent Settings**: User preferences are saved via DataStore
- **Customizable Joystick**: Adjustable sensitivity and deadzone
- **Throttle Behavior**: Return-to-center or Hold-position modes
- **Real-time Telemetry**: Battery voltage, angles, and system status
- **Modern UI**: Jetpack Compose + Material Design 3 with a Spotify-like dark theme
- **Animations**: Smooth joystick animations and visual feedback
- **Landscape Mode**: Forced landscape orientation for better ergonomics

## Requirements

- **Android**: Minimum API level 24 (Android 7.0)
- **Bluetooth**: BLE support required (for future STM32 link)
- **Permissions**: Location and Bluetooth permissions

## Setup

1. **Clone the Repository**: Ensure you have the complete project
2. **Open in Android Studio**: Import the `Remote Control/` directory
3. **Build**: Let Android Studio download dependencies and build the project
4. **Run**: Deploy to a device or emulator

## Architecture

```
Remote Control/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # App manifest (forced landscape)
│   │   ├── java/com/natozenbilek/remotecontrol/
│   │   │   ├── MainActivity.kt          # Main UI activity and AppRoot
│   │   │   ├── FlightModels.kt          # Domain models (FlightCommand, FlightTelemetry, FlightSettings)
│   │   │   ├── FlightUI.kt              # UI composables (FlightScreen, SettingsScreen, Joystick)
│   │   │   ├── SettingsRepository.kt    # DataStore-backed settings persistence
│   │   │   ├── FlightLink.kt            # Link abstraction (MockFlightLink implementation)
│   │   │   ├── BleManager.kt            # BLE connection manager (for future real link)
│   │   │   └── BleProtocol.kt           # BLE communication protocol
│   │   └── res/                         # Resources (themes, colors, etc.)
│   ├── src/androidTest/                 # Instrumentation tests
│   ├── src/test/                        # Unit tests
│   ├── build.gradle.kts                 # App build configuration
│   └── proguard-rules.pro               # ProGuard rules
├── gradle/
│   └── wrapper/                         # Gradle wrapper files
├── build.gradle.kts                     # Project build configuration
├── settings.gradle.kts                  # Project settings
├── gradle.properties                    # Gradle properties
├── gradlew                              # Gradle wrapper (Unix)
├── gradlew.bat                          # Gradle wrapper (Windows)
└── README.md                            # This file
```

## Key Components

### FlightModels.kt
- **FlightCommand**: Normalized flight commands (roll, pitch, yaw in [-1, 1], throttle in [0, 1])
- **FlightTelemetry**: Minimal telemetry model for the UI
- **FlightSettings**: User settings (control scheme, throttle behavior, joystick tuning)
- **FlightLink**: Abstraction for sending commands and receiving telemetry (MockFlightLink, future BLE links)

### FlightUI.kt
- **FlightScreen**: Main flight control screen (dual joysticks, telemetry cards)
- **SettingsScreen**: Settings screen (control scheme description, throttle behavior, joystick sensitivity/deadzone)
- **Joystick**: Animated, customizable joystick composable
- **StatusCard, AnglesCard, TelemetryMiniCard, LogCard**: Telemetry and log visualization

### SettingsRepository.kt
- Persists `FlightSettings` using DataStore
- Exposes a Flow-based reactive API for settings changes

### MainActivity.kt
- Hosts the `AppRoot` composable which switches between Flight and Settings screens
- Uses `SettingsRepository` to manage persistent settings
- Uses `MockFlightLink` for a local simulation link

## Control Scheme

### Mode 2 (Default)

- **Left Joystick**:
  - Y axis: Throttle (0–100%)
  - X axis: Yaw (rotation)
- **Right Joystick**:
  - X axis: Roll (bank left/right)
  - Y axis: Pitch (nose up/down)

### Throttle Behavior

- **Return to Center**: Joystick visually returns to center; center is mapped to a hover point in firmware
- **Hold Position**: Joystick position is held, similar to an RC transmitter

### Joystick Settings

- **Sensitivity**: Scales how strongly joystick motion affects roll/pitch/yaw (Low: 0.7x, Normal: 1.0x, High: 1.3x)
- **Deadzone**: Ignores small movements around the center (Small: 0.03, Medium: 0.08, Large: 0.15)

## BLE Communication (Future)

The app currently runs in simulation mode using `MockFlightLink`.  
In the future, real BLE implementations targeting ESP32/STM32 flight controllers can be added:

- **Service UUID**: `12345678-1234-5678-90ab-cdefcafebabe`
- **Command Characteristic**: Sends control commands
- **Telemetry Characteristic**: Receives flight system status data

`BleProtocol.kt` includes functions to encode `FlightCommand` into protocol byte arrays and decode telemetry payloads.

## Permissions

The app requires the following permissions:

- `BLUETOOTH` & `BLUETOOTH_ADMIN`: Basic Bluetooth functionality
- `ACCESS_FINE_LOCATION`: Required for BLE scanning on Android 6.0+
- `BLUETOOTH_SCAN` & `BLUETOOTH_CONNECT`: Modern Bluetooth permissions (Android 12+)

## Software Engineering Principles

- **Separation of Concerns**: UI, domain model, and link layers are clearly separated
- **Dependency Inversion**: `FlightLink` interface allows swapping mock/real implementations
- **Reactive Programming**: Flow-based settings management
- **Type Safety**: Kotlin data classes and enums for protocol-safe modeling
- **Documentation**: Public APIs are documented with KDoc
- **Testability**: Mock implementations make UI and domain logic easy to test

## Theme and Design

- **Spotify-like Dark Theme**: `#121212` background, `#181818` cards, `#1DB954` accent color
- **Ergonomic Layout**: Joysticks occupy the lower ~60% of the screen for thumb reach
- **Animations**: Smooth joystick motion (150 ms tween) for better feedback
- **Visual Feedback**: Shadows, elevations, and active state highlighting

## License

All code in this repository is released into the Public Domain (CC0 licensed).
