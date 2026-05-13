#!/usr/bin/env python3
"""
ESP32 QEMU Launcher
This script opens a new terminal window and launches the QEMU simulator.
"""

import subprocess
import os
import sys
import shutil
from pathlib import Path

def main():
    print("=" * 50)
    print("  ESP32 QEMU Simulator Launcher")
    print("=" * 50)
    print()
    
    # Project directory
    project_dir = os.path.dirname(os.path.abspath(__file__))
    print(f"Project Directory: {project_dir}")
    
    # ESP-IDF tools
    idf_path = r"C:\Espressif\frameworks\esp-idf-v5.3.1"
    python_path = r"C:\Espressif\python_env\idf5.3_py3.9_env\Scripts\python.exe"
    idf_py = os.path.join(idf_path, "tools", "idf.py")

    if not os.path.exists(python_path):
        print(f"[WARNING] Specified Python path not found: {python_path}")
        python_path = sys.executable
        print(f"[INFO] Using system Python: {python_path}")
    else:
        print(f"[INFO] ESP-IDF Python found: {python_path}")

    if not os.path.exists(idf_py):
        print(f"[ERROR] idf.py not found: {idf_py}")
        print("Please check your ESP-IDF installation.")
        return 1
    
    # Run QEMU command in new terminal
    cmd_to_run = [
        python_path,
        idf_py,
        "qemu",
        "monitor",
    ]

    inner_cmd = subprocess.list2cmdline(cmd_to_run)
    cmd = [
        "cmd.exe",
        "/k",
        inner_cmd
    ]

    env = os.environ.copy()
    env.setdefault("IDF_PATH", idf_path)

    python_env_dir = Path(python_path).resolve().parent.parent
    if python_env_dir.exists():
        env["IDF_PYTHON_ENV_PATH"] = str(python_env_dir)
        scripts_dir = python_env_dir / "Scripts"
        if scripts_dir.exists() and str(scripts_dir) not in env["PATH"]:
            env["PATH"] = f"{scripts_dir};{env['PATH']}"
        print(f"[INFO] IDF_PYTHON_ENV_PATH set: {python_env_dir}")
    else:
        print(f"[WARNING] Python virtual environment not found: {python_env_dir}")

    def ensure_tool(tool_name, search_root, binary_subpaths=None, executable_name=None):
        if binary_subpaths is None:
            binary_subpaths = ["bin"]
        elif isinstance(binary_subpaths, str):
            binary_subpaths = [binary_subpaths]
        if shutil.which(tool_name, path=env.get("PATH", "")):
            return None
        root_path = Path(search_root)
        selected_bin = None
        if root_path.is_dir():
            for sub in sorted(root_path.iterdir(), reverse=True):
                for binary_subpath in binary_subpaths:
                    candidate = sub / binary_subpath if binary_subpath else sub
                    exe_name = executable_name or f"{tool_name}.exe"
                    if (candidate / exe_name).exists():
                        selected_bin = candidate
                        break
                if selected_bin:
                    break
        if selected_bin:
            env["PATH"] = f"{selected_bin};{env.get('PATH', '')}"
            print(f"[INFO] {tool_name} added to PATH: {selected_bin}")
            return selected_bin
        else:
            print(f"[WARNING] '{tool_name}' not found on PATH. Please check ESP-IDF Tools installation.")
            return None

    ensure_tool("cmake", r"C:\Espressif\tools\cmake")
    ensure_tool("ninja", r"C:\Espressif\tools\ninja", binary_subpaths=["bin", ""], executable_name="ninja.exe")
    ensure_tool("mingw32-make", r"C:\Espressif\tools\make", binary_subpaths=["bin", ""], executable_name="make.exe")

    qemu_root = Path(r"C:\Espressif\tools\qemu-xtensa")
    qemu_bin = None
    if qemu_root.is_dir():
        for sub in sorted(qemu_root.iterdir(), reverse=True):
            candidate = sub / "qemu" / "bin"
            exe = candidate / "qemu-system-xtensa.exe"
            if exe.exists():
                qemu_bin = candidate
                break
    if qemu_bin:
        env["PATH"] = f"{qemu_bin};{env['PATH']}"
        env["QEMU_PATH"] = str(qemu_bin)
        print(f"[INFO] QEMU added to PATH: {qemu_bin}")
    else:
        print("[ERROR] qemu-system-xtensa not found. Please check ESP-IDF tools installation.")

    if not shutil.which("ninja", path=env.get("PATH", "")) and not shutil.which("ninja.exe", path=env.get("PATH", "")):
        print("[ERROR] 'ninja' tool not found; required for ESP-IDF projects.")
    if not shutil.which("mingw32-make", path=env.get("PATH", "")) and not shutil.which("make", path=env.get("PATH", "")):
        print("[WARNING] 'make' tool not found; no problem if ninja is available.")

    create_new_console = getattr(subprocess, "CREATE_NEW_CONSOLE", 0x00000010)
    
    print("Opening new terminal window...")
    print("Starting QEMU simulator...")
    print()
    print("NOTE: You will see ESP32 output in the newly opened window!")
    print("To exit: Press Ctrl+]")
    print()
    
    try:
        popen_kwargs = {"env": env, "cwd": project_dir, "creationflags": create_new_console}

        print(f"[INFO] Command to run: {inner_cmd}")
        subprocess.Popen(cmd, **popen_kwargs)
        print("[OK] New terminal window opened!")
        print("[OK] QEMU starting...")
        print()
        print("==> Check the newly opened window!")
    except Exception as e:
        print(f"[HATA] {e}")
        return 1
    
    return 0

if __name__ == "__main__":
    sys.exit(main())

