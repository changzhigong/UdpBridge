package com.fy.printterminal

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 打印预览界面 (安卓端)
 * 通过 UDP 请求 PC 网关: PC 将内容渲染为每页 PNG 并分片回传, 本界面逐页显示。
 * 支持 PDF / Word / Excel / PPT / 图片 / 纯文本。
 * "确认打印" 按钮直接把原始文件发往 PC 网关打印。
 */
class PrintPreviewActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val type = intent.getStringExtra("file_type") ?: PreviewCache.type
        val printer = intent.getStringExtra("printer") ?: PreviewCache.printer
        val gatewayIp = intent.getStringExtra("gateway_ip") ?: PreviewCache.gatewayIp
        val bytes = PreviewCache.data

        val tvTitle = findViewById<TextView>(R.id.tvPreviewTitle)
        val container = findViewById<LinearLayout>(R.id.previewContainer)
        val btnPrintNow = findViewById<Button>(R.id.btnPrintNow)

        tvTitle.text = "打印预览 ($type) → $printer"

        if (bytes == null || bytes.isEmpty()) {
            container.addView(TextView(this).apply { text = "无预览数据，请重新选择文件" })
            btnPrintNow.isEnabled = false
            return
        }
        if (gatewayIp.isEmpty()) {
            container.addView(TextView(this).apply { text = "未连接到打印网关，无法预览/打印" })
            btnPrintNow.isEnabled = false
            return
        }

        // 加载占位
        val loading = TextView(this).apply {
            text = "正在生成预览，请稍候…"
            setPadding(16, 16, 16, 16)
        }
        container.addView(loading)

        // 确认打印按钮: 真正发送打印
        btnPrintNow.setOnClickListener {
            it.isEnabled = false
            btnPrintNow.text = "打印中…"
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        UdpClient.printRaw(gatewayIp, type, printer, bytes)
                    }
                    if (result.status == "QUEUED" || result.status == "DONE") {
                        Toast.makeText(this@PrintPreviewActivity, "打印任务已提交", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@PrintPreviewActivity,
                            "打印失败: ${result.msg}", Toast.LENGTH_LONG).show()
                        btnPrintNow.isEnabled = true
                        btnPrintNow.text = "确认打印"
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@PrintPreviewActivity,
                        "打印失败: ${e.message}", Toast.LENGTH_LONG).show()
                    btnPrintNow.isEnabled = true
                    btnPrintNow.text = "确认打印"
                }
            }
        }

        // 生成预览
        scope.launch {
            try {
                val pages: List<ByteArray> = withContext(Dispatchers.IO) {
                    if (type == "IMAGE") {
                        // 图片本地直接解码, 无需走网络渲染
                        listOf(bytes)
                    } else {
                        UdpClient.requestPreview(gatewayIp, type, printer, bytes)
                    }
                }
                withContext(Dispatchers.Main) {
                    container.removeView(loading)
                    if (pages.isEmpty()) {
                        container.addView(TextView(this@PrintPreviewActivity).apply {
                            text = "预览为空或生成失败。\n您可以点击下方\"确认打印\"直接打印。"
                            setPadding(16, 16, 16, 16)
                        })
                        return@withContext
                    }
                    val screenW = resources.displayMetrics.widthPixels
                    for (pageBytes in pages) {
                        val bmp = if (type == "IMAGE") {
                            BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
                        } else {
                            BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size)
                        }
                        if (bmp != null) {
                            val iv = ImageView(this@PrintPreviewActivity).apply {
                                setImageBitmap(bmp)
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { setMargins(0, 0, 0, 16) }
                            }
                            container.addView(iv)
                        }
                    }
                    val info = TextView(this@PrintPreviewActivity).apply {
                        text = "共 ${pages.size} 页"
                        textSize = 12f
                        setPadding(16, 8, 16, 8)
                    }
                    container.addView(info)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    container.removeView(loading)
                    container.addView(TextView(this@PrintPreviewActivity).apply {
                        text = "预览生成失败: ${e.message}\n您仍可点击下方\"确认打印\"直接打印。"
                        setPadding(16, 16, 16, 16)
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
