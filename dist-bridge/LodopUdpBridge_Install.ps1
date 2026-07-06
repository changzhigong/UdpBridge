# LodopUdpBridge 安装脚本（PowerShell）
# 用法：
#   安装：右键此文件 -> 使用 PowerShell 运行（或以管理员身份运行）
#   卸载：LodopUdpBridge_Uninstall.ps1

param(
    [string]$Mode = "install"  # install 或 uninstall
)

$AppName = "LodopUdpBridge"
$InstallDir = Join-Path $env:ProgramFiles "LodopUdpBridge"
$DataDir = Join-Path $env:APPDATA $AppName
$MenuDir = Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs\$AppName"
$RegPath = "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$AppName"

# 检查管理员权限
function Test-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# 请求管理员权限
if (-not (Test-Admin)) {
    Write-Host "需要管理员权限，正在重新启动..." -ForegroundColor Yellow
    Start-Process powershell "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Mode $Mode" -Verb RunAs
    exit
}

if ($Mode -eq "uninstall") {
    # ==================== 卸载 ====================
    Write-Host "=== $AppName 卸载程序 ===" -ForegroundColor Cyan
    
    # 结束进程
    Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like "*$AppName*" } | Stop-Process -Force
    Get-Process javaw -ErrorAction SilentlyContinue | Stop-Process -Force
    
    # 删除安装目录
    if (Test-Path $InstallDir) {
        Remove-Item $InstallDir -Recurse -Force
        Write-Host "已删除安装目录: $InstallDir" -ForegroundColor Green
    }
    
    # 删除开始菜单
    if (Test-Path $MenuDir) {
        Remove-Item $MenuDir -Recurse -Force
        Write-Host "已删除开始菜单" -ForegroundColor Green
    }
    
    # 删除注册表
    if (Test-Path $RegPath) {
        Remove-Item $RegPath -Recurse -Force
        Write-Host "已删除注册表卸载信息" -ForegroundColor Green
    }
    
    # 询问是否删除数据
    $delData = Read-Host "是否删除配置和数据文件（$DataDir）？[Y/N]"
    if ($delData -eq "Y" -or $delData -eq "y") {
        if (Test-Path $DataDir) {
            Remove-Item $DataDir -Recurse -Force
            Write-Host "已删除数据目录" -ForegroundColor Green
        }
    }
    
    Write-Host "`n卸载完成！" -ForegroundColor Green
    pause
    exit
}

# ==================== 安装 ====================
Write-Host "=== $AppName 安装程序 ===" -ForegroundColor Cyan

# 检测 Java
$javaOk = $false
try {
    $javaVersion = & java -version 2>&1
    if ($LASTEXITCODE -eq 0) { $javaOk = $true }
} catch {}

if (-not $javaOk) {
    Write-Host "未检测到 Java 安装！" -ForegroundColor Red
    Write-Host "请先安装 Java 11 或更高版本" -ForegroundColor Yellow
    Write-Host "下载地址: https://www.oracle.com/java/technologies/downloads/" -ForegroundColor Yellow
    pause
    exit 1
}

# 创建目录
if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null }
if (-not (Test-Path $DataDir)) { New-Item -ItemType Directory -Path $DataDir -Force | Out-Null }
if (-not (Test-Path $MenuDir)) { New-Item -ItemType Directory -Path $MenuDir -Force | Out-Null }

# 复制文件（从脚本所在目录）
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Copy-Item "$scriptDir\LodopUdpBridge.jar" "$InstallDir\" -Force
Copy-Item "$scriptDir\run.bat" "$InstallDir\" -Force

# 创建开始菜单快捷方式
$WScriptShell = New-Object -ComObject WScript.Shell
$shortcut = $WScriptShell.CreateShortcut("$MenuDir\启动 $AppName.lnk")
$shortcut.TargetPath = "$InstallDir\run.bat"
$shortcut.WorkingDirectory = $InstallDir
$shortcut.Save()

$shortcut = $WScriptShell.CreateShortcut("$MenuDir\卸载 $AppName.lnk")
$shortcut.TargetPath = "powershell"
$shortcut.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$InstallDir\LodopUdpBridge_Uninstall.ps1`""
$shortcut.Save()

# 复制卸载脚本到安装目录
Copy-Item $PSCommandPath "$InstallDir\LodopUdpBridge_Uninstall.ps1" -Force

# 注册卸载信息
New-Item -Path $RegPath -Force | Out-Null
Set-ItemProperty -Path $RegPath -Name "DisplayName" -Value "$AppName 打印网关"
Set-ItemProperty -Path $RegPath -Name "UninstallString" -Value "powershell -NoProfile -ExecutionPolicy Bypass -File `"$InstallDir\LodopUdpBridge_Uninstall.ps1`""
Set-ItemProperty -Path $RegPath -Name "InstallLocation" -Value $InstallDir
Set-ItemProperty -Path $RegPath -Name "DisplayVersion" -Value "1.0.0"
Set-ItemProperty -Path $RegPath -Name "Publisher" -Value "FY App"

Write-Host "`n安装完成！" -ForegroundColor Green
Write-Host "  安装目录: $InstallDir"
Write-Host "  数据目录: $DataDir"
Write-Host "  开始菜单: $MenuDir"
Write-Host "`n可以按 Enter 键退出..." -ForegroundColor Gray
pause
