package com.fy.printterminal

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.*

/**
 * 一行代码打印 API
 *
 * 使用示例:
 *   PrintBridge.printImage(context, "EPSON LQ-630K", bitmap)
 *   PrintBridge.printPdf(context, "EPSON LQ-630K", pdfBytes)
 *   PrintBridge.printText(context, "EPSON LQ-630K", "订单号: 123456")
 */
object PrintBridge {

    /**
     * 打印图片 (Bitmap → Base64 → UDP)
     */
    fun printImage(context: Context, printer: String, bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val gw = UdpClient.discover()
                UdpClient.printBitmap(gw.ip, printer, bitmap)
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    /**
     * 打印 PDF 文件
     */
    fun printPdf(context: Context, printer: String, pdfBytes: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val gw = UdpClient.discover()
                UdpClient.printPdf(gw.ip, printer, pdfBytes)
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    /**
     * 打印纯文本
     */
    fun printText(context: Context, printer: String, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val gw = UdpClient.discover()
                UdpClient.printText(gw.ip, printer, text)
            }.onFailure {
                it.printStackTrace()
            }
        }
    }

    /**
     * 同步打印 + 返回结果
     * @return PrintResult(status, msg, type)
     */
    suspend fun printImageSync(
        context: Context,
        printer: String,
        bitmap: Bitmap,
        timeoutMs: Long = 15000
    ): UdpClient.PrintResult = withTimeout(timeoutMs) {
        val gw = UdpClient.discover()
        UdpClient.printBitmap(gw.ip, printer, bitmap)
    }

    suspend fun printPdfSync(
        context: Context,
        printer: String,
        pdfBytes: ByteArray,
        timeoutMs: Long = 15000
    ): UdpClient.PrintResult = withTimeout(timeoutMs) {
        val gw = UdpClient.discover()
        UdpClient.printPdf(gw.ip, printer, pdfBytes)
    }
}
