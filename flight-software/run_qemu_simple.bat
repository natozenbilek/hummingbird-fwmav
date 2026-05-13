@echo off
echo ================================================
echo     ESP32 QEMU Simulator Baslatiliyor...
echo ================================================
echo.

cd /d "C:\Users\asus\Documents\workspace\Flight Software"

echo ESP-IDF ortamini hazirlaniyor...
call C:\Espressif\idf_cmd_init.bat

echo.
echo QEMU baslatiliyor...
echo.

idf.py qemu monitor

pause



