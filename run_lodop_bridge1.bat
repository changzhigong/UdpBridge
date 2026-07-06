@echo off
REM LodopUdpBridge
REM  UDP  51010
echo =================================================
echo   LodopUdpBridge 
echo 51010
echo.

REM  JDK 11+  java.net.http.WebSocket
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo  Java， Java 11
    echo  https://adoptium.net/temurin/releases
    pause
    exit /b 1
)

REM Java>= 11
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%v
)
set JAVA_VER=%JAVA_VER:"=%
for /f "tokens=1 delims=." %%v in ("%JAVA_VER%") do set JAVA_MAJOR=%%v
if %JAVA_MAJOR% lss 11 (

    pause
    exit /b 1
)

if not exist LodopUdpBridge.class (
    echo  LodopUdpBridge.java ...
    javac LodopUdpBridge.java
    if %errorlevel% neq 0 (
        echo   JDK NOT JRE）
        echo javac
        pause
        exit /b 1
    )
    echo done
    echo.
)

REM start
echo  UDP 51010
echo.
java LodopUdpBridge

pause
