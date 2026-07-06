; LodopUdpBridge 安装包脚本
; 编译： "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" LodopUdpBridge.iss

[Setup]
AppName=Lodop UDP Bridge
AppVersion=1.2
AppPublisher=郑州晖锦
DefaultDirName={autopf}\LodopUdpBridge
DefaultGroupName=Lodop UDP Bridge
AllowNoIcons=yes
OutputDir=installer-output
OutputBaseFilename=LodopUdpBridge_Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible x86compatible
ArchitecturesInstallIn64BitMode=x64compatible
SourceDir=.

[Tasks]
Name: autostart; Description: 开机自动启动; GroupDescription: 启动设置; Flags: unchecked
Name: desktopicon; Description: 创建桌面快捷方式（后台静默运行）; GroupDescription: 快捷方式; Flags: unchecked

[Files]
Source: LodopUdpBridge.jar; DestDir: {app}; Flags: ignoreversion
Source: launch.vbs; DestDir: {app}; Flags: ignoreversion
Source: run.bat; DestDir: {app}; Flags: ignoreversion
Source: jre\*; DestDir: {app}\jre; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: {group}\Lodop UDP Bridge; Filename: {app}\launch.vbs; WorkingDir: {app}; IconFilename: {app}\launch.vbs
Name: {group}\Lodop UDP Bridge (控制台); Filename: {app}\run.bat; WorkingDir: {app}
Name: {group}\卸载 Lodop UDP Bridge; Filename: {uninstallexe}
Name: {autodesktop}\Lodop UDP Bridge; Filename: {app}\launch.vbs; WorkingDir: {app}; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: Software\Microsoft\Windows\CurrentVersion\Run; ValueType: string; ValueName: LodopUdpBridge; ValueData: """{app}\launch.vbs"""; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: {app}\launch.vbs; Description: 启动 Lodop UDP Bridge（后台静默运行）; Flags: nowait postinstall runascurrentuser shellexec

[Code]
// 卸载完成后确认是否删除配置数据和日志文件
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\LodopUdpBridge');
    if DirExists(DataDir) then
    begin
      if MsgBox('是否删除配置数据和日志文件？' + #13#10 + DataDir, mbConfirmation, MB_YESNO) = IDYES then
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
