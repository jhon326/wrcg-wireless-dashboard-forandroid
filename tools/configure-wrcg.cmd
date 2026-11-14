@echo off
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0configure-wrcg.ps1" %*
set "RESULT=%ERRORLEVEL%"

if "%~1"=="" pause
exit /b %RESULT%
