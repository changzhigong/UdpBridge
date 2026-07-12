package com.fy.printterminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * UDP 通信客户端 - 封装与 PC 端 UniversalPrintBridge 的通信
 * 协议: UDP 52010 端口
 */
object UdpClient {

    private const val UDP_PORT = 52010
    private const val TCP_PORT = 52011
    private const val BROADCAST_TIMEOUT = 3000
    private const val DISCOVER_TOTAL_TIMEOUT = 5000   // 发现阶段总超时(多轮重试窗口)
    private const val MAX_BUFFER = 65507
    private const val CHUNK_SIZE = 8000
    private const val PREVIEW_CHUNK_SIZE = 6000
    private const val TCP_CONNECT_TIMEOUT = 5000
    private const val TCP_SO_TIMEOUT = 30000

    /**
     * 网关发现结果
     */
    data class GatewayInfo(
        val hostname: String,
        val ip: String,
        val port: Int,
        val printers: List<PrinterInfo>,
        val version: String = ""   // PC 网关版本号(DISCOVER_ACK 带回, 与安卓端 versionName 对应)
    )

    data class PrinterInfo(
        val name: String,
        val isDefault: Boolean = false
    )

    data class PrintResult(
        val status: String,
        val msg: String,
        val type: String = ""
    )

    /**
     * 广播发现打印网关
     * 改进(Win11 兼容): 先按本机各 IPv4 子网发"定向广播"(如 192.168.1.255),
     * 再回退到受限广播 255.255.255.255; 全程在 DISCOVER_TOTAL_TIMEOUT 内对全部目标多次重试,
     * 任一轮收到 DISCOVER_ACK 即返回。即便 Windows 双栈/虚拟网卡导致 255.255.255.255 不可达,
     * 也能通过定向广播命中网关, 避免安卓侧 poll timed out。
     */
    suspend fun discover(): GatewayInfo = withContext(Dispatchers.IO) {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = BROADCAST_TIMEOUT
        }

        try {
            val targets = collectBroadcastTargets()
            val request = """{"cmd":"DISCOVER"}""".toByteArray(Charsets.UTF_8)
            val buf = ByteArray(MAX_BUFFER)
            val deadline = System.currentTimeMillis() + DISCOVER_TOTAL_TIMEOUT
            var lastErr: Exception? = null

            while (System.currentTimeMillis() < deadline) {
                // 本轮回发所有广播目标(定向广播优先, 255.255.255.255 兜底)
                for (target in targets) {
                    try {
                        socket.send(DatagramPacket(request, request.size, target, UDP_PORT))
                    } catch (e: Exception) {
                        lastErr = e // 个别目标不可达(如 255.255.255.255 被系统限制)不影响其他目标
                    }
                }
                // 等待回应(单轮超时 BROADCAST_TIMEOUT, 超时则进入下一轮重试)
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val info = parseGatewayResponse(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (info.ip != "0.0.0.0") return@withContext info
                } catch (e: SocketTimeoutException) {
                    lastErr = e
                }
            }
            throw SocketTimeoutException("扫描失败: poll timed out").apply { initCause(lastErr) }
        } finally {
            socket.close()
        }
    }

    /**
     * 收集本机所有 IPv4 子网的定向广播地址(如 192.168.1.255), 末尾追加受限广播 255.255.255.255 作兜底。
     * 定向广播比受限广播在 Win11 / 虚拟网卡环境下更可靠(部分 Android 版本还会拦截 255.255.255.255)。
     */
    private fun collectBroadcastTargets(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.interfaceAddresses) {
                    val a = addr.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val bc = addr.broadcast
                        if (bc != null && !list.contains(bc)) list.add(bc)
                    }
                }
            }
        } catch (_: Exception) { /* 枚举失败则用兜底广播 */ }
        // 兜底: 受限广播(部分 Android 版本会拦截, 但大多数仍可发)
        try {
            val limited = InetAddress.getByName("255.255.255.255")
            if (!list.contains(limited)) list.add(limited)
        } catch (_: Exception) { }
        return list
    }

    /**
     * 列出打印机
     */
    suspend fun listPrinters(gatewayIp: String): List<PrinterInfo> = withContext(Dispatchers.IO) {
        sendUdp(gatewayIp, """{"cmd":"LIST"}""").let { response ->
            parsePrinterList(response)
        }
    }

    /**
     * 发送打印任务 (Base64 编码数据, 兼容旧接口)
     * 内部统一走分片 UDP 传输(printRaw), 支持大文件
     */
    suspend fun printBase64(
        gatewayIp: String,
        type: String,
        printer: String,
        data: ByteArray,
        copies: Int = 1,
        paper: String = "",
        orientation: String = "",
        pages: String = "",
        oddEven: String = "all",
        duplex: String = "off"
    ): PrintResult = printRaw(gatewayIp, type, printer, data, copies, paper, orientation, pages, oddEven, duplex)

    /**
     * 发送打印任务 (分片 UDP 传输, 支持大文件)
     * 协议:
     *   1. 发送 PRINT 头(声明 chunks/size/type/printer)
     *   2. 发送多个 CHUNK 包(JSON 头 + 8KB 原始数据)
     *   3. 等待网关 READY / MISSING(重传) / QUEUED|DONE|FAIL
     */
    suspend fun printRaw(
        gatewayIp: String,
        type: String,
        printer: String,
        data: ByteArray,
        copies: Int = 1,
        paper: String = "",
        orientation: String = "",
        pages: String = "",
        oddEven: String = "all",
        duplex: String = "off"
    ): PrintResult = tcpPrint(gatewayIp, type, printer, data, copies, paper, orientation, pages, oddEven, duplex)

    /**
     * 打印文件上传走 TCP(可靠, 不分片). 单连接单命令:
     * 发 [4B 二进制长度][4B JSON长度][JSON][data] -> 收响应 JSON 帧
     */
    private suspend fun tcpPrint(
        gatewayIp: String, type: String, printer: String, data: ByteArray,
        copies: Int, paper: String, orientation: String,
        pages: String = "", oddEven: String = "all", duplex: String = "off"
    ): PrintResult = withContext(Dispatchers.IO) {
        if (data.isEmpty()) return@withContext PrintResult("FAIL", "empty data")
        var socket: Socket? = null
        try {
            val addr = InetAddress.getByName(gatewayIp)
            socket = Socket().apply {
                connect(InetSocketAddress(addr, TCP_PORT), TCP_CONNECT_TIMEOUT)
                soTimeout = TCP_SO_TIMEOUT
            }
            val out = socket.getOutputStream()
            val header = buildString {
                append("{")
                append("\"cmd\":\"PRINT\",")
                append("\"prn\":\"${escapeJson(printer)}\",")
                append("\"type\":\"$type\",")
                append("\"copies\":$copies,")
                append("\"paper\":\"${escapeJson(paper)}\",")
                append("\"orientation\":\"${escapeJson(orientation)}\",")
                append("\"pages\":\"${escapeJson(pages)}\",")
                append("\"oddEven\":\"${escapeJson(oddEven)}\",")
                append("\"duplex\":\"${escapeJson(duplex)}\",")
                append("\"size\":${data.size}")
                append("}")
            }
            writeTcpFrame(out, header.toByteArray(Charsets.UTF_8), data)
            val (jb, _) = readTcpFrame(socket.getInputStream())
            parsePrintResponse(String(jb, Charsets.UTF_8))
        } catch (e: IOException) {
            PrintResult("FAIL", "TCP 打印失败: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 预览走 TCP: 发 PREVIEW 帧+data -> 收 PREVIEW_READY 帧 -> 收 PREVIEW_BLOB 帧(含各页 PNG) -> 切页
     */
    private suspend fun tcpPreview(
        gatewayIp: String, type: String, printer: String, data: ByteArray
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        if (data.isEmpty()) return@withContext emptyList()
        var socket: Socket? = null
        try {
            val addr = InetAddress.getByName(gatewayIp)
            socket = Socket().apply {
                connect(InetSocketAddress(addr, TCP_PORT), TCP_CONNECT_TIMEOUT)
                soTimeout = TCP_SO_TIMEOUT
            }
            val out = socket.getOutputStream()
            val header = buildString {
                append("{")
                append("\"cmd\":\"PREVIEW\",")
                append("\"prn\":\"${escapeJson(printer)}\",")
                append("\"type\":\"$type\",")
                append("\"copies\":1,")
                append("\"size\":${data.size}")
                append("}")
            }
            writeTcpFrame(out, header.toByteArray(Charsets.UTF_8), data)
            val inp = socket.getInputStream()
            // 帧1: PREVIEW_READY
            val (readyJson, _) = readTcpFrame(inp)
            val ready = String(readyJson, Charsets.UTF_8)
            if (ready.contains("PREVIEW_FAIL")) throw IOException("预览失败: " + (jsonGet(ready, "msg") ?: ""))
            // 帧2: PREVIEW_BLOB(各页 PNG)
            val (_, blob) = readTcpFrame(inp)
            if (blob.isEmpty()) return@withContext emptyList()
            val pagesOut = mutableListOf<ByteArray>()
            var off = 0
            while (off + 4 <= blob.size) {
                val len = readIntBE(blob, off)
                off += 4
                if (off + len > blob.size) break
                pagesOut.add(blob.copyOfRange(off, off + len))
                off += len
            }
            pagesOut
        } catch (e: IOException) {
            // 不再吞成空列表, 上抛以便 requestPreview 重试并向用户展示真实错误
            throw e
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // TCP 帧: [4B 二进制长度][4B JSON长度][JSON][二进制]
    private fun writeTcpFrame(out: OutputStream, json: ByteArray, binary: ByteArray?) {
        val bl = binary?.size ?: 0
        val jl = json.size
        out.write((bl ushr 24) and 0xFF)
        out.write((bl ushr 16) and 0xFF)
        out.write((bl ushr 8) and 0xFF)
        out.write(bl and 0xFF)
        out.write((jl ushr 24) and 0xFF)
        out.write((jl ushr 16) and 0xFF)
        out.write((jl ushr 8) and 0xFF)
        out.write(jl and 0xFF)
        out.write(json)
        if (binary != null) out.write(binary)
        out.flush()
    }

    private fun readTcpFrame(inp: InputStream): Pair<ByteArray, ByteArray> {
        val bl = readIntBE(inp)
        val jl = readIntBE(inp)
        if (jl < 0 || jl > 10_000_000) throw IOException("bad json length $jl")
        if (bl < 0 || bl > 200_000_000) throw IOException("bad binary length $bl")
        val jb = ByteArray(jl)
        readFully(inp, jb)
        val bin = if (bl > 0) ByteArray(bl).also { readFully(inp, it) } else ByteArray(0)
        return Pair(jb, bin)
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = inp.read(buf, off, buf.size - off)
            if (r < 0) throw IOException("stream closed")
            off += r
        }
    }

    private fun readIntBE(inp: InputStream): Int {
        val b = ByteArray(4)
        readFully(inp, b)
        return ((b[0].toInt() and 0xFF) shl 24) or
               ((b[1].toInt() and 0xFF) shl 16) or
               ((b[2].toInt() and 0xFF) shl 8) or
               (b[3].toInt() and 0xFF)
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)

    /**
     * 从 Bitmap 打印图片 (一行代码打印)
     */
    suspend fun printBitmap(
        gatewayIp: String,
        printer: String,
        bitmap: Bitmap,
        copies: Int = 1,
        paper: String = "",
        orientation: String = "",
        pages: String = "",
        oddEven: String = "all",
        duplex: String = "off"
    ): PrintResult {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return printBase64(gatewayIp, "IMAGE", printer, stream.toByteArray(), copies, paper, orientation, pages, oddEven, duplex)
    }

    /**
     * 打印 PDF 文件
     */
    suspend fun printPdf(
        gatewayIp: String,
        printer: String,
        pdfBytes: ByteArray,
        copies: Int = 1,
        paper: String = "",
        orientation: String = "",
        pages: String = "",
        oddEven: String = "all",
        duplex: String = "off"
    ): PrintResult {
        return printBase64(gatewayIp, "PDF", printer, pdfBytes, copies, paper, orientation, pages, oddEven, duplex)
    }

    /**
     * 打印文本
     */
    suspend fun printText(
        gatewayIp: String,
        printer: String,
        text: String,
        copies: Int = 1,
        paper: String = "",
        orientation: String = "",
        pages: String = "",
        oddEven: String = "all",
        duplex: String = "off"
    ): PrintResult {
        return printBase64(gatewayIp, "TEXT", printer, text.toByteArray(Charsets.UTF_8), copies, paper, orientation, pages, oddEven, duplex)
    }

    // ---- 底层 UDP 发送 ----
    private fun sendUdp(ip: String, payload: String): String {
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 5000
            val data = payload.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(
                data, data.size,
                InetAddress.getByName(ip), UDP_PORT
            ))
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return String(packet.data, 0, packet.length, Charsets.UTF_8)
        } finally {
            socket.close()
        }
    }

    // ---- JSON 解析 (无外部库依赖) ----
    private fun jsonGet(json: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json) ?: return null
        return m.groupValues[1]
    }

    private fun jsonGetInt(json: String, key: String, default: Int = 0): Int {
        val m = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json) ?: return default
        return m.groupValues[1].toIntOrNull() ?: default
    }

    private fun jsonGetBool(json: String, key: String, default: Boolean = false): Boolean {
        val m = Regex("\"$key\"\\s*:\\s*(true|false)").find(json) ?: return default
        return m.groupValues[1].toBoolean()
    }

    private fun parseGatewayResponse(json: String): GatewayInfo {
        val hostname = jsonGet(json, "hostname") ?: "UNKNOWN"
        val ip = jsonGet(json, "ip") ?: "0.0.0.0"
        val port = jsonGetInt(json, "port", UDP_PORT)
        val version = jsonGet(json, "version") ?: ""
        val printers = parsePrinterList(json)
        return GatewayInfo(hostname, ip, port, printers, version)
    }

    private fun parsePrinterList(json: String): List<PrinterInfo> {
        val printers = mutableListOf<PrinterInfo>()
        // 匹配 "name":"xxx" 对
        val nameRegex = "\"name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val defaultRegex = "\"default\"\\s*:\\s*(true|false)".toRegex()
        // 简单方式: 找到所有打印机关联的 name 和 default
        val nameMatches = nameRegex.findAll(json).toList()
        val defaultMatches = defaultRegex.findAll(json).toList()
        nameMatches.forEachIndexed { i, m ->
            val name = m.groupValues[1]
            val isDefault = if (i < defaultMatches.size) defaultMatches[i].groupValues[1].toBoolean() else false
            printers.add(PrinterInfo(name, isDefault))
        }
        return printers
    }

    private fun parsePrintResponse(json: String): PrintResult {
        val status = jsonGet(json, "status") ?: "FAIL"
        val msg = jsonGet(json, "msg") ?: ""
        val type = jsonGet(json, "type") ?: ""
        return PrintResult(status, msg, type)
    }

    /**
     * 请求预览: 上传文件到 PC 网关, PC 渲染为每页 PNG 并回传(走 TCP 可靠传输)
     * 内置自动重试(最多3次, 间隔600ms): 规避 LibreOffice 首次转换慢/抢锁失败等瞬时问题。
     * 全部失败后抛出真实异常, 由 UI 层展示具体原因(而非笼统"预览为空")。
     * @return 每页 PNG 图片字节列表(顺序)
     */
    suspend fun requestPreview(
        gatewayIp: String,
        type: String,
        printer: String,
        data: ByteArray
    ): List<ByteArray> {
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try {
                val pages = tcpPreview(gatewayIp, type, printer, data)
                if (pages.isNotEmpty()) return pages
            } catch (e: Exception) {
                lastErr = e
            }
            if (attempt < 2) kotlinx.coroutines.delay(600)
        }
        throw lastErr ?: IOException("预览为空")
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
