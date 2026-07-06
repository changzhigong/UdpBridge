Option Explicit
Dim Fso, WshShell, AppDir, JarPath, UniJarPath, JavaPath, LibPath, LibPdf, LibFont, LibLog

Set Fso = CreateObject("Scripting.FileSystemObject")
Set WshShell = CreateObject("WScript.Shell")

' 安装目录
AppDir = Fso.GetParentFolderName(WScript.ScriptFullName)
WshShell.CurrentDirectory = AppDir
JavaPath = Fso.BuildPath(AppDir, "jre\bin\javaw.exe")
If Not Fso.FileExists(JavaPath) Then
    MsgBox "JRE not found" & vbCrLf & AppDir, vbCritical, "打印网关"
    WScript.Quit 1
End If

' ==================== 启动 CLODOP 桥 (端口 51010) ====================
JarPath = Fso.BuildPath(AppDir, "LodopUdpBridge.jar")
If Fso.FileExists(JarPath) Then
    WshShell.Run """" & JavaPath & """ -jar """ & JarPath & """", 0, False
Else
    MsgBox "[CLODOP 桥] LodopUdpBridge.jar not found", vbExclamation, "打印网关"
End If

' ==================== 启动通用打印网关 (端口 52010) ====================
UniJarPath = Fso.BuildPath(AppDir, "UniversalPrintBridge.jar")
LibPdf = Fso.BuildPath(AppDir, "lib\pdfbox-2.0.30.jar")
LibFont = Fso.BuildPath(AppDir, "lib\fontbox-2.0.30.jar")
LibLog = Fso.BuildPath(AppDir, "lib\commons-logging-1.2.jar")
If Fso.FileExists(UniJarPath) Then
    Dim Cp : Cp = UniJarPath & ";" & LibPdf & ";" & LibFont & ";" & LibLog
    WshShell.CurrentDirectory = AppDir
    WshShell.Run """" & JavaPath & """ -cp """ & Cp & """ UniversalPrintBridge", 0, False
Else
    MsgBox "[通用网关] UniversalPrintBridge.jar not found", vbExclamation, "打印网关"
End If
