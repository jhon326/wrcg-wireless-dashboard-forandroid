@echo off
setlocal
cd /d "%~dp0"

if not defined ANDROID_HOME if defined LOCALAPPDATA set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "APK=%~dp0..\dist\WRCG-Dash-0.3.0-debug.apk"

if not exist "%ADB%" (
    echo ADB was not found.
    pause
    exit /b 1
)

if not exist "%APK%" (
    echo Build the APK first with build-apk.cmd.
    pause
    exit /b 1
)

"%ADB%" devices
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo.
    echo Installation failed. Enable USB debugging and accept the phone prompt.
)
pause
