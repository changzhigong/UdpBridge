package com.fy.printterminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UDP 通信客户端 - 封装与 PC 端 UniversalPrintBridge 的通信
 * 协议: UDP 52010 端口
 */
object UdpClient {

    private const val UDP_PORT = 52010
    private const val BROADCAST_TIMEOUT = 3000
    private const val MAX_BUFFER = 65507
    private const val CHUNK_SIZE = 8000
    private const val PREVIEW_CHUNK_SIZE = 6000

    /**
     * 网关发现结果
     */
    data class GatewayInfo(
        val hostname: String,
        val ip: String,
        val port: Int,
        val printers: List<PrinterInfo>
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
     */
    suspend fun discover(): GatewayInfo = withContext(Dispatchers.IO) {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = BROADCAST_TIMEOUT
        }

        try {
            val request = """{"cmd":"DISCOVER"}""".toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(
                request, request.size,
                InetAddress.getByName("255.255.255.255"), UDP_PORT
            ))

            val buf = ByteArray(MAX_BUFFER)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)

            parseGatewayResponse(String(packet.data, 0, packet.length))
        } finally {
            socket.close()
        }
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
        orientation: String = ""
    ): PrintResult = printRaw(gatewayIp, type, printer, data, copies, paper, orientation)

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
        orientation: String = ""
    ): PrintResult = withContext(Dispatchers.IO) {
        if (data.isEmpty()) return@withContext PrintResult("FAIL", "empty data")

        val socket = DatagramSocket().apply { soTimeout = 2000 }
        try {
            val addr = InetAddress.getByName(gatewayIp)
            val total = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            val header = buildString {
                append("{")
                append("\"cmd\":\"PRINT\",")
                append("\"prn\":\"${escapeJson(printer)}\",")
                append("\"type\":\"$type\",")
                append("\"copies\":$copies,")
                append("\"paper\":\"${escapeJson(paper)}\",")
                append("\"orientation\":\"${escapeJson(orientation)}\",")
                append("\"chunks\":$total,")
                append("\"size\":${data.size}")
                append("}")
            }
            socket.send(DatagramPacket(header.toByteArray(Charsets.UTF_8), header.length, addr, UDP_PORT))

            val buf = ByteArray(4096)
            var result: PrintResult? = null
            var retries = 0
            sendAllChunks(socket, addr, data, total)
            while (retries < 10) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val resp = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    when (val status = jsonGet(resp, "status")) {
                        "READY" -> { sendAllChunks(socket, addr, data, total); retries++ }
                        "MISSING" -> {
                            val seqs = jsonGet(resp, "seqs")?.split(",")
                                ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                            sendChunks(socket, addr, data, seqs)
                            retries++
                        }
                        "QUEUED", "DONE" -> { result = parsePrintResponse(resp); break }
                        "FAIL" -> { result = parsePrintResponse(resp); break }
                        else -> retries++
                    }
                } catch (e: SocketTimeoutException) {
                    sendAllChunks(socket, addr, data, total)
                    retries++
                }
            }
            result ?: PrintResult("FAIL", "no response from gateway")
        } finally {
            socket.close()
        }
    }

    private fun sendAllChunks(socket: DatagramSocket, addr: InetAddress, data: ByteArray, total: Int) {
        for (seq in 0 until total) sendOneChunk(socket, addr, data, seq, total)
    }

    private fun sendChunks(socket: DatagramSocket, addr: InetAddress, data: ByteArray, seqs: List<Int>) {
        val total = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        for (seq in seqs) sendOneChunk(socket, addr, data, seq, total)
    }

    private fun sendOneChunk(socket: DatagramSocket, addr: InetAddress, data: ByteArray, seq: Int, total: Int) {
        val start = seq * CHUNK_SIZE
        if (start >= data.size) return
        val end = minOf(start + CHUNK_SIZE, data.size)
        val chunk = data.copyOfRange(start, end)
        val json = """{"cmd":"CHUNK","seq":$seq,"total":$total}"""
        val jb = json.toByteArray(Charsets.UTF_8)
        val jl = jb.size
        // 长度前缀帧: [4字节大端JSON长度][JSON][二进制]
        val pkt = ByteArray(4 + jl + chunk.size)
        pkt[0] = ((jl ushr 24) and 0xFF).toByte()
        pkt[1] = ((jl ushr 16) and 0xFF).toByte()
        pkt[2] = ((jl ushr 8) and 0xFF).toByte()
        pkt[3] = (jl and 0xFF).toByte()
        System.arraycopy(jb, 0, pkt, 4, jl)
        System.arraycopy(chunk, 0, pkt, 4 + jl, chunk.size)
        socket.send(DatagramPacket(pkt, pkt.size, addr, UDP_PORT))
    }

    /**
     * 请求预览: 上传文件到 PC 网关, PC 将内容渲染为每页 PNG 并分片回传
     * @return 每页 PNG 图片字节列表(顺序)
     */
    suspend fun requestPreview(
        gatewayIp: String,
        type: String,
        printer: String,
        data: ByteArray
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        if (data.isEmpty()) return@withContext emptyList()

        val socket = DatagramSocket().apply { soTimeout = 3000 }
        try {
            val addr = InetAddress.getByName(gatewayIp)
            val total = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            val header = buildString {
                append("{")
                append("\"cmd\":\"PREVIEW\",")
                append("\"prn\":\"${escapeJson(printer)}\",")
                append("\"type\":\"$type\",")
                append("\"copies\":1,")
                append("\"chunks\":$total,")
                append("\"size\":${data.size}")
                append("}")
            }
            socket.send(DatagramPacket(header.toByteArray(Charsets.UTF_8), header.length, addr, UDP_PORT))
            sendAllChunks(socket, addr, data, total)

            val buf = ByteArray(MAX_BUFFER)
            var pages = 0
            var blobSize = 0
            var ptotal = 0
            val chunks = mutableMapOf<Int, ByteArray>()
            var gotReady = false
            var retries = 0
            while (retries < 12) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val resp = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    when {
                        resp.contains("PREVIEW_READY") -> {
                            pages = jsonGetInt(resp, "pages")
                            blobSize = jsonGetInt(resp, "size")
                            ptotal = (blobSize + PREVIEW_CHUNK_SIZE - 1) / PREVIEW_CHUNK_SIZE
                            if (ptotal == 0) ptotal = 1
                            gotReady = true
                        }
                        resp.contains("PREVIEW_CHUNK") -> {
                            val seq = jsonGetInt(resp, "seq")
                            val jsonLen = readIntBE(packet.data, 0)
                            val jsonEnd = 4 + jsonLen
                            val bin = packet.data.copyOfRange(jsonEnd, packet.length)
                            chunks[seq] = bin
                        }
                        resp.contains("PREVIEW_FAIL") ->
                            throw IOException("预览失败: " + (jsonGet(resp, "msg") ?: ""))
                        resp.contains("READY") -> { sendAllChunks(socket, addr, data, total); retries++ }
                        resp.contains("MISSING") -> {
                            val seqs = jsonGet(resp, "seqs")?.split(",")
                                ?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                            sendChunks(socket, addr, data, seqs)
                            retries++
                        }
                    }
                    if (gotReady && chunks.size >= ptotal) break
                } catch (e: SocketTimeoutException) {
                    if (gotReady) {
                        val missing = (0 until ptotal).filter { !chunks.containsKey(it) }
                        if (missing.isEmpty()) break
                        val req = """{"cmd":"PREVIEW_MISSING","seqs":"${missing.joinToString(",")}"}"""
                        socket.send(DatagramPacket(req.toByteArray(Charsets.UTF_8), req.length, addr, UDP_PORT))
                        retries++
                    } else {
                        sendAllChunks(socket, addr, data, total)
                        retries++
                    }
                }
            }
            if (!gotReady) return@withContext emptyList()

            // 重组 blob
            val blob = ByteArrayOutputStream()
            for (i in 0 until ptotal) {
                val c = chunks[i] ?: return@withContext emptyList()
                blob.write(c)
            }
            val blobBytes = blob.toByteArray()
            // 解析每页: [4字节大端长度][PNG]
            val pagesOut = mutableListOf<ByteArray>()
            var off = 0
            while (off + 4 <= blobBytes.size) {
                val len = readIntBE(blobBytes, off)
                off += 4
                if (off + len > blobBytes.size) break
                pagesOut.add(blobBytes.copyOfRange(off, off + len))
                off += len
            }
            pagesOut
        } finally {
            socket.close()
        }
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
        orientation: String = ""
    ): PrintResult {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return printBase64(gatewayIp, "IMAGE", printer, stream.toByteArray(), copies, paper, orientation)
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
        orientation: String = ""
    ): PrintResult {
        return printBase64(gatewayIp, "PDF", printer, pdfBytes, copies, paper, orientation)
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
        orientation: String = ""
    ): PrintResult {
        return printBase64(gatewayIp, "TEXT", printer, text.toByteArray(Charsets.UTF_8), copies, paper, orientation)
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
        val printers = parsePrinterList(json)
        return GatewayInfo(hostname, ip, port, printers)
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

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
