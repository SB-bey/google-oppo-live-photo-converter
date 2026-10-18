package com.sbbey.motionphoto

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scrollView: NestedScrollView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var formatChip: Chip
    private lateinit var fileInfo: TextView
    private lateinit var fileDetail: TextView
    private lateinit var btnPick: MaterialButton
    private lateinit var btnToOppo: MaterialButton
    private lateinit var btnToGoogle: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnSave: MaterialButton

    private var loadedName: String = ""
    private var loadedBytes: ByteArray? = null
    private var lastOutput: File? = null
    private var busy = false

    private val pickRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        scrollView = findViewById(R.id.scrollView)
        progress = findViewById(R.id.progress)
        formatChip = findViewById(R.id.formatChip)
        fileInfo = findViewById(R.id.fileInfo)
        fileDetail = findViewById(R.id.fileDetail)
        btnPick = findViewById(R.id.btnPick)
        btnToOppo = findViewById(R.id.btnToOppo)
        btnToGoogle = findViewById(R.id.btnToGoogle)
        btnShare = findViewById(R.id.btnShare)
        btnSave = findViewById(R.id.btnSave)

        setBusy(false)

        btnPick.setOnClickListener {
            if (busy) return@setOnClickListener
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/jpg"))
            }
            startActivityForResult(intent, pickRequest)
        }

        btnToOppo.setOnClickListener {
            if (!busy) convert(toOppo = true)
        }
        btnToGoogle.setOnClickListener {
            if (!busy) convert(toOppo = false)
        }

        btnShare.setOnClickListener {
            val f = lastOutput ?: return@setOnClickListener
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享实况图片"))
        }

        btnSave.setOnClickListener {
            val f = lastOutput ?: return@setOnClickListener
            setBusy(true)
            appendLog("正在保存到相册…")
            runOnBackground {
                try {
                    val savedUri = saveToGallery(f)
                    runOnUiThread {
                        setBusy(false)
                        appendLog("已保存到相册：$savedUri")
                        toast("已保存到相册")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setBusy(false)
                        appendLog("保存失败：${e.message}")
                    }
                }
            }
        }

        appendLog("欢迎使用 Google ⇄ OPPO 实况转换器\n点击「选择动态图片」开始")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRequest || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        setBusy(true)
        appendLog("正在读取文件…")
        runOnBackground {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                if (bytes.size > 128 * 1024 * 1024) throw IllegalStateException("文件超过 128MB，不支持")

                val name = queryDisplayName(uri) ?: "motion_photo.jpg"
                runOnUiThread {
                    loadedName = name
                    loadedBytes = bytes
                    setBusy(false)
                    analyze(bytes, name)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    appendLog("读取失败：${e.message}")
                }
            }
        }
    }

    private fun analyze(bytes: ByteArray, name: String) {
        fileInfo.text = name
        fileDetail.text = formatSize(bytes.size.toLong())
        appendLog("已加载：$name（${formatSize(bytes.size.toLong())}）")
        val result = try {
            MotionPhotoParser(bytes).parse()
        } catch (e: Exception) {
            fileDetail.text = "解析失败"
            appendLog("解析失败：${e.message}")
            return
        }

        when (result.format) {
            MotionPhotoParser.Format.GOOGLE -> {
                formatChip.text = "Google 动态图"
                formatChip.setChipBackgroundColorResource(R.color.md_light_primaryContainer)
                formatChip.setTextColor(ContextCompat.getColor(this, R.color.md_light_onPrimaryContainer))
            }
            MotionPhotoParser.Format.OPPO -> {
                formatChip.text = "OPPO Live2"
                formatChip.setChipBackgroundColorResource(R.color.md_light_tertiaryContainer)
                formatChip.setTextColor(ContextCompat.getColor(this, R.color.md_light_onTertiaryContainer))
            }
            MotionPhotoParser.Format.GENERIC_MOTION -> {
                formatChip.text = "动态图片"
                formatChip.setChipBackgroundColorResource(R.color.md_light_secondaryContainer)
                formatChip.setTextColor(ContextCompat.getColor(this, R.color.md_light_onSecondaryContainer))
            }
            else -> {
                formatChip.text = "无动态视频"
                formatChip.setChipBackgroundColorResource(R.color.md_light_errorContainer)
                formatChip.setTextColor(ContextCompat.getColor(this, R.color.md_light_onErrorContainer))
            }
        }

        val video = result.videoBytes
        if (video == null || video.isEmpty()) {
            fileDetail.text = "${formatSize(bytes.size.toLong())} · 未找到内嵌视频"
            appendLog("这张图片里没有找到内嵌动态视频，无法转换。")
            btnToOppo.isEnabled = false
            btnToGoogle.isEnabled = false
            return
        }

        val extra = buildString {
            append(formatSize(bytes.size.toLong()))
            append(" · 视频 ")
            append(formatSize(video.size.toLong()))
            result.videoCodecHint?.let { append(" · $it") }
        }
        fileDetail.text = extra
        appendLog("内嵌视频：${formatSize(video.size.toLong())}")
        result.videoCodecHint?.let { appendLog("视频编码：$it") }
        if (result.videoCodecHint == "HEVC") {
            appendLog("提示：HEVC 视频在部分旧机型相册可能无法播放动画（ColorOS 14+ 正常）")
        }
    }

    private fun convert(toOppo: Boolean) {
        val bytes = loadedBytes ?: return
        setBusy(true)
        appendLog(if (toOppo) "正在转为 OPPO Live2…" else "正在转为 Google 动态图…")

        runOnBackground {
            try {
                val parsed = MotionPhotoParser(bytes).parse()
                val video = parsed.videoBytes
                    ?: throw IllegalStateException("没有内嵌视频")

                val outBytes = if (toOppo) {
                    MotionPhotoConverter.toOppoLive2(parsed.coverJpeg, video)
                } else {
                    MotionPhotoConverter.toGoogleMotionPhoto(parsed.coverJpeg, video)
                }

                val base = loadedName.substringBeforeLast('.').ifBlank { "motion_photo" }
                val suffix = if (toOppo) ".live" else ".google"
                val fileName = "${base}_converted$suffix.jpg"

                val outDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "LivePhotoConvert")
                outDir.mkdirs()
                val outFile = File(outDir, fileName)
                FileOutputStream(outFile).use { it.write(outBytes) }

                runOnUiThread {
                    setBusy(false)
                    lastOutput = outFile
                    appendLog(
                        if (toOppo) "转换完成 → OPPO Live2 实况图"
                        else "转换完成 → Google 动态图片"
                    )
                    appendLog("文件：${outFile.absolutePath}")
                    appendLog("大小：${formatSize(outBytes.size.toLong())}")
                    btnShare.isEnabled = true
                    btnSave.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    appendLog("转换失败：${e.message}")
                }
            }
        }
    }

    /** API 29+ 写 MediaStore 相册；更老版本用扫描 app 目录。 */
    private fun saveToGallery(file: File): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LivePhotos")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("无法创建相册条目")
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            uri
        } else {
            MediaScannerCompat.scan(this, file)
            Uri.fromFile(file)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.2f MB".format(kb / 1024.0)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnPick.isEnabled = !b
        val hasFile = loadedBytes != null && !busy
        btnToOppo.isEnabled = hasFile
        btnToGoogle.isEnabled = hasFile
        btnShare.isEnabled = !b && lastOutput != null
        btnSave.isEnabled = btnShare.isEnabled
    }

    private fun appendLog(text: String) {
        logView.append(if (logView.text.isEmpty()) text else "\n$text")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun runOnBackground(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    appendLog("出错了：${e.message}")
                }
            }
        }.start()
    }
}
