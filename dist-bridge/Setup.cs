using System;
using System.IO;
using System.IO.Compression;
using System.Diagnostics;
using Microsoft.Win32;

/// <summary>
/// LodopUdpBridge 安装程序
/// 用法：
///   LodopUdpBridgeSetup.exe /install   - 安装
///   LodopUdpBridgeSetup.exe /uninstall - 卸载
/// </summary>
class Setup
{
    static string AppName = "LodopUdpBridge";
    static string InstallDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), AppName);
    static string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), AppName);
    static string MenuDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonPrograms), AppName);

    static void Main(string[] args)
    {
        bool uninstall = args.Length > 0 && args[0].ToLower() == "/uninstall";

        if (uninstall)
        {
            Uninstall();
        }
        else
        {
            Install();
        }
    }

    static void Install()
    {
        Console.WriteLine("=== {0} 安装程序 ===", AppName);

        // 检查管理员权限
        if (!IsAdmin())
        {
            Console.WriteLine("需要管理员权限，正在重新启动...");
            RestartAsAdmin("/install");
            return;
        }

        try
        {
            // 创建安装目录
            if (!Directory.Exists(InstallDir)) Directory.CreateDirectory(InstallDir);
            if (!Directory.Exists(DataDir)) Directory.CreateDirectory(DataDir);

            // 复制文件（从 EXE 资源或者从同一目录）
            string exeDir = AppDomain.CurrentDomain.BaseDirectory;
            File.Copy(Path.Combine(exeDir, "LodopUdpBridge.jar"), Path.Combine(InstallDir, "LodopUdpBridge.jar"), true);
            File.Copy(Path.Combine(exeDir, "run.bat"), Path.Combine(InstallDir, "run.bat"), true);

            // 创建开始菜单快捷方式
            if (!Directory.Exists(MenuDir)) Directory.CreateDirectory(MenuDir);
            CreateShortcut(Path.Combine(MenuDir, "启动 " + AppName + ".lnk"), Path.Combine(InstallDir, "run.bat"), InstallDir);

            // 注册卸载信息
            RegisterUninstall();

            Console.WriteLine("安装完成！");
            Console.WriteLine("安装目录: " + InstallDir);
            Console.ReadLine();
        }
        catch (Exception ex)
        {
            Console.WriteLine("安装失败: " + ex.Message);
            Console.ReadLine();
        }
    }

    static void Uninstall()
    {
        Console.WriteLine("=== {0} 卸载程序 ===", AppName);

        if (!IsAdmin())
        {
            RestartAsAdmin("/uninstall");
            return;
        }

        try
        {
            // 结束进程
            foreach (var proc in Process.GetProcessesByName("java"))
            {
                try { proc.Kill(); } catch { }
            }

            // 删除安装目录
            if (Directory.Exists(InstallDir)) Directory.Delete(InstallDir, true);

            // 删除开始菜单
            if (Directory.Exists(MenuDir)) Directory.Delete(MenuDir, true);

            // 删除卸载注册表
            UnregisterUninstall();

            Console.WriteLine("卸载完成！");
            Console.ReadLine();
        }
        catch (Exception ex)
        {
            Console.WriteLine("卸载失败: " + ex.Message);
            Console.ReadLine();
        }
    }

    static void CreateShortcut(string path, string target, string workingDir)
    {
        // 用 WScript.Shell COM 对象创建快捷方式
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        dynamic shell = Activator.CreateInstance(shellType);
        var shortcut = shell.CreateShortcut(path);
        shortcut.TargetPath = target;
        shortcut.WorkingDirectory = workingDir;
        shortcut.Save();
    }

    static void RegisterUninstall()
    {
        string keyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\" + AppName;
        using (var key = Registry.LocalMachine.OpenSubKey(keyPath, true) ?? Registry.LocalMachine.CreateSubKey(keyPath))
        {
            key.SetValue("DisplayName", AppName);
            key.SetValue("UninstallString", Path.Combine(InstallDir, "LodopUdpBridgeSetup.exe") + " /uninstall");
            key.SetValue("InstallLocation", InstallDir);
            key.SetValue("DisplayVersion", "1.0.0");
        }
    }

    static void UnregisterUninstall()
    {
        try
        {
            Registry.LocalMachine.DeleteSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\" + AppName);
        }
        catch { }
    }

    static bool IsAdmin()
    {
        try
        {
            var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var principal = new System.Security.Principal.WindowsPrincipal(identity);
            return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
        }
        catch { return false; }
    }

    static void RestartAsAdmin(string args)
    {
        var proc = new ProcessStartInfo
        {
            FileName = System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName,
            Arguments = args,
            UseShellExecute = true,
            Verb = "runas"
        };
        Process.Start(proc);
        Environment.Exit(0);
    }
}
