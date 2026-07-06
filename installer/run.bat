@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 打印网关 - 双桥启动

set "JAVA=jre\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

echo ========================================
echo   打印网关 - 启动中...
echo   CLODOP 桥:     UDP 51010
echo   通用打印网关:   UDP 52010
echo ========================================
echo.

if not exist "LodopUdpBridge.jar" (
    echo [错误] LodopUdpBridge.jar 未找到
    pause
    goto :eof
)

if not exist "UniversalPrintBridge.jar" (
    echo [错误] UniversalPrintBridge.jar 未找到
    pause
    goto :eof
)

echo [启动] CLODOP 桥 (51010) ...
start "" "%JAVA%" -jar LodopUdpBridge.jar

echo [启动] 通用打印网关 (52010) ...
start "" "%JAVA%" -cp "UniversalPrintBridge.jar;lib\pdfbox-2.0.30.jar;lib\fontbox-2.0.30.jar;lib\commons-logging-1.2.jar" UniversalPrintBridge

echo.
echo 两个网关已启动,请检查系统托盘图标:
echo   蓝色 P = CLODOP 桥 (51010)
echo   绿色 P = 通用打印网关 (52010)
echo.
pause
