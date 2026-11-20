@echo off
cd /d "%~dp0"
if exist .venv\Scripts\python.exe (
    .venv\Scripts\python.exe tools\update.py
) else (
    python tools\update.py
)
pause
