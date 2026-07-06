@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 通用打印网关 (UDP 52010)

echo ======================================
echo   通用打印网关 - UniversalPrintBridge
echo   UDP 端口: 52010
echo ======================================
echo.

if not exist "UniversalPrintBridge.class" (
    echo [错误] 未找到编译产物,正在编译...
    javac -cp "lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar;lib/commons-logging-1.2.jar" --release 17 -encoding UTF-8 UniversalPrintBridge.java
    if %errorlevel% neq 0 (
        echo [错误] 编译失败
        pause
        goto :eof
    )
    echo [成功] 编译完成
)

echo [启动] 通用打印网关...
echo.
java -cp ".;lib/pdfbox-2.0.30.jar;lib/fontbox-2.0.30.jar;lib/commons-logging-1.2.jar" UniversalPrintBridge
pause
