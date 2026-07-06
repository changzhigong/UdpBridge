@echo off
REM create-runtime.bat - 用 jlink 创建自定义 Java 运行时（免 JRE 安装包用）
REM 需要 JDK 11+（自带 jlink）

set JDK_DIR=C:\Program Files\Java\jdk-17
set OUTPUT_DIR=%~dp0runtime

echo ===================================================
echo  创建自定义 Java 运行时（jlink）
echo  JDK: %JDK_DIR%
echo  输出: %OUTPUT_DIR%
echo ===================================================
echo.

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM 需要的模块（LodopUdpBridge 用到的）：
REM java.base       - 基础类库
REM java.desktop    - AWT/Swing/SystemTray
REM java.logging    - 日志
REM java.net.http   - WebSocket 客户端
REM java.scripting  - ScriptEngine（备用）

"%JDK_DIR%\bin\jlink.exe" ^
  --module-path "%JDK_DIR%\jmods" ^
  --add-modules java.base,java.desktop,java.logging,java.net.http ^
  --output "%OUTPUT_DIR%" ^
  --compress=2 ^
  --no-header-files ^
  --no-man-pages

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===================================================
    echo  自定义运行时创建成功！
    echo  大小:
    for /f "tokens=3" %%a in ('dir "%OUTPUT_DIR%" /s /-c ^| find "个文件"') do set SIZE=%%a
    du -sh "%OUTPUT_DIR%" 2>nul || dir "%OUTPUT_DIR%" /s | find "个文件"
    echo ===================================================
) else (
    echo.
    echo jlink 失败，错误码: %ERRORLEVEL%
)

pause
