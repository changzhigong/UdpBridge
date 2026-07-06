@echo off
REM install.bat - 安装脚本（由自解压包调用）
REM 需要管理员权限

set APP_NAME=LodopUdpBridge
set INSTALL_DIR=%ProgramFiles%\LodopUdpBridge
set DATA_DIR=%APPDATA%\LodopUdpBridge

echo ===================================================
echo  %APP_NAME% 安装程序
echo ===================================================
echo.

REM 创建安装目录
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

REM 复制文件
copy /Y "%~dp0LodopUdpBridge.jar" "%INSTALL_DIR%\" >nul
copy /Y "%~dp0run.bat" "%INSTALL_DIR%\" >nul

REM 创建开始菜单快捷方式
set MENU_DIR=%ProgramData%\Microsoft\Windows\Start Menu\Programs\%APP_NAME%
if not exist "%MENU_DIR%" mkdir "%MENU_DIR%"

REM 用 PowerShell 创建快捷方式
powershell -Command "$s = (New-Object -COM WScript.Shell).CreateShortcut('%MENU_DIR%\启动 %APP_NAME%.lnk'); $s.TargetPath = '%INSTALL_DIR%\run.bat'; $s.WorkingDirectory = '%INSTALL_DIR%'; $s.Save()" 2>nul

powershell -Command "$s = (New-Object -COM WScript.Shell).CreateShortcut('%MENU_DIR%\卸载 %APP_NAME%.lnk'); $s.TargetPath = 'msiexec.exe'; $s.Arguments = '/x {ProductCode}'; $s.Save()" 2>nul

REM 注册卸载信息（ARP）
reg add "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\%APP_NAME%" /v DisplayName /t REG_SZ /d "%APP_NAME%" /f >nul 2>&1
reg add "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\%APP_NAME%" /v UninstallString /t REG_SZ /d "%INSTALL_DIR%\uninstall.bat" /f >nul 2>&1
reg add "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\%APP_NAME%" /v InstallLocation /t REG_SZ /d "%INSTALL_DIR%" /f >nul 2>&1
reg add "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\%APP_NAME%" /v DisplayVersion /t REG_SZ /d "1.0.0" /f >nul 2>&1

echo.
echo 安装完成！
echo 安装目录: %INSTALL_DIR%
echo 数据目录: %DATA_DIR%
echo.
echo 开始菜单: %MENU_DIR%
echo.
pause
