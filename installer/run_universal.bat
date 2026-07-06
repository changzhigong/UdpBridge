@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 通用打印网关 (UDP 52010)

echo ======================================
echo   通用打印网关 - UniversalPrintBridge
echo   UDP 端口: 52010
echo ======================================
echo.

if not exist "UniversalPrintBridge.jar" (
    echo [错误] 未找到 UniversalPrintBridge.jar
    pause
    goto :eof
)

if not exist "lib\pdfbox-2.0.30.jar" (
    echo [错误] 未找到 lib 依赖文件
    pause
    goto :eof
)

echo [启动] 通用打印网关...

if exist "jre\bin\javaw.exe" (
    start "" "jre\bin\javaw.exe" -cp "UniversalPrintBridge.jar;lib\pdfbox-2.0.30.jar;lib\fontbox-2.0.30.jar;lib\commons-logging-1.2.jar" UniversalPrintBridge
) else if exist "..\jre\bin\javaw.exe" (
    start "" "..\jre\bin\javaw.exe" -cp "UniversalPrintBridge.jar;lib\pdfbox-2.0.30.jar;lib\fontbox-2.0.30.jar;lib\commons-logging-1.2.jar" UniversalPrintBridge
) else (
    java -cp "UniversalPrintBridge.jar;lib\pdfbox-2.0.30.jar;lib\fontbox-2.0.30.jar;lib\commons-logging-1.2.jar" UniversalPrintBridge
)
