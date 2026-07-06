@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 通用打印网关 (UDP 52010)

echo ======================================
echo   通用打印网关 - UniversalPrintBridge
echo   UDP 端口: 52010
echo ======================================
echo.

REM 优先使用内嵌 JRE（安装目录下），否则回退系统 java
set "JAVA=java"
if exist "jre\bin\java.exe" set "JAVA=jre\bin\java.exe"
if exist "..\jre\bin\java.exe" set "JAVA=..\jre\bin\java.exe"

set "CP=UniversalPrintBridge.jar;lib\pdfbox-2.0.30.jar;lib\fontbox-2.0.30.jar;lib\commons-logging-1.2.jar"

if not exist "UniversalPrintBridge.jar" (
    echo [警告] 未找到 UniversalPrintBridge.jar，尝试从源码编译...
    if not exist "UniversalPrintBridge.java" (
        echo [错误] 既无 UniversalPrintBridge.jar 也无源码 UniversalPrintBridge.java，无法启动
        pause
        goto :eof
    )
    javac -cp "lib\pdfbox-2.0.30.jar;lib\fontbox-2.0.30.jar;lib\commons-logging-1.2.jar" --release 17 -encoding UTF-8 UniversalPrintBridge.java
    if %errorlevel% neq 0 (
        echo [错误] 编译失败，无法启动
        pause
        goto :eof
    )
)

echo [启动] 通用打印网关...
echo.
"%JAVA%" -cp "%CP%" UniversalPrintBridge
pause
