@echo off
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined ANDROID_HOME if defined LOCALAPPDATA set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

call gradlew.bat --no-daemon --console=plain assembleRelease
if errorlevel 1 exit /b 1

if not exist "..\dist" mkdir "..\dist"
copy /Y "app\build\outputs\apk\release\app-release.apk" "..\dist\WRCG-Dash-4.0.0-release.apk"
echo.
echo APK: %CD%\..\dist\WRCG-Dash-4.0.0-release.apk
