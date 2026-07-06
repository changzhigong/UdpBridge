; LodopUdpBridge.iss
; Inno Setup 脚本 - 生成带 JRE 的 EXE 安装包
; 编译：ISCC.exe LodopUdpBridge.iss
;
; 功能：安装、卸载、修复、开始菜单、控制面板卸载、Java 内置无需安装
;       可选自启动、桌面快捷方式、安装后启动

[Setup]
; 基本信息
AppName=LodopUdpBridge 打印网关
AppVersion=1.0.0
AppPublisher=FY App
AppPublisherURL=https://github.com/changzhigong/fycrg-app
AppSupportURL=https://github.com/changzhigong/fycrg-app
AppUpdatesURL=https://github.com/changzhigong/fycrg-app

; 安装设置
DefaultDirName={autopf}\LodopUdpBridge
DefaultGroupName=LodopUdpBridge
AllowNoIcons=yes
LicenseFile=
InfoBeforeFile=
InfoAfterFile=

; 输出设置
OutputDir=.
OutputBaseFilename=LodopUdpBridge_Setup
SetupIconFile=

; 压缩
Compression=lzma
SolidCompression=yes
LZMANumBlockThreads=2

; 权限（写 Program Files 需要管理员）
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog

; 卸载
UninstallDisplayIcon={app}\runtime\bin\javaw.exe
Uninstallable=yes
CreateUninstallRegKey=yes

; 语言
ShowLanguageDialog=no

[Languages]
Name: "chinese"; MessagesFile: "compiler:Default.isl"

[Tasks]
; 桌面快捷方式（可选）
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
; 开机自启动（可选）
Name: "autostart"; Description: "开机自动启动 LodopUdpBridge"; GroupDescription: "启动选项"; Flags: unchecked

[Files]
; 主 JAR
Source: "LodopUdpBridge.jar"; DestDir: "{app}"; Flags: ignoreversion
; 启动脚本（用户手动启动 / 开始菜单）
Source: "run.bat"; DestDir: "{app}"; Flags: ignoreversion
; 静默启动脚本（用于自启动，无窗口）
Source: "run_silent.bat"; DestDir: "{app}"; Flags: ignoreversion
; bundled runtime（JRE，递归复制所有子目录）
Source: "runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; 开始菜单 - 启动
Name: "{group}\启动 LodopUdpBridge"; Filename: "{app}\run.bat"; WorkingDir: "{app}"; IconFilename: "{app}\runtime\bin\javaw.exe"; IconIndex: 0
; 开始菜单 - 卸载
Name: "{group}\卸载 LodopUdpBridge"; Filename: "{uninstallexe}"
; 桌面快捷方式（可选）
Name: "{autodesktop}\LodopUdpBridge"; Filename: "{app}\run.bat"; WorkingDir: "{app}"; IconFilename: "{app}\runtime\bin\javaw.exe"; IconIndex: 0; Tasks: desktopicon

[Run]
; 安装完成后可选启动
Filename: "{app}\run.bat"; Description: "启动 LodopUdpBridge"; Flags: nowait postinstall runascurrentuser; StatusMsg: "正在启动 LodopUdpBridge..."

[Registry]
; 可选自启动（勾选 Tasks 中的 autostart 时写入注册表）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "LodopUdpBridge"; ValueData: """{app}\run_silent.bat"""; Flags: uninsdeletevalue; Tasks: autostart

[UninstallDelete]
; 卸载时清理数据目录（用户确认）
Type: filesandordirs; Name: "{localappdata}\LodopUdpBridge"

[Code]
// 卸载前确认是否删除配置数据
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\LodopUdpBridge');
    if DirExists(DataDir) then
    begin
      if MsgBox('是否删除配置和数据文件？' + #13#10 + DataDir, mbConfirmation, MB_YESNO) = IDYES then
      begin
        DelTree(DataDir, True, True, True);
      end;
    end;
  end;
end;

// 检测是否已安装（升级时先卸载旧版）
function InitializeSetup(): Boolean;
var
  UninstallPath: String;
  ResultCode: Integer;
begin
  Result := True;
  UninstallPath := ExpandConstant('{uninstallexe}');
  if FileExists(UninstallPath) then
  begin
    if MsgBox('检测到已安装 LodopUdpBridge，是否先卸载旧版本？', mbConfirmation, MB_YESNO) = IDYES then
    begin
      Exec(UninstallPath, '/SILENT', '', SW_SHOWNORMAL, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;
