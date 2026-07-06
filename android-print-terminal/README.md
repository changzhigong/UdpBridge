# Android 打印终端

基于 UniversalPrintBridge 的安卓端打印 App，支持：

- **一行代码打印**: `PrintBridge.printImage(context, "打印机名", bitmap)`
- **网关发现**: UDP 广播自动发现局域网 PC 打印网关
- **打印机列表**: 从 PC 端获取 Windows 所有打印机
- **文件选择**: 系统文件选择器，支持 PDF/PNG/JPEG/TXT
- **打印预览**: 安卓端本地预览（图片渲染、PDF 页数统计、文本展示）
- **系统打印服务**: 注册为 Android 系统打印服务，WPS/Chrome 等所有 App 可用

## 架构

```
Android App → UDP(52010) → UniversalPrintBridge(PC) → Windows 打印机
```

## 集成到现有项目

拷贝以下文件到你的 Android 项目:

1. `UdpClient.kt` — UDP 通信封装
2. `PrintBridge.kt` — 一行打印 API
3. `MainActivity.kt` — 终端界面 (可选)
4. `PrintService.kt` — 系统打印服务 (可选)

然后在 AndroidManifest.xml 添加网络权限:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## 使用示例

```kotlin
// 打印图片 (一行)
PrintBridge.printImage(this, "EPSON LQ-630K", bitmap)

// 打印 PDF
PrintBridge.printPdf(this, "EPSON LQ-630K", pdfBytes)

// 打印文本
PrintBridge.printText(this, "EPSON LQ-630K", "订单号: 123456")

// 同步打印 + 获取结果
val result = PrintBridge.printImageSync(context, printer, bitmap)
if (result.status == "QUEUED") { /* 成功 */ }
```

## 构建

用 Android Studio 打开 `android-print-terminal/` 目录，Sync Gradle，Build APK。

或命令行:

```bash
cd android-print-terminal
./gradlew assembleDebug
```
