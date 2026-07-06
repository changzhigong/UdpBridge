@echo off
REM LodopUdpBridge 启动脚本（纯后台版）
REM 双击运行即可启动 UDP 打印监听服务（端口 51010）
REM 无浏览器、无HTTP服务器、无桥页面，纯后台运行 + 系统托盘

echo =================================================
echo   LodopUdpBridge —— 局域网打印网关（纯后台版）
echo   端口: 51010（避免与系统端口冲突）
echo =================================================
echo.

REM 检查 Java 是否安装（需要 JDK 11+，支持 java.net.http.WebSocket）
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Java，请先安装 Java 11 或更高版本
    echo 下载地址: https://adoptium.net/temurin/releases
    pause
    exit /b 1
)

REM 检查 Java 版本是否 >= 11
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%v
)
set JAVA_VER=%JAVA_VER:"=%
for /f "tokens=1 delims=." %%v in ("%JAVA_VER%") do set JAVA_MAJOR=%%v
if %JAVA_MAJOR% lss 11 (
    echo [错误] Java 版本过低（%JAVA_VER%），需要 Java 11+ 以支持 WebSocket
    echo 下载地址: https://adoptium.net/temurin/releases
    pause
    exit /b 1
)

REM 编译 Java 文件（需要 JDK，非 JRE）
if not exist LodopUdpBridge.class (
    echo 正在编译 LodopUdpBridge.java ...
    javac LodopUdpBridge.java
    if %errorlevel% neq 0 (
        echo [错误] 编译失败（需要 JDK 而非 JRE）
        echo 确保 javac 命令可用
        pause
        exit /b 1
    )
    echo 编译成功
    echo.
)

REM 启动监听服务
echo 正在启动 UDP 打印监听服务（端口 51010）...
echo 纯后台运行 + 系统托盘图标
echo （托盘图标可查看打印机列表/测试页/退出）
echo.
java LodopUdpBridge

pause
