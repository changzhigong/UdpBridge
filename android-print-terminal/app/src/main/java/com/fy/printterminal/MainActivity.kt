package com.fy.printterminal

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

/**
 * 打印终端 - 主界面
 *
 * 功能:
 * 1. 扫描发现局域网打印网关
 * 2. 列出可用打印机
 * 3. 选择文件(PDF/图片)并预览
 * 4. 发送打印任务到 PC 端
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvGatewayStatus: TextView
    private lateinit var rvPrinters: RecyclerView
    private lateinit var tvStatus: TextView

    private var printerList = mutableListOf<UdpClient.PrinterInfo>()
    private var selectedPrinter: String? = null
    private var gatewayIp: String? = null
    private var currentFileUri: Uri? = null
    private var currentFileBytes: ByteArray? = null
    private var currentFileType: String? = null

    private val printerAdapter = PrinterAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvGatewayStatus = findViewById(R.id.tvGatewayStatus)
        rvPrinters = findViewById(R.id.rvPrinters)
        tvStatus = findViewById(R.id.tvStatus)

        rvPrinters.layoutManager = LinearLayoutManager(this)
        rvPrinters.adapter = printerAdapter

        findViewById<View>(R.id.btnScan).setOnClickListener { scanGateway() }
        findViewById<View>(R.id.btnBrowseFile).setOnClickListener { browseFile() }
        findViewById<View>(R.id.btnPreview).setOnClickListener { previewFile() }
        findViewById<View>(R.id.btnPrint).setOnClickListener { sendPrint() }

        // 启动时自动扫描
        scanGateway()
    }

    // ==================== 扫描网关 ====================
    private fun scanGateway() {
        tvGatewayStatus.text = "正在扫描..."
        tvStatus.text = ""
        setButtonsEnabled(false)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val gw = UdpClient.discover()
                withContext(Dispatchers.Main) {
                    gatewayIp = gw.ip
                    tvGatewayStatus.text = "已连接: ${gw.hostname} (${gw.ip})"
                    printerList.clear()
                    printerList.addAll(gw.printers)
                    printerAdapter.notifyDataSetChanged()
                    if (printerList.isNotEmpty()) {
                        selectPrinter(0)
                    }
                    Toast.makeText(this@MainActivity,
                        "发现 ${gw.printers.size} 台打印机", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvGatewayStatus.text = "未发现打印网关"
                    gatewayIp = null
                    Toast.makeText(this@MainActivity,
                        "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                }
            }
        }
    }

    // ==================== 选择文件 ====================
    private fun browseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf",
                "image/png",
                "image/jpeg",
                "image/bmp",
                "text/plain"
            ))
        }
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                currentFileUri = uri
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        currentFileBytes = stream.readBytes()
                        currentFileType = detectFileType(currentFileBytes!!)
                        tvStatus.text = "已选择文件 (${currentFileType}, ${currentFileBytes!!.size / 1024}KB)"
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "文件读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==================== 自动检测文件类型 ====================
    private fun detectFileType(data: ByteArray): String {
        if (data.size >= 4) {
            if (data[0] == 0x25.toByte() && data[1] == 0x50.toByte()
                && data[2] == 0x44.toByte() && data[3] == 0x46.toByte())
                return "PDF"
            if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte()
                && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte())
                return "IMAGE"
            if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte())
                return "IMAGE"
        }
        // 尝试作为文本
        return try {
            val text = String(data, 0, minOf(1024, data.size), Charsets.UTF_8)
            if (text.all { it.isISOControl() || it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?()[]{}-_+=@#$%^&*<>|/\\\"'" })
                "TEXT"
            else
                "BINARY"
        } catch (e: Exception) {
            "BINARY"
        }
    }

    // ==================== 预览文件 ====================
    private fun previewFile() {
        val bytes = currentFileBytes ?: run {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        val type = currentFileType ?: "UNKNOWN"
        val printer = selectedPrinter ?: ""

        val intent = Intent(this, PrintPreviewActivity::class.java).apply {
            putExtra("file_type", type)
            putExtra("printer", printer)
        }
        // 图片数据通过临时缓存传递; PDF/TEXT 也类似
        PreviewCache.data = bytes
        PreviewCache.type = type
        startActivity(intent)
    }

    // ==================== 发送打印 ====================
    private fun sendPrint() {
        val ip = gatewayIp ?: run {
            Toast.makeText(this, "请先扫描网关", Toast.LENGTH_SHORT).show()
            return
        }
        val printer = selectedPrinter ?: run {
            Toast.makeText(this, "请先选择打印机", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = currentFileBytes ?: run {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show()
            return
        }
        val type = currentFileType ?: "TEXT"

        tvStatus.text = "正在发送打印任务..."
        setButtonsEnabled(false)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = when (type) {
                    "PDF" -> UdpClient.printPdf(ip, printer, bytes)
                    "IMAGE" -> UdpClient.printBitmap(ip, printer,
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    "TEXT" -> UdpClient.printText(ip, printer, String(bytes, Charsets.UTF_8))
                    else -> UdpClient.printBase64(ip, type, printer, bytes)
                }
                withContext(Dispatchers.Main) {
                    if (result.status == "QUEUED" || result.status == "DONE") {
                        tvStatus.text = "打印任务已提交 (${result.type})"
                        Toast.makeText(this@MainActivity, "打印成功", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "打印失败: ${result.msg}"
                        Toast.makeText(this@MainActivity,
                            "打印失败: ${result.msg}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "发送失败: ${e.message}"
                    Toast.makeText(this@MainActivity,
                        "发送失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                }
            }
        }
    }

    // ==================== UI 辅助 ====================
    private fun selectPrinter(index: Int) {
        selectedPrinter = printerList[index].name
        printerAdapter.notifyDataSetChanged()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(findViewById<View>(R.id.btnBrowseFile)!!,
            findViewById<View>(R.id.btnPreview)!!,
            findViewById<View>(R.id.btnPrint)!!).forEach {
            it.isEnabled = enabled
        }
    }

    // ==================== 打印机列表 Adapter ====================
    inner class PrinterAdapter :
        RecyclerView.Adapter<PrinterAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_single_choice, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = printerList[position]
            val label = if (p.isDefault) "${p.name} (默认)" else p.name
            holder.tvName.text = label
            // simple_list_item_single_choice 的选中态由内部 CheckedTextView 决定
            if (holder.tvName is android.widget.CheckedTextView) {
                (holder.tvName as android.widget.CheckedTextView).isChecked = (p.name == selectedPrinter)
            }
            holder.itemView.isActivated = (p.name == selectedPrinter)
            holder.itemView.setOnClickListener {
                selectPrinter(position)
            }
        }

        override fun getItemCount() = printerList.size
    }

    companion object {
        private const val REQUEST_PICK_FILE = 1001
    }
}

/**
 * 预览数据缓存 (跨 Activity)
 */
object PreviewCache {
    var data: ByteArray? = null
    var type: String = ""
}
