package com.fy.printterminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64

/**
 * UDP 通信客户端 - 封装与 PC 端 UniversalPrintBridge 的通信
 * 协议: UDP 52010 端口
 */
object UdpClient {

    private const val UDP_PORT = 52010
    private const val BROADCAST_TIMEOUT = 3000
    private const val MAX_BUFFER = 65507

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
     * 发送打印任务 (Base64 编码数据)
     * @param gatewayIp 网关 IP
     * @param type 打印类型 PDF/IMAGE/TEXT
     * @param printer 打印机名称
     * @param data 原始数据字节
     * @param copies 份数
     */
    suspend fun printBase64(
        gatewayIp: String,
        type: String,
        printer: String,
        data: ByteArray,
        copies: Int = 1
    ): PrintResult = withContext(Dispatchers.IO) {
        val base64 = Base64.getEncoder().encodeToString(data)
        val json = buildString {
            append("{")
            append("\"cmd\":\"PRINT\",")
            append("\"type\":\"$type\",")
            append("\"printer\":\"${escapeJson(printer)}\",")
            append("\"copies\":$copies,")
            append("\"data\":\"$base64\"")
            append("}")
        }
        parsePrintResponse(sendUdp(gatewayIp, json))
    }

    /**
     * 从 Bitmap 打印图片 (一行代码打印)
     */
    suspend fun printBitmap(
        gatewayIp: String,
        printer: String,
        bitmap: Bitmap,
        copies: Int = 1
    ): PrintResult {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return printBase64(gatewayIp, "IMAGE", printer, stream.toByteArray(), copies)
    }

    /**
     * 打印 PDF 文件
     */
    suspend fun printPdf(
        gatewayIp: String,
        printer: String,
        pdfBytes: ByteArray,
        copies: Int = 1
    ): PrintResult {
        return printBase64(gatewayIp, "PDF", printer, pdfBytes, copies)
    }

    /**
     * 打印文本
     */
    suspend fun printText(
        gatewayIp: String,
        printer: String,
        text: String,
        copies: Int = 1
    ): PrintResult {
        return printBase64(gatewayIp, "TEXT", printer, text.toByteArray(Charsets.UTF_8), copies)
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
