package com.sbbey.motionphoto

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.ByteArrayOutputStream
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
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var convertSection: View
    private lateinit var mergeSection: View
    private lateinit var btnMerge: MaterialButton
    private lateinit var btnPickPhoto: MaterialButton
    private lateinit var btnPickVideo: MaterialButton
    private lateinit var photoInfo: TextView
    private lateinit var videoInfo: TextView

    private var loadedName: String = ""
    private var loadedBytes: ByteArray? = null
    /** iPhone Live Photo 配对视频（主图无内嵌视频时，按同名 .MOV 自动配对）。 */
    private var pairVideoName: String = ""
    private var pairVideoBytes: ByteArray? = null
    private var photoName: String = ""
    private var photoBytes: ByteArray? = null
    private var videoName: String = ""
    private var videoBytes: ByteArray? = null
    private var lastOutput: File? = null
    private var busy = false

    private val settingsRequest = 1004
    private val gphotosRequest = 1005
    private val gphotosAppRequest = 1006

    companion object {
        /** Google Photos 应用包名。 */
        const val GOOGLE_PHOTOS_PACKAGE = "com.google.android.apps.photos"
    }

    /** Android 13+ 原生 Photo Picker（有 GMS 时自动含 Google 相册云端内容）；低版本自动回退系统文件选择器。 */
    private val pickMotionLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadDynamicPhoto(uri) else appendLog("已取消选择")
    }

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadPhoto(uri) else appendLog("已取消选择照片")
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadVideo(uri) else appendLog("已取消选择视频")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
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
        toggleMode = findViewById(R.id.toggleMode)
        convertSection = findViewById(R.id.convertSection)
        mergeSection = findViewById(R.id.mergeSection)
        btnMerge = findViewById(R.id.btnMerge)
        btnPickPhoto = findViewById(R.id.btnPickPhoto)
        btnPickVideo = findViewById(R.id.btnPickVideo)
        photoInfo = findViewById(R.id.photoInfo)
        videoInfo = findViewById(R.id.videoInfo)

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivityForResult(Intent(this, SettingsActivity::class.java), settingsRequest)
        }

        applyLiquidGlass()

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mergeMode = checkedId == R.id.btnModeMerge
            convertSection.visibility = if (mergeMode) View.GONE else View.VISIBLE
            mergeSection.visibility = if (mergeMode) View.VISIBLE else View.GONE
            btnToOppo.visibility = if (mergeMode) View.GONE else View.VISIBLE
            btnToGoogle.visibility = if (mergeMode) View.GONE else View.VISIBLE
            btnMerge.visibility = if (mergeMode) View.VISIBLE else View.GONE
            refreshButtons()
        }

        setBusy(false)

        btnPick.setOnClickListener {
            if (busy) return@setOnClickListener
            launchImport()
        }

        btnPickPhoto.setOnClickListener {
            if (busy) return@setOnClickListener
            pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnPickVideo.setOnClickListener {
            if (busy) return@setOnClickListener
            pickVideoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }

        btnToOppo.setOnClickListener {
            if (!busy) convert(toOppo = true)
        }
        btnToGoogle.setOnClickListener {
            if (!busy) convert(toOppo = false)
        }
        btnMerge.setOnClickListener {
            if (!busy) merge()
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
            runOnBackground {
                try {
                    val savedUri = saveToGallery(f)
                    runOnUiThread {
                        appendLog("已保存到相册：$savedUri")
                        toast("已保存到相册")
                    }
                } catch (e: Exception) {
                    runOnUiThread { appendLog("保存失败：${e.message}") }
                }
            }
        }

        appendLog("欢迎使用实况转换器\n一个导入，支持 iPhone / Google / 三星 / 小米 / 华为等所有动态图片 → OPPO Live2\n另可照片+视频合成实况图")
    }

    override fun onResume() {
        super.onResume()
        refreshButtons()
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        val theme = prefs.getString(SettingsActivity.KEY_THEME, "system") ?: "system"
        val themeId = when (theme) {
            "blue" -> R.style.AppTheme
            "purple" -> R.style.AppThemePurple
            "green" -> R.style.AppThemeGreen
            "pink" -> R.style.AppThemePink
            else -> R.style.AppThemeDynamic
        }
        setTheme(themeId)
    }

    private fun liquidGlassEnabled(): Boolean =
        getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.KEY_LIQUID_GLASS, false)

    private fun autoSaveEnabled(): Boolean =
        getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.KEY_AUTO_SAVE, false)

    private fun hevcHintEnabled(): Boolean =
        getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.KEY_HEVC_HINT, true)

    private fun applyLiquidGlass() {
        if (!liquidGlassEnabled()) return
        val cards = listOf(
            findViewById<MaterialCardView>(R.id.cardInput),
            findViewById<MaterialCardView>(R.id.cardConvert),
            findViewById<MaterialCardView>(R.id.cardResult)
        )
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val surface = ContextCompat.getColor(
            this, if (dark) R.color.liquid_glass_surface_dark else R.color.liquid_glass_surface
        )
        val stroke = ContextCompat.getColor(
            this, if (dark) R.color.liquid_glass_stroke_dark else R.color.liquid_glass_stroke
        )
        for (card in cards) {
            card.setCardBackgroundColor(surface)
            card.strokeColor = stroke
            card.strokeWidth = resources.getDimensionPixelSize(R.dimen.liquid_glass_stroke)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == settingsRequest) {
            if (resultCode == Activity.RESULT_OK) recreate()
            return
        }
        if (requestCode == gphotosRequest) {
            if (resultCode != Activity.RESULT_OK) return
            val uriStr = data?.getStringExtra(GooglePhotosActivity.RESULT_EXTRA_URI) ?: return
            loadDynamicPhoto(Uri.parse(uriStr))
            return
        }
        if (requestCode == gphotosAppRequest) {
            if (resultCode != Activity.RESULT_OK) return
            val uri = data?.data ?: return
            appendLog("已从 Google 相册选择图片")
            loadDynamicPhoto(uri)
        }
    }

    /**
     * 统一导入：优先直接打开 Google Photos 应用选图（能看到云端备份的照片），
     * 未安装时自动回退系统照片选择器（Android 13+ Photo Picker 亦含云端相册）。
     */
    private fun launchImport() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
            setPackage(GOOGLE_PHOTOS_PACKAGE)
        }
        if (intent.resolveActivity(packageManager) != null) {
            appendLog("正在打开 Google 相册…（可以选云端照片）")
            try {
                startActivityForResult(intent, gphotosAppRequest)
                return
            } catch (e: Exception) {
                // 启动失败继续回退
            }
        }
        appendLog("正在打开系统相册…")
        pickMotionLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Photo Picker / Google 相册返回的素材（图片/动态图）。 */
    private fun loadDynamicPhoto(uri: Uri) {
        setBusy(true)
        pairVideoBytes = null
        pairVideoName = ""
        appendLog("正在读取文件…")
        runOnBackground {
            try {
                val bytes = readUri(uri)
                val name = queryDisplayName(uri) ?: "motion_photo.jpg"
                runOnUiThread {
                    loadedName = name
                    loadedBytes = bytes
                    setBusy(false)
                    analyze(bytes, name)
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false); appendLog("读取失败：${e.message}") }
            }
        }
    }

    /** 合成模式：选择封面照片。 */
    private fun loadPhoto(uri: Uri) {
        setBusy(true)
        runOnBackground {
            try {
                val bytes = readUri(uri)
                val name = queryDisplayName(uri) ?: "photo.jpg"
                runOnUiThread {
                    photoName = name
                    photoBytes = bytes
                    setBusy(false)
                    photoInfo.text = "$name · ${formatSize(bytes.size.toLong())}"
                    appendLog("照片封面：$name（${formatSize(bytes.size.toLong())}）")
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false); appendLog("读取失败：${e.message}") }
            }
        }
    }

    /** 合成模式：选择视频。 */
    private fun loadVideo(uri: Uri) {
        setBusy(true)
        runOnBackground {
            try {
                val bytes = readUri(uri)
                val name = queryDisplayName(uri) ?: "video.mp4"
                runOnUiThread {
                    videoName = name
                    videoBytes = bytes
                    setBusy(false)
                    videoInfo.text = "$name · ${formatSize(bytes.size.toLong())}"
                    appendLog("视频：$name（${formatSize(bytes.size.toLong())}）")
                    appendLog("提示：建议 3 秒内 H.264 视频，动画效果最佳")
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false); appendLog("读取失败：${e.message}") }
            }
        }
    }

    private fun readUri(uri: Uri): ByteArray {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取文件")
        if (bytes.size > 128 * 1024 * 1024) throw IllegalStateException("文件超过 128MB，不支持")
        return bytes
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
            MotionPhotoParser.Format.SAMSUNG -> {
                formatChip.text = "三星动态图"
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
            // 单文件内没有动态视频：尝试按同名配对 iPhone Live Photo 的 .MOV
            pairVideoBytes = null
            pairVideoName = ""
            val base = name.substringBeforeLast('.', name)
            findPairingVideoUri(base)?.let { movUri ->
                fileDetail.text = "${formatSize(bytes.size.toLong())} · 检测到 iPhone Live Photo 配对视频"
                appendLog("检测到 iPhone Live Photo：正在读取配对视频…")
                runOnBackground {
                    try {
                        val movBytes = readUri(movUri)
                        runOnUiThread {
                            pairVideoName = queryDisplayName(movUri) ?: "${base}.MOV"
                            pairVideoBytes = movBytes
                            formatChip.text = "iPhone Live Photo"
                            formatChip.setChipBackgroundColorResource(R.color.md_light_primaryContainer)
                            formatChip.setTextColor(ContextCompat.getColor(this, R.color.md_light_onPrimaryContainer))
                            fileDetail.text = "iPhone Live Photo · 封面 ${formatSize(bytes.size.toLong())} · 视频 ${formatSize(movBytes.size.toLong())}"
                            appendLog("配对视频：${pairVideoName}（${formatSize(movBytes.size.toLong())}）")
                            appendLog("现在可以直接转换 → OPPO Live2")
                            setBusy(false)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            setBusy(false)
                            appendLog("读取配对视频失败：${e.message}")
                            fileDetail.text = "${formatSize(bytes.size.toLong())} · 未找到内嵌视频"
                        }
                    }
                }
                return
            }
            fileDetail.text = "${formatSize(bytes.size.toLong())} · 未找到内嵌视频"
            appendLog("这张图片里没有找到内嵌动态视频，无法转换。")
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
        if (result.videoCodecHint == "HEVC" && hevcHintEnabled()) {
            appendLog("提示：HEVC 视频在部分旧机型相册可能无法播放动画（ColorOS 14+ 正常）")
        }
    }

    /** 按文件名（忽略扩展名）在系统相册视频里查找同名 .MOV，用于配对 iPhone Live Photo。 */
    private fun findPairingVideoUri(baseName: String): Uri? {
        if (baseName.isBlank()) return null
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(1) ?: continue
                val id = c.getString(0) ?: continue
                val lower = display.lowercase()
                if (lower == "${baseName.lowercase()}.mov") {
                    return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        }
        return null
    }

    private fun convert(toOppo: Boolean) {
        val bytes = loadedBytes ?: return
        setBusy(true)
        appendLog(if (toOppo) "正在转为 OPPO Live2…" else "正在转为 Google 动态图…")

        runOnBackground {
            try {
                val parsed = MotionPhotoParser(bytes).parse()
                val video = parsed.videoBytes
                    ?: pairVideoBytes
                    ?: throw IllegalStateException("没有动态视频")

                val cover = ensureJpegCover(parsed.coverJpeg)

                val outBytes = if (toOppo) {
                    MotionPhotoConverter.toOppoLive2(cover, video)
                } else {
                    MotionPhotoConverter.toGoogleMotionPhoto(cover, video)
                }

                val base = loadedName.substringBeforeLast('.').ifBlank { "motion_photo" }
                val suffix = if (toOppo) ".live" else ".google"
                val outFile = writeOutput(outBytes, "${base}_converted$suffix.jpg")

                runOnUiThread {
                    setBusy(false)
                    lastOutput = outFile
                    appendLog(
                        if (toOppo) "转换完成 → OPPO Live2 实况图"
                        else "转换完成 → Google 动态图片"
                    )
                    appendLog("文件：${outFile.absolutePath}")
                    appendLog("大小：${formatSize(outBytes.size.toLong())}")
                    maybeAutoSave(outFile, outBytes)
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false); appendLog("转换失败：${e.message}") }
            }
        }
    }

    /** 模式 2：照片 + 视频 → OPPO Live2（对应原仓库「原库内容」功能）。 */
    private fun merge() {
        val photo = photoBytes ?: run {
            toast("请先选择照片封面")
            return
        }
        val video = videoBytes ?: run {
            toast("请先选择视频")
            return
        }
        setBusy(true)
        appendLog("正在合成 OPPO Live2…")

        runOnBackground {
            try {
                val cover = ensureJpegCover(photo)
                val outBytes = MotionPhotoConverter.toOppoLive2(cover, video)
                val base = photoName.substringBeforeLast('.').ifBlank { "merged" }
                val outFile = writeOutput(outBytes, "${base}_merged.live.jpg")

                runOnUiThread {
                    setBusy(false)
                    lastOutput = outFile
                    appendLog("合成完成 → OPPO Live2 实况图（照片+视频）")
                    appendLog("文件：${outFile.absolutePath}")
                    appendLog("大小：${formatSize(outBytes.size.toLong())}")
                    maybeAutoSave(outFile, outBytes)
                }
            } catch (e: Exception) {
                runOnUiThread { setBusy(false); appendLog("合成失败：${e.message}") }
            }
        }
    }

    private fun writeOutput(bytes: ByteArray, fileName: String): File {
        val outDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "LivePhotoConvert")
        outDir.mkdirs()
        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { it.write(bytes) }
        return outFile
    }

    /** 设置开启「自动保存到相册」时，转换成功后自动写入相册。 */
    private fun maybeAutoSave(outFile: File, outBytes: ByteArray) {
        if (!autoSaveEnabled()) return
        runOnBackground {
            try {
                val uri = saveToGallery(outFile)
                runOnUiThread {
                    appendLog("已自动保存到相册：$uri")
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("自动保存失败：${e.message}") }
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

    /**
     * 封面转 JPEG：已经是 JPEG 直接返回；HEIC 等其它格式用系统解码器转成 JPEG
     * （iPhone Live Photo 的主图常为 HEIC，OPPO Live2 封面必须是 JPEG）。
     */
    private fun ensureJpegCover(cover: ByteArray): ByteArray {
        if (cover.size >= 2 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte()) return cover
        val bmp = BitmapFactory.decodeByteArray(cover, 0, cover.size)
            ?: throw IllegalStateException("无法解码封面（仅支持 JPEG / HEIC 等系统可解码格式）")
        val bos = ByteArrayOutputStream()
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, bos)) {
            throw IllegalStateException("封面转 JPEG 失败")
        }
        appendLog("封面是 HEIC/非 JPEG，已转码为 JPEG")
        return bos.toByteArray()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        refreshButtons()
    }

    private fun refreshButtons() {
        val mergeMode = toggleMode.checkedButtonId == R.id.btnModeMerge
        val hasFile = loadedBytes != null && !busy
        btnPick.isEnabled = !busy
        btnPickPhoto.isEnabled = !busy
        btnPickVideo.isEnabled = !busy
        if (mergeMode) {
            btnMerge.isEnabled = !busy && photoBytes != null && videoBytes != null
        } else {
            btnToOppo.isEnabled = hasFile
            btnToGoogle.isEnabled = hasFile
        }
        btnShare.isEnabled = !busy && lastOutput != null
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
