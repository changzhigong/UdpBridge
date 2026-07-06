; LodopUdpBridge_NSIS.nsi
; NSIS 安装包脚本 - 生成 EXE 安装包
; 编译：拖拽此文件到 makensis.exe 上，或运行 makensis LodopUdpBridge_NSIS.nsi
;
; 功能：安装、卸载、修复、开始菜单快捷方式、注册表卸载信息

;==============================================================
; 包含头文件
;==============================================================
!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"

;==============================================================
; 基本设置
;==============================================================
Name "LodopUdpBridge 打印网关"
OutFile "LodopUdpBridge_Setup.exe"
InstallDir "$PROGRAMFILES64\LodopUdpBridge"
RequestExecutionLevel admin  ; 需要管理员权限（写 Program Files）
SetCompressor lzma          ; 最佳压缩
Unicode True                ; 支持中文

;==============================================================
; 版本信息
;==============================================================
VIProductVersion "1.0.0.0"
VIAddVersionKey "ProductName" "LodopUdpBridge"
VIAddVersionKey "ProductVersion" "1.0.0"
VIAddVersionKey "CompanyName" "FY App"
VIAddVersionKey "LegalCopyright" "FY App"
VIAddVersionKey "FileDescription" "LodopUdpBridge 局域网打印网关安装程序"
VIAddVersionKey "FileVersion" "1.0.0"

;==============================================================
; MUI2 界面设置
;==============================================================
!insertmacro MUI_SET MUI_ABORTWARNING
!insertmacro MUI_SET MUI_ICON "${NSISDIR}\Contrib\Graphics\Icons\modern-install.ico"
!insertmacro MUI_SET MUI_UNICON "${NSISDIR}\Contrib\Graphics\Icons\modern-uninstall.ico"

; 欢迎页面
!insertmacro MUI_PAGE_WELCOME
; 安装目录选择页面
!insertmacro MUI_PAGE_DIRECTORY
; 安装进度页面
!insertmacro MUI_PAGE_INSTFILES
; 完成页面（可选运行程序）
!insertmacro MUI_PAGE_FINISH

; 卸载页面
!insertmacro MUI_UNPAGE_WELCOME
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

; 语言（简体中文）
!insertmacro MUI_LANGUAGE "SimpChinese"

;==============================================================
; 安装区段
;==============================================================
Section "主程序" SecMain
    SectionIn RO  ; 必选

    SetOutPath "$INSTDIR"

    ; 复制文件
    File "LodopUdpBridge.jar"
    File "run.bat"

    ; 创建数据目录（日志等，用户可写）
    CreateDirectory "$APPDATA\LodopUdpBridge"

    ; 注册表卸载信息（控制面板-程序和功能）
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "DisplayName" "LodopUdpBridge 打印网关"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "InstallLocation" "$INSTDIR"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "DisplayVersion" "1.0.0"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "Publisher" "FY App"
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "NoModify" 0x1
    WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" \
        "NoRepair" 0x0

    ; 开始菜单快捷方式
    CreateDirectory "$SMPROGRAMS\LodopUdpBridge"
    CreateShortcut "$SMPROGRAMS\LodopUdpBridge\启动 LodopUdpBridge.lnk" "$INSTDIR\run.bat" "" "$INSTDIR"
    CreateShortcut "$SMPROGRAMS\LodopUdpBridge\卸载 LodopUdpBridge.lnk" "$INSTDIR\uninstall.exe"

    ; 创建卸载程序
    WriteUninstaller "$INSTDIR\uninstall.exe"

    ; 检测 Java（可选，写入注册表标记）
    Call CheckJava

SectionEnd

;==============================================================
; 卸载区段
;==============================================================
Section "Uninstall"

    ; 结束正在运行的 Java 进程
    ExecWait 'taskkill /F /IM javaw.exe /FI "WINDOWTITLE eq LodopUdpBridge"' $0

    ; 删除安装目录
    RMDir /r "$INSTDIR"

    ; 删除开始菜单
    RMDir /r "$SMPROGRAMS\LodopUdpBridge"

    ; 删除注册表卸载信息
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge"

    ; 询问是否删除数据文件
    MessageBox MB_YESNO|MB_ICONQUESTION "是否删除配置和数据文件（%APPDATA%\LodopUdpBridge）？" IDYES DeleteData
    GoTo SkipDelete
    DeleteData:
        RMDir /r "$APPDATA\LodopUdpBridge"
    SkipDelete:

SectionEnd

;==============================================================
; 函数：检测 Java 是否已安装
;==============================================================
Function CheckJava
    ClearErrors
    ReadRegStr $0 HKLM "SOFTWARE\JavaSoft\Java Runtime Environment" "CurrentVersion"
    IfErrors 0 JavaFound
    ReadRegStr $0 HKLM "SOFTWARE\JavaSoft\JDK" "CurrentVersion"
    IfErrors JavaNotFound JavaFound
    JavaNotFound:
        MessageBox MB_OK|MB_ICONWARNING "未检测到 Java 安装。请先安装 Java 11 或更高版本。$\n下载地址：https://www.oracle.com/java/technologies/downloads/"
        Goto EndCheck
    JavaFound:
        DetailPrint "检测到 Java: $0"
    EndCheck:
FunctionEnd

;==============================================================
; 初始化函数（安装前检查）
;==============================================================
Function .onInit
    ; 检查是否已安装（升级）
    ReadRegStr $0 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\LodopUdpBridge" "UninstallString"
    StrCmp $0 "" Done
    MessageBox MB_OKCANCEL|MB_ICONINFORMATION "检测到已安装 LodopUdpBridge，是否先卸载旧版本？" IDOK UninstallOld
    Abort
    UninstallOld:
        ExecWait '$0 /S _?=$INSTDIR'
    Done:
FunctionEnd
