@echo off
REM ============================================================
REM  LodopUdpBridge 静默启动（用于自启动 / 计划任务）
REM  无窗口、无暂停，直接后台运行
REM ============================================================

cd /d "%~dp0"

if not exist "LodopUdpBridge.jar" goto :eof

if exist "runtime\bin\javaw.exe" (
    "runtime\bin\javaw.exe" -jar LodopUdpBridge.jar %*
) else (
    javaw.exe -jar LodopUdpBridge.jar %*
)
