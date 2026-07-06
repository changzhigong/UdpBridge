Option Explicit
Dim Fso, WshShell, AppDir, JarPath

Set Fso = CreateObject("Scripting.FileSystemObject")
Set WshShell = CreateObject("WScript.Shell")

' Get script directory (resolved, no trailing backslash)
AppDir = Fso.GetParentFolderName(WScript.ScriptFullName)
JarPath = Fso.BuildPath(AppDir, "LodopUdpBridge.jar")

If Not Fso.FileExists(JarPath) Then
    MsgBox "LodopUdpBridge.jar not found in:" & vbCrLf & AppDir, vbCritical, "Lodop UDP Bridge"
    WScript.Quit 1
End If

' Prefer bundled JRE (jre\bin\javaw.exe)
Dim JavaPath
JavaPath = Fso.BuildPath(AppDir, "jre\bin\javaw.exe")
If Fso.FileExists(JavaPath) Then
    WshShell.Run """" & JavaPath & """ -jar """ & JarPath & """", 0, False
    WScript.Quit 0
End If

' Fallback: system javaw.exe
WshShell.Run "javaw -jar """ & JarPath & """", 0, False
