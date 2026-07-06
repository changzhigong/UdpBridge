package com.fy.printterminal

import android.print.PrintAttributes
import android.print.PrintJob
import android.print.PrintJobId
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import kotlinx.coroutines.*

/**
 * Android 系统级打印服务
 * 注册后，所有 App (WPS/Chrome/微信) 的打印功能都能发现此打印机
 *
 * 启用方式:
 *   设置 → 连接 → 打印 → 添加服务 → 局域网打印网关
 */
class PrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return LodopPrinterDiscoverySession()
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val gw = UdpClient.discover()
                // 获取默认打印机或第一台
                val defaultPrinter = gw.printers.firstOrNull { it.isDefault } ?: gw.printers.firstOrNull()
                if (defaultPrinter == null) {
                    printJob.fail("未发现可用打印机")
                    return@launch
                }
                // 读取打印数据 (Android 系统会将任意文档转为 PDF)
                val inputStream = contentResolver.openInputStream(printJob.document.data)
                val pdfBytes = inputStream?.readBytes() ?: run {
                    printJob.fail("无法读取文档数据")
                    return@launch
                }
                inputStream.close()

                // 发送到 PC 端打印
                val result = UdpClient.printPdf(gw.ip, defaultPrinter.name, pdfBytes)
                if (result.status == "QUEUED" || result.status == "DONE") {
                    printJob.complete()
                } else {
                    printJob.fail(result.msg)
                }
            } catch (e: Exception) {
                printJob.fail("打印异常: ${e.message}")
            }
        }
    }

    // ==================== 打印机发现 ====================
    inner class LodopPrinterDiscoverySession : PrinterDiscoverySession() {

        override fun onStartPrinterDiscovery(maybe: List<PrinterId>) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val gw = UdpClient.discover()
                    val printers = gw.printers.map { p ->
                        val printerId = generatePrinterId("lodop_${p.name}")
                        PrinterInfo.Builder(printerId, p.name, PrinterInfo.STATUS_IDLE).build()
                    }
                    withContext(Dispatchers.Main) {
                        addPrinters(printers)
                    }
                } catch (e: Exception) {
                    // 网关不可达，不添加任何打印机
                }
            }
        }

        override fun onStopPrinterDiscovery() {}

        override fun onValidatePrinters(ids: List<PrinterId>) {}

        override fun onDestroy() {}
    }

    // ==================== PrinterId 工具 ====================
    private var printerIdCounter = 0

    private fun generatePrinterId(localId: String): PrinterId {
        return PrinterId(packageName, localId)
    }
}
