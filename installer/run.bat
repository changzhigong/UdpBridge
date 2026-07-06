@echo off
cd /d "%~dp0"

if not exist "LodopUdpBridge.jar" goto :eof

if exist "jre\bin\javaw.exe" (
    "jre\bin\javaw.exe" -jar LodopUdpBridge.jar %*
) else (
    javaw -jar LodopUdpBridge.jar %*
)
