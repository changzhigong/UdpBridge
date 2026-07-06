// launch.c - 无控制台启动 LodopUdpBridge
// 编译（需用 64 位 cl.exe 或 gcc -mwindows）：
//   cl /Fe:launch.exe /link user32.lib shell32.lib launch.c
// 或直接用 gcc: gcc -o launch.exe -mwindows launch.c -lshell32

#include <windows.h>
#include <stdio.h>
#include <string.h>

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    char exePath[MAX_PATH];
    char jarPath[MAX_PATH];
    char cmd[4096];
    char workDir[MAX_PATH];
    
    // 获取本 exe 所在目录
    GetModuleFileName(NULL, exePath, MAX_PATH);
    char *lastSlash = strrchr(exePath, '\\');
    if (lastSlash) *(lastSlash + 1) = '\0';
    
    // jar 路径 = exe目录 + LodopUdpBridge.jar
    snprintf(jarPath, MAX_PATH, "%sLodopUdpBridge.jar", exePath);
    
    // javaw.exe 路径 = exe目录 + jre\bin\javaw.exe
    snprintf(cmd, 4096, "\"%sjre\\bin\\javaw.exe\" -jar \"%s\"", exePath, jarPath);
    
    // 工作目录 = exe目录
    snprintf(workDir, MAX_PATH, "%s", exePath);
    
    // 启动进程（无控制台）
    STARTUPINFO si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    ZeroMemory(&pi, sizeof(pi));
    
    if (!CreateProcess(NULL, cmd, NULL, NULL, FALSE, 0, NULL, workDir, &si, &pi)) {
        char errMsg[512];
        snprintf(errMsg, 512, "启动失败！\n\n请确认 jre 目录存在。\n\n错误码: %lu", GetLastError());
        MessageBox(NULL, errMsg, "Lodop UDP Bridge", MB_ICONERROR | MB_OK);
        return 1;
    }
    
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}
