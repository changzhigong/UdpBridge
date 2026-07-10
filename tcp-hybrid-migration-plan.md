# 通用网关传输层改造实施计划：UDP 发现 + TCP 数据（混合架构）

> 适用范围：通用网关 `UniversalPrintBridge`（v1.5.x，PC 端 `UniversalPrintBridge.java` + 安卓端 `UdpClient.kt`）
> 目标：保留 UDP 广播做主机/打印机发现，将打印文件上传与预览 PNG 回传改为 TCP 可靠传输。

---

## 1. 方案概述与可行性结论

**结论：可行，且为推荐架构。**

将传输层按职责拆分：
- **控制面（保留 UDP 52010）**：`DISCOVER` 广播发现网关、`LIST` 拉取打印机列表。均为一次性小 JSON 包，丢包可重发，UDP 天然适配，且保住"局域网自动发现"这一核心卖点。
- **数据面（新增 TCP 52011）**：打印文件（Office/PDF/图片）上传、预览多页 PNG 回传。均为大字节流，改用 TCP 后字节流有序可靠，无需分片/重传。

**为什么可行：**
1. 端口不冲突：`DatagramSocket(52010)` 与 `ServerSocket(52011)` 独立并存。
2. 协议语义可复用：`cmd`/`JSON` 头结构、`[4字节大端长度][JSON][二进制]` 帧格式原样平移，仅去掉分片。
3. 删除的复杂度 > 新增的复杂度：当前 `MISSING`/`READY`/`retries` 重传、`Assembly` 重组、`Thread.sleep(2)` 防 UDP 溢出等代码可整体删除，属结构性净减负。
4. 安卓端仍用 `discover()`（UDP）拿到网关 IP，再对 `IP:52011` 建 TCP 连接传数据，客户端改造闭环清晰。

---

## 2. 协议设计

### 2.1 端口
| 协议 | 端口 | 用途 |
|---|---|---|
| UDP | `52010`（常量 `UDP_PORT`） | 发现：`DISCOVER` / `LIST` |
| TCP | `52011`（新增常量 `TCP_PORT`） | 数据：`PRINT` / `PREVIEW` 及其响应 |

### 2.2 TCP 帧格式（复用现有 4 字节前缀，去掉分片）
```
[4 字节大端长度 L][JSON 头, L 字节 UTF-8][二进制载荷, 可选]
```
- TCP 是字节流，靠 4 字节长度前缀定界，**不再分片**，一次性发送完整 buffer。
- JSON 头含 `size` 字段，PC 端按 `size` 精确读取后续二进制，无需 chunk 计数。

### 2.3 命令集简化
| 方向 | 命令 | 说明 | 状态 |
|---|---|---|---|
| 上行 | `PRINT` | 头含 prn/type/copies/paper/orientation/size | 保留（改走 TCP） |
| 上行 | `PREVIEW` | 头含 prn/type/size | 保留（改走 TCP） |
| 下行 | `DONE` / `FAIL` | 打印结果 | 保留（TCP 连接上回写） |
| 下行 | `PREVIEW_READY` | 含 pages/size | 保留（TCP 连接上回写） |
| 下行 | `PREVIEW_CHUNK` | 含 seq/total + 二进制 PNG | 改造：在**同一条 TCP 连接**上顺序写回，不再用 UDP 主动推 |
| — | `CHUNK` / `MISSING` / `READY` | 分片与重传 | **删除**（TCP 不需要） |

---

## 3. 分阶段实施

### 阶段 0：常量与协议骨架（约 0.25 天）
- PC 端 `UniversalPrintBridge.java`：新增 `static final int TCP_PORT = 52011;`
- 安卓端 `UdpClient.kt`：新增 `private const val TCP_PORT = 52011`
- 复用现有 `buildPrefixed(json, chunk)` / `parsePrefixed(buf, len)`（PC 端）与 `readIntBE`（安卓端）做 TCP 帧编解码，无需重写。

### 阶段 1：PC 端 TCP 服务（约 1 天）
**新增：**
- `startTcpServer()`：独立守护线程起 `ServerSocket(TCP_PORT)`，循环 `accept()`。
- `handleTcpClient(Socket s)`：
  - 读 `[4B][JSON]` 头 → `jsonGet` 取 `cmd`/`size`；按 `size` 精确读二进制载荷到 `byte[]`。
  - `PRINT` → `queuePrint(prn, payload, copies, paper, orientation, ...)`（整文件已在内存，无需重组）→ 打印 → `OutputStream.write` 回 `{"status":"DONE"/"FAIL",...}`。
  - `PREVIEW` → 渲染 PNG blob 后，在 `s.getOutputStream()` 上**顺序写回** `PREVIEW_READY` + 各页 `PREVIEW_CHUNK`（替代 `sendPreviewChunks` 的 UDP 推送 + `Thread.sleep(2)`）。
- 设 `s.setSoTimeout(30000)`；异常隔离，单连接故障不影响 `accept` 循环。

**删除：**
- `Assembly` 类及 `assemblies` 重组 Map、`CHUNK` 分支（原 `UniversalPrintBridge.java:939`）、`PREVIEW_MISSING` 重传（`:960`）。
- `sendPreviewChunks()` 中的 UDP `DatagramPacket` 推送与 `Thread.sleep(2)`（`:521`–`532`）。

**保留：**
- UDP 主循环（`:873`–`:985`）的 `DISCOVER` / `LIST` 分支不动。
- `PRINT`/`PREVIEW` 的 UDP 分支建议保留一个版本并打 `deprecated` 日志，兼容尚未升级的旧安卓端。

**代码量：** 新增 ~120 行 / 删除 ~150 行。

### 阶段 2：安卓端 TCP 客户端（约 0.5–1 天）
**新增：**
- `tcpPrint(gatewayIp, type, printer, data, copies, paper, orientation): PrintResult`：
  - `Socket(addr, TCP_PORT)`，`connectTimeout=5000`；
  - 发 `[4B][PRINT头JSON(含 size)]` → 发 `data` 全量 → 读响应 JSON（DONE/FAIL）→ 关连接。
- `tcpPreview(gatewayIp, type, printer, data): List<ByteArray>`：
  - 建连 → 发 `PREVIEW` 头+data → 读 `PREVIEW_READY`（pages/size）→ 流式读 PNG blob，按 `[4B长度][PNG]` 切分每页 → 返回。

**改造：**
- `printRaw()`（`:103`）/ `requestPreview()`（`:197`）内部改为调用 TCP 版；`discover()`（`:50`）/ `listPrinters()`（`:76`）仍走 UDP。

**删除：**
- `sendAllChunks` / `sendOneChunk` / `sendChunks`（`:165`–`191`）、`MISSING` 重发、`retries` 计数、`SocketTimeoutException` 全量重发分支。

**代码量：** 新增 ~120 行 / 删除 ~120 行。

### 阶段 3：并发与健壮性（约 0.5 天）
- **打印串行化**：TCP 处理线程收到 `PRINT` 后投入**单线程打印队列**执行（打印机是独占资源；原 UDP 单线程 `receive()` 隐式串行，改多线程后必须显式加锁/队列，否则多手机并发会抢打印机错乱）。
- **超时**：PC 端 `ServerSocket` accept 不阻塞、每连接 `soTimeout=30000`；安卓端 `Socket.connect(addr, 5000)` + `soTimeout`。
- **网关离线感知**：TCP `connect` 失败立即抛异常，安卓端快速失败，避免原 UDP `soTimeout` 那种长等待。

### 阶段 4：验证与发版（约 0.5 天）
- 按第 5 节验证清单逐项确认。
- 提交代码、打 `v1.6.0` tag 触发 CI，重新打包 APK + Setup，本地安装验证。

---

## 4. 关键风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 并发抢打印机 | 多手机同时打印内容错乱 | 单线程打印队列/锁（阶段 3） |
| 网关离线卡死 | 手机端无响应 | TCP `connect` 5s 超时 + `soTimeout` 快速失败 |
| 防火墙拦截 | TCP 52011 连不上 | 安装包加放行规则；文档提示用户放行局域网端口 |
| 旧安卓端不兼容 | 升级期双端版本错配 | UDP `PRINT`/`PREVIEW` 分支保留一个版本（阶段 1） |
| 大文件内存压力 | 多页高清预览占用高 | 按 `size` 精确 `byte[size]` 接收（沿用现有 `a.buffer` 思路），非必要不翻倍缓冲 |

---

## 5. 验证清单

- [ ] UDP `discover()` 仍能广播发现网关并拿到 IP/打印机列表
- [ ] 大文件打印（>64KB，原 UDP 分片易丢）走 TCP 一次成功
- [ ] 预览多页 PNG 完整回传、无丢片、逐页可显示
- [ ] 网关离线时手机端在 ~5s 内快速失败而非卡死
- [ ] 旧版安卓端（仍发 UDP PRINT）仍可正常打印（若保留 UDP 分支）
- [ ] CI 出包（APK + Setup）本地安装验证通过

---

## 6. 工作量汇总

| 模块 | 新增 | 删除 | 工期 |
|---|---|---|---|
| 阶段 0 骨架 | ~10 行×2 | — | 0.25 天 |
| PC 端 TCP 服务 | ~120 行 | ~150 行 | 1 天 |
| 安卓端 TCP 客户端 | ~120 行 | ~120 行 | 0.5–1 天 |
| 并发与健壮性 | ~60 行 | — | 0.5 天 |
| 验证与发版 | — | — | 0.5 天 |
| **合计** | | | **1.5–2.5 天** |

**净效果**：删除的 UDP 分片/重传复杂度（约 270 行）大于新增的 TCP 代码（约 250 行），整体代码量与维护负担下降，可靠性显著提升。

---

## 7. 与全 TCP / 全 HTTP 方案对比

| 方案 | 自动发现 | 改动量 | 工期 | 评价 |
|---|---|---|---|---|
| 现状（全 UDP） | ✓ 广播 | — | — | 不可靠，大文件易丢片 |
| **混合（UDP 发现 + TCP 数据）** | ✓ 广播 | 中 | **1.5–2.5 天** | **推荐**：保发现、减负、可靠 |
| 全 TCP | ✗ 需另做 | 中 | 1–2 天 | 发现机制断裂 |
| 全 HTTP | ✗ 需 mDNS/手动 | 中偏上 | 2–3 天 | 最标准但改动最大 |

混合方案在"保住自动发现"的前提下，以适中改动获得 TCP 的可靠性，是性价比最优解。
