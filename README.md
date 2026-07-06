# LodopUdpBridge

局域网打印网关 —— Android App 通过 UDP 发现并下发打印任务，桥接 C-Lodop WebSocket 协议驱动针式打印机。

## 架构

```
App（移动端）→ UDP(51010) → Java Bridge → WebSocket(CLODOP) → C-Lodop → 针式打印机
```

- **UDP 端口**：`51010`（局域网广播发现 + 打印任务下发）
- **C-Lodop WS**：`ws://127.0.0.1:8000/c_webskt/`
- **运行方式**：纯后台 + 系统托盘图标，无浏览器、无 HTTP 服务器
- **日志路径**：`%APPDATA%/LodopUdpBridge/bridge.log`

## 技术栈

- **语言**：Java 11+（使用 `java.net.http.WebSocket`，需 JDK 不是 JRE）
- **JRE 版本**：Java 17.0.13（7 个 modules：`java.base java.datatransfer java.xml java.prefs java.desktop java.logging java.net.http`）
- **无外部依赖**：JSON 用正则解析，不依赖任何第三方库
- **打包**：Inno Setup 6（含内嵌 JRE 的单文件安装包）
- **启动器**：C 编译的 `launch.exe`（无控制台窗口，CreateProcess 启动 javaw）

## 目录结构

```
LodopUdpBridge/
├── LodopUdpBridge.java          # 主源码（UDP 服务 + CLodop WS 客户端 + 系统托盘）
├── LodopUdpBridge.java.bak      # 源码备份
├── run_lodop_bridge.bat         # 运行脚本（GBK 编码）
├── run_lodop_bridge1.bat        # 备用运行脚本
├── installer/                   # 定型打包方案（v1.2）
│   ├── LodopUdpBridge.iss       # Inno Setup 脚本
│   ├── launch.c                 # C 启动器源码
│   ├── launch.exe               # C 启动器二进制（CI 复用，不重编译）
│   ├── launch.vbs               # VBS 静默启动包装
│   ├── run.bat                  # 控制台调试运行脚本
│   └── jre/                     # 内嵌 JRE（不入库，CI 用 jlink 生成）
├── dist-bridge/                 # 旧版打包试验场（v1.0.0，保留参考）
│   ├── LodopUdpBridge.iss       # Inno Setup 脚本（含 [Code] 升级逻辑参考）
│   ├── LodopUdpBridge_NSIS.nsi  # NSIS 脚本
│   ├── LodopUdpBridge*.wxs      # WiX 脚本
│   └── ...                      # 各种打包方案脚本
├── FILE_MAP.md                  # 完整文件清单
└── .github/workflows/release.yml # GitHub Actions 自动发版
```

## 本地编译

### 前置要求

- JDK 17+（不是 JRE）
- Inno Setup 6（`C:\Program Files (x86)\Inno Setup 6\ISCC.exe`）

### 编译步骤

```cmd
# 1. 编译 Java 源码
cd installer
javac --release 17 -encoding UTF-8 -d . ..\LodopUdpBridge.java

# 2. 打包 JAR
jar cfe LodopUdpBridge.jar LodopUdpBridge LodopUdpBridge.class LodopUdpBridge$1.class LodopUdpBridge$2.class LodopUdpBridge$3.class

# 3. 生成 JRE（jlink）
jlink --add-modules java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.logging,java.net.http --output jre --strip-debug --no-header-files --no-man-pages --compress=2

# 4. 编译安装包
"C:\Program Files (x86)\Inno Setup 6\ISCC.exe" LodopUdpBridge.iss

# 产物在 installer-output\LodopUdpBridge_Setup.exe
```

## 自动发版（GitHub Actions）

### 触发方式

推送 `v*` 格式的 tag 即自动构建并发版：

```bash
git tag v1.2.0
git push origin v1.2.0
```

### CI 构建流程

1. checkout 代码
2. 安装 JDK 17（Temurin）
3. `javac` 编译 Java 源码 → `jar` 打包
4. `jlink` 生成内嵌 JRE
5. `ISCC.exe` 编译 Inno Setup 安装包
6. 创建 GitHub Release，上传 `LodopUdpBridge_Setup.exe`

### 手动触发

在 GitHub 仓库 Actions 页面选择 "Release" 工作流，点击 "Run workflow" 即可手动构建（不发版，仅产出 artifact）。

## 关键技术点

| 要点 | 说明 |
|------|------|
| CLodop 协议分隔符 | `\f\f`（与 CLodopfuncs.js `DelimChar` 对齐） |
| TaskID 格式 | `JAVA_HHMMSS_N` |
| 打印任务下发 | 每条 HTML 单独一个 WS 消息，避免 CLodop 缓冲区问题 |
| 打印机枚举 | 优先从 CLodop WS 推送解析，fallback 到 `javax.print` |
| JSON 解析 | 纯正则，无外部依赖（`extractJsonLongField` 手动解析转义） |
| .bat 编码 | GBK（含中文注释，勿转 UTF-8） |
| iss 编码 | UTF-8 无 BOM（Inno Setup 6.7.1 正确识别） |

## 安装包功能

- 安装到 `C:\Program Files\LodopUdpBridge`
- 内嵌 JRE，无需用户安装 Java
- 系统托盘后台运行
- 可选：桌面快捷方式、开机自启动
- 升级时自动检测旧版并询问卸载
- 卸载时询问是否删除配置数据和日志
