@echo off
REM ============================================================
REM  LodopUdpBridge launcher
REM  用法：双击运行（后台，无控制台窗口）
REM ============================================================

cd /d "%~dp0"

if not exist "LodopUdpBridge.jar" (
    echo [ERROR] 找不到 LodopUdpBridge.jar
    pause
    goto :eof
)

if exist "runtime\bin\javaw.exe" (
    set "JAVA=runtime\bin\javaw.exe"
) else (
    set "JAVA=javaw.exe"
)

%JAVA% -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] 找不到 Java：%JAVA%
    echo 请确认 bundled runtime 完整，或系统已安装 Java
    pause
    goto :eof
)

REM 启动（javaw.exe 无控制台窗口，后台运行）
start "" "%JAVA%" -jar LodopUdpBridge.jar %*
