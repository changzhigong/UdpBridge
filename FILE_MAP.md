# LodopUdpBridge —— PC 端文件清单

> 提取自 Claw 工作空间（Android App 仓库），仅保留 PC 端 Java 打印桥相关文件。
> 提取时间：2026-07-06
> 用途：局域网打印网关，App 通过 UDP 发现并下发打印任务，桥接 C-Lodop WebSocket 协议驱动针式打印机。

---

## 架构

```
App（移动端）→ UDP(DISCOVER/PRINT) → Java Bridge → WebSocket(CLODOP协议) → C-Lodop → 针式打印机
```

- UDP 端口：`51010`
- C-Lodop WS：`ws://127.0.0.1:8000/c_webskt/`
- 纯后台运行 + 系统托盘图标，无浏览器、无 HTTP 服务器
- 日志路径：`%APPDATA%/LodopUdpBridge/bridge.log`

---

## 根目录（核心源码与运行脚本）

| 文件 | 用途 |
|------|------|
| `LodopUdpBridge.java` | 主源码（896 行），UDP 服务 + CLodop WS 客户端 + 系统托盘 |
| `LodopUdpBridge.java.bak` | 源码备份（2026-06-25） |
| `LodopUdpBridge.class` | 编译后主类（JDK 11+，使用 java.net.http.WebSocket） |
| `LodopUdpBridge$1.class` `$2.class` `$3.class` | 内部类（WS Listener 等） |
| `LodopUdpBridge.jar` | 可执行 JAR 包（17.7 KB，无依赖） |
| `run_lodop_bridge.bat` | 主运行脚本（GBK 编码，含 Java 版本检查） |
| `run_lodop_bridge1.bat` | 备用运行脚本（精简版） |

**编译运行**：需要 JDK 11+（不是 JRE），因为依赖 `java.net.http.WebSocket`。

```cmd
javac LodopUdpBridge.java
java LodopUdpBridge
```

---

## dist-bridge/（打包工作目录，91 MB）

存放多种打包方案的中间产物和最终安装包。**含 4 种打包方案并存**，主用 Inno Setup。

### 主安装包（最终交付物）
- `LodopUdpBridge_Setup.exe` —— **最终安装包（33.9 MB，含内嵌 JRE）**
- `innosetup.exe` —— Inno Setup 编译器本身（10.6 MB）

### Inno Setup 方案（主用）
- `LodopUdpBridge.iss` —— Inno Setup 脚本
- `install.bat` / `uninstall.bat` —— 安装/卸载批处理
- `run.bat` / `run_silent.bat` / `run.vbs` —— 静默运行脚本

### NSIS 方案
- `LodopUdpBridge_NSIS.nsi` —— NSIS 脚本

### WiX 方案（MSI）
- `LodopUdpBridge.wxs` `.wixobj` `.wixpdb` `.wixproj` —— WiX 完整工程
- `LodopUdpBridge-minimal.wxs` `.wixobj` `.wixpdb` —— WiX 精简版
- `Setup.cs` —— C# 自定义 Action

### jpackage 方案
- `package-with-jpackage.bat` —— jpackage 打包脚本
- `create-runtime.bat` —— 创建自定义 JRE
- `runtime/` —— jpackage 用的 JRE 运行时

### JAR 打包辅助
- `manifest.txt` —— JAR Manifest
- `META-INF/` —— JAR 元数据
- `json.jar` —— 备用 JSON 库（实际代码用正则解析，未依赖）

### PowerShell 脚本
- `LodopUdpBridge_Install.ps1` —— PowerShell 安装脚本

### 历史 class 产物（2026-06-25 旧版）
- `LodopUdpBridge.class` `$1.class` `$2.class` `$3.class` `.jar` —— 旧版编译产物（与根目录的新版不同，根目录是 06-28 重编译的）

---

## installer/（启动器方案，80 MB）

精简版打包方案，使用 C 语言启动器 + 内嵌 JRE。

| 文件/目录 | 用途 |
|-----------|------|
| `launch.c` | C 启动器源码（1.7 KB） |
| `launch.exe` | 编译后的启动器（95 KB） |
| `launch.vbs` | VBS 静默启动包装 |
| `run.bat` | 简单运行脚本 |
| `LodopUdpBridge.iss` | Inno Setup 脚本（启动器版本，1.7 KB） |
| `LodopUdpBridge.jar` | JAR 包（17.7 KB，与 dist-bridge 旧版同源） |
| `jre/` | 内嵌 JRE 运行时 |
| `installer-output/` | 安装包输出目录 |

---

## 关键技术点（源码摘要）

1. **CLodop 协议对齐**：使用 `\f\f` 作为字段分隔符，与 CLodopfuncs.js 第 9 行 `DelimChar` 一致
2. **TaskID 格式**：`JAVA_HHMMSS_N`，对齐 CLodop `GetTaskID`
3. **逐条发送**：每条 HTML 单独一个 WS 消息，避免 CLodop 缓冲区问题
4. **打印机枚举**：从 CLodop WS 推送的 JS 库中正则提取 `Printers` 对象，fallback 到 `javax.print`
5. **响应解析**：解析 `TaskID=true/false`，返回 `PRINT_ACK` 闭环
6. **长 HTML 解析**：`extractJsonLongField` 手动解析 JSON 转义字符（避免外部 JSON 库依赖）

---

## 注意事项

- `.bat` 文件用 **GBK 编码**保存（中文注释），不要用 UTF-8 重新保存，否则会出现乱码
- 编译需要 **JDK 11+**（不是 JRE），因为用到 `java.net.http.WebSocket`
- 内嵌 JRE 版本：见 `dist-bridge/runtime/` 和 `installer/jre/`
- 此工作空间只含 PC 端代码，**移动端 App 代码仍在 Claw 工作空间**
