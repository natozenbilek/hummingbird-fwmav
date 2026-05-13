# ESP32 Flight Software

ESP32-based flight control firmware, tested in QEMU emulator and validated on physical hardware.

## Development Environments

### QEMU Simulator (Testing without Physical Hardware)

Test in QEMU emulator:

```bash
# Method 1: Batch file (Easiest on Windows)
run_qemu_simple.bat

# Method 2: Python script
python run_qemu.py

# Method 3: Manual command
idf.py qemu monitor
```

### Physical ESP32 Board

Flash to physical hardware:

```bash
# Build
idf.py build

# Flash (replace COM3 with your port)
idf.py -p COM3 flash monitor
```

## Project Structure

```
Flight Software/
├── main/
│   ├── main.c                 # Main application code
│   ├── flight_control.h       # Flight control headers
│   ├── flight_control_math.c  # Flight control math functions
│   ├── CMakeLists.txt         # Component build config
│   └── Kconfig.projbuild      # Component configuration
├── tests/                     # Unit tests
├── CMakeLists.txt             # Project build configuration
├── LICENSE                    # License information
├── sdkconfig                  # ESP-IDF configuration
├── sdkconfig.old             # Previous configuration backup
├── run_qemu_simple.bat       # QEMU launcher (Windows)
├── run_qemu.py               # QEMU launcher (Python)
└── README.md                 # This file
```

## Code Structure

The main application code is organized into distinct sections:

- **QEMU Test Code**: Conditional code for emulator testing (marked with `#ifdef CONFIG_IDF_TARGET_ESP32`)
- **Flight Control Logic**: Core flight control algorithms in separate functions
- **Hardware Initialization**: Setup code for physical hardware

## Migration to Physical Hardware

1. **Remove QEMU Code**: Delete QEMU-specific test functions and conditional blocks
2. **Set Target**: Configure for your ESP32 variant (`idf.py set-target esp32` or `esp32s3`)
3. **Add Real Code**: Implement actual flight control logic in the designated functions
4. **Build and Flash**: Use `idf.py build` and `idf.py flash` commands

## Resources

- [ESP-IDF Documentation](https://docs.espressif.com/projects/esp-idf/en/latest/get-started/index.html)
- [QEMU Emulator Guide](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/api-guides/tools/qemu.html)

## License

Code in this repository is in the Public Domain (CC0 licensed).
