package com.fy.printterminal

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 打印预览界面 (安卓端)
 * 图片: 直接渲染显示
 * PDF: 显示页数等元信息
 * 文本: 显示前 2000 字
 */
class PrintPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val type = intent.getStringExtra("file_type") ?: "UNKNOWN"
        val printer = intent.getStringExtra("printer") ?: ""
        val data = PreviewCache.data
        val tvTitle = findViewById<TextView>(R.id.tvPreviewTitle)
        val container = findViewById<android.widget.LinearLayout>(R.id.previewContainer)

        tvTitle.text = "打印预览 ($type) → $printer"

        if (data == null) {
            container.addView(TextView(this).apply { text = "无预览数据" })
            return
        }

        when (type) {
            "IMAGE" -> {
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                if (bmp != null) {
                    val iv = ImageView(this).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        maxWidth = resources.displayMetrics.widthPixels - 64
                    }
                    container.addView(iv)
                } else {
                    container.addView(TextView(this).apply { text = "图片解析失败" })
                }
            }
            "PDF" -> {
                val pdfStr = String(data, Charsets.UTF_8)
                val pageCount = Regex("(?i)/Type\\s*/Page[^s]").findAll(pdfStr).count()
                container.addView(TextView(this).apply {
                    text = """
                        |PDF 文档
                        |文件大小: ${data.size / 1024} KB
                        |估计页数: ${if (pageCount > 0) pageCount else "未知"}
                        |打印方式: 直接发送到 PC 端 PDFBox 渲染
                    """.trimMargin()
                    textSize = 15f
                    setPadding(16, 16, 16, 16)
                })
            }
            "TEXT" -> {
                val text = String(data, 0, minOf(2000, data.size), Charsets.UTF_8)
                container.addView(TextView(this).apply {
                    this.text = "$text\n\n...(总长 ${data.size} 字节)"
                    textSize = 14f
                    setPadding(16, 16, 16, 16)
                })
            }
            else -> {
                container.addView(TextView(this).apply {
                    text = "不支持预览此类型: $type\n大小: ${data.size / 1024} KB"
                })
            }
        }

        findViewById<android.widget.Button>(R.id.btnPrintNow).setOnClickListener {
            // 返回主界面并触发打印
            Toast.makeText(this,
                "请在主界面点击打印按钮", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
