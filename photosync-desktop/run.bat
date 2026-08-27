@echo off
setlocal
cd /d "%~dp0"
set "PHOTOSYNC_PYTHON=%~dp0.venv\Scripts\python.exe"
if not exist "%PHOTOSYNC_PYTHON%" set "PHOTOSYNC_PYTHON=python"
"%PHOTOSYNC_PYTHON%" -m photosync_desktop
if errorlevel 1 pause
