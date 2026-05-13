# Hummingbird FWMAV — control and embedded software

Single-author capstone work on the control and embedded software sub-system
of a bioinspired hummingbird flapping-wing micro aerial vehicle (FWMAV).
An ESP32-C3 SuperMini runs the firmware and drives a DRV8833 dual H-bridge
in sign-magnitude PWM at 20 kHz. An Android handset, written in Jetpack
Compose, acts as the BLE GATT central and commands the throttle in real
time over a 9-byte CRC-protected frame, while receiving 20-byte CRC-protected
telemetry at 10 Hz.

Demo (bench, 2026-05-11): https://youtu.be/utHcj4ybsKc

The full technical report is [`paper.pdf`](paper.pdf).

## What's in this repository

```
flight-software/   ESP32-C3 firmware (ESP-IDF v5.3 + FreeRTOS)
remote-control/    Android client (Kotlin / Jetpack Compose / Material 3)
paper.pdf          Accompanying conference-format report (IEEE template)
LICENSE            CC0 1.0 Universal
```

The mechanical airframe and the wing-stroke linkage are a separate
sub-team's sub-system and are out of scope for this work.

## Hardware

| Part                | Role                                  |
| ------------------- | ------------------------------------- |
| ESP32-C3 SuperMini  | MCU + BLE 5 peripheral                |
| DRV8833             | Dual H-bridge motor driver            |
| Brushed DC motor    | Demonstration load (bench test)       |
| MPU-6050            | 6-DoF IMU (integration-ready, not yet wired in firmware) |
| 3.7 V Li-Po         | On-board supply (target platform)     |

## Wiring (bench demo)

The bench setup uses two GPIOs on the ESP32-C3 to drive the DRV8833.

| ESP32-C3 pin | DRV8833 pin | Function                |
| ------------ | ----------- | ----------------------- |
| `GPIO3`      | `AIN1`      | PWM channel A           |
| `GPIO4`      | `AIN2`      | PWM channel B           |
| `3V3`        | `VCC`       | logic supply            |
| `GND`        | `GND`       | common ground           |
| (USB)        | (motor +/-) | DRV8833 `AOUT1`/`AOUT2` to motor leads |

The motor supply rail to DRV8833 `VM` comes from USB-C 5 V during bench
demo. For flight, swap to a 3.7 V Li-Po.

The on-board LED (`GPIO8`, active-low on SuperMini) mirrors the motor
command state, so the whole phone-to-firmware path can be verified even
with the motor disconnected.

## Quick start

### 1. Firmware (ESP32-C3)

Set up ESP-IDF v5.3 first:
<https://docs.espressif.com/projects/esp-idf/en/v5.3/esp32c3/get-started/index.html>

Then, from the repository root:

```bash
cd flight-software
. $IDF_PATH/export.sh
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/cu.usbmodem<PORT> flash monitor
```

The firmware logs over the USB CDC serial port at 115 200 baud. It
prints `BLE: ready, advertising as ESP32 Flight` on boot, runs the
control-math self-test, then waits for a BLE central to connect.

### 2. Host-side regression tests

The control math (clamp, IMU validation, complementary filter,
anti-windup PID) compiles for POSIX and is exercised by an automated
test runner:

```bash
cd flight-software/tests
cmake -S . -B build
cmake --build build
./build/run_control_tests
```

Expected output: `All control math tests passed.` and exit status 0.
The same C is recompiled for the ESP32 Xtensa target under QEMU and
re-exercised on every boot via `run_control_math_self_test()`.

### 3. Android client

Requirements: JDK 17 or newer, Android SDK API level 34 (installed
through Android Studio), an Android 12+ device with BLE.

Create a `local.properties` file pointing Gradle at your SDK:

```properties
sdk.dir=/path/to/your/Android/sdk
```

Then build a debug APK with the Gradle wrapper:

```bash
cd remote-control
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you do not have a system-wide JDK, point Gradle at Android Studio's
bundled JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

The app is locked to landscape orientation. On first launch it asks for
`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Android 12+) or
`ACCESS_FINE_LOCATION` (older). Tap **Connect**, pick `ESP32 Flight`
from the scan, then use the throttle slider in Simple mode or the
Mode 2 dual joystick in Advanced mode.

## BLE protocol

A single primary GATT service exposes two characteristics:

- **Command** (write-without-response, 9 bytes). Roll setpoint (int16),
  pitch setpoint (int16), yaw trim (int16), throttle percent (uint8),
  flags (uint8), CRC-8 (uint8). Angles encoded as deg × 10, little-endian.
- **Telemetry** (notify, 20 bytes). Estimated roll, pitch, yaw plus
  echoed servo commands, motor throttle, flags, a 32-bit control-loop
  counter, link-status byte, and a trailing CRC-8.

Both frames use CRC-8 with polynomial x⁸ + x² + x + 1 (`0x07`), initial
value `0x00`, over all bytes preceding the CRC. The handset's BleManager
negotiates a 64-byte MTU.

## Safety

The firmware enforces three independent fail-safes. All three force the
motor to zero duty and clear the cached command:

1. **Command watchdog (500 ms).** If no valid command frame arrives
   within this window the controller transitions to EMERGENCY. The app
   sends a heartbeat copy of the last command every 150 ms, well inside
   the window.
2. **BLE disconnect.** The `GATTS_DISCONNECT_EVT` callback triggers the
   emergency state explicitly. A reconnection re-starts at zero throttle;
   the previous setpoint is not restored.
3. **CRC mismatch.** A single corrupted command is treated as evidence
   that the link is unreliable for the immediately adjacent commands as
   well, so the controller enters EMERGENCY rather than discarding the
   single packet.

Recovery from EMERGENCY requires an explicit *clear* flag in a fresh,
CRC-valid command frame.

## Status and remaining integration

The control sub-system is complete and demonstrated on physical
hardware. Three integration tasks remain before flight on the mechanical
airframe:

- MPU-6050 I²C driver at 100 Hz to populate the IMU input
- Battery voltage ADC + resistor divider on a free C3 pin
- Roll/pitch/yaw → motor mixer (the DRV8833 has a second channel
  available on the same chip)

The throttle echo accuracy was measured on the bench: across an 11-point
slider sweep (0 – 100 % in 10 % steps), the firmware-reported throttle
tracks the commanded value within ±1 percentage point, the firmware's
integer-percent quantisation.

## License

This repository is released under the
[CC0 1.0 Universal](LICENSE) public-domain dedication. The same applies
to the firmware sub-tree (`flight-software/LICENSE`, identical text).

## Acknowledgments

Advisor: Assoc. Prof. Dr. Harun Artuner, Hacettepe University,
Department of Computer Engineering. Thank you for advising this work
throughout BBM479 Design Project I (Fall 2025) and BBM480 Design
Project II (Spring 2026), and for the feedback that shaped the safety
contract.

The mechanical airframe and the wing-stroke linkage were developed in
parallel by a mechanical engineering sub-team in the Hacettepe ME
Department's MMÜ497 Design Project:

- Abdulsamet Karakoç
- Ayşe Sıla Karaöz
- Fatih Gamsız
- Zeynepnur Cengiz

Supervised by Assoc. Prof. Dr. Mehmet Nurullah Balcı (Department Vice
Chair) and Asst. Prof. Dr. Ramin Barzegar. Their analysis of the
stroke-jamming and wobble mode in the early phase of the joint project
shaped the decision to keep the mechanical airframe out of scope for
the present sub-system.

## Author

Nezih Arhan Tözenbilek — Hacettepe University, Department of Computer
Engineering, BBM480 Design Project II, Spring 2026.
