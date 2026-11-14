@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title WRCG 无线仪表盘 - 源码版

where node >nul 2>nul
if errorlevel 1 (
    echo.
    echo 未找到 Node.js。
    echo 这个脚本面向已安装开发环境的用户，请先安装 Node.js 20 或更高版本：
    echo https://nodejs.org/
    echo.
    pause
    exit /b 1
)

node -e "process.exit(Number(process.versions.node.split('.')[0]) >= 20 ? 0 : 1)"
if errorlevel 1 (
    echo.
    echo 当前 Node.js 版本过低，需要 20 或更高版本。
    node --version
    echo.
    pause
    exit /b 1
)

echo 正在启动 WRCG 无线仪表盘...
echo.
node src\desktop-entry.js
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo 程序异常退出，错误码：%EXIT_CODE%
    pause
)
exit /b %EXIT_CODE%
