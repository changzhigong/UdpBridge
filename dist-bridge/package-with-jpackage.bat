@echo off
REM package-with-jpackage.bat
REM 用 jpackage 生成带 JRE 的 EXE 安装包（无需用户安装 Java）
REM 需要 JDK 14+（已安装 JDK 17）

set APP_NAME=LodopUdpBridge
set APP_VERSION=1.0.0
set VENDOR=FY App
set INPUT_DIR=%~dp0
set RUNTIME_IMAGE=%INPUT_DIR%runtime
set MAIN_JAR=LodopUdpBridge.jar
set OUTPUT_DIR=%INPUT_DIR%

echo ===================================================
echo  jpackage - 生成免 JRE 安装包
echo  应用: %APP_NAME%
echo  版本: %APP_VERSION%
echo  运行时: %RUNTIME_IMAGE%
echo ===================================================
echo.

if not exist "%RUNTIME_IMAGE%\bin\java.exe" (
    echo [错误] 未找到 runtime 目录，请先运行 create-runtime.bat
    pause
    exit /b 1
)

if not exist "%INPUT_DIR%%MAIN_JAR%" (
    echo [错误] 未找到 %MAIN_JAR%
    pause
    exit /b 1
)

"%JAVA_HOME%\bin\jpackage.exe" ^
  --type exe ^
  --name "%APP_NAME%" ^
  --app-version %APP_VERSION% ^
  --vendor "%VENDOR%" ^
  --input "%INPUT_DIR%" ^
  --main-jar %MAIN_JAR% ^
  --runtime-image "%RUNTIME_IMAGE%" ^
  --dest "%OUTPUT_DIR%" ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --win-menu-group "%APP_NAME%" ^
  --java-options "-Djava.awt.headless=false" ^
  2>&1

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ===================================================
    echo  安装包生成成功！
    echo  文件: %OUTPUT_DIR%\%APP_NAME%-%APP_VERSION%.exe
    echo ===================================================
) else (
    echo.
    echo jpackage 失败，错误码: %ERRORLEVEL%
)

pause
