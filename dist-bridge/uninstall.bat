@echo off
REM uninstall.bat - 卸载脚本
set APP_NAME=LodopUdpBridge
set INSTALL_DIR=%ProgramFiles%\LodopUdpBridge

echo ===================================================
echo  %APP_NAME% 卸载程序
echo ===================================================
echo.

REM 结束正在运行的 Java 进程
taskkill /F /IM java.exe /FI "WINDOWTITLE eq LodopUdpBridge" 2>nul
taskkill /F /IM javaw.exe 2>nul

REM 删除安装目录
if exist "%INSTALL_DIR%" rmdir /S /Q "%INSTALL_DIR%"

REM 删除开始菜单
set MENU_DIR=%ProgramData%\Microsoft\Windows\Start Menu\Programs\%APP_NAME%
if exist "%MENU_DIR%" rmdir /S /Q "%MENU_DIR%"

REM 删除注册表卸载信息
reg delete "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\%APP_NAME%" /f >nul 2>&1

REM 删除数据目录（可选）
set /p DEL_DATA=是否删除配置和数据文件（%APPDATA%\%APP_NAME%）？[Y/N]:
if /i "%DEL_DATA%"=="Y" (
    if exist "%APPDATA%\%APP_NAME%" rmdir /S /Q "%APPDATA%\%APP_NAME%"
)

echo.
echo 卸载完成！
pause
