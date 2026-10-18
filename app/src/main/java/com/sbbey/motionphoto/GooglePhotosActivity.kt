package com.sbbey.motionphoto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Google 相册（云端）导入流程：
 *   OAuth 授权（浏览器）→ sessions.create（API）→ 浏览器打开 pickerUri+/autoclose 选照片
 *   → 轮询 sessions.get → mediaItems.list → baseUrl 下载 → 缓存文件 → 返回 URI 给主页。
 */
class GooglePhotosActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "gphotos"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_API_KEY = "api_key"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_VERIFIER = "code_verifier"
        const val KEY_STATE = "state"
        const val KEY_SESSION = "session_id"
        const val RESULT_EXTRA_URI = "picked_uri"
        private const val MAX_WAIT_MS = 10 * 60 * 1000L
    }

    private lateinit var status: TextView
    private lateinit var cancelBtn: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_google_photos)

        status = findViewById(R.id.gpStatus)
        cancelBtn = findViewById(R.id.gpCancel)
        cancelBtn.setOnClickListener { finish() }

        // 可能是 OAuth 回调直接拉起（单任务模式）：先处理回调，否则正常启动流程
        if (handleOAuthCallback(intent)) return

        startPickerFlow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (handleOAuthCallback(intent)) {
            // 回调处理完成，继续后续流程
        }
    }

    /** 处理 sbbeylivephoto://oauth2callback 回调。返回 true 表示消费了回调。 */
    private fun handleOAuthCallback(intent: Intent): Boolean {
        val uri = intent.dataString ?: return false
        if (!uri.startsWith(GoogleAuth.REDIRECT_URI)) return false
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val (code, state) = GoogleAuth.parseCallback(uri)
        val savedState = prefs.getString(KEY_STATE, null)
        if (state == null || savedState == null || state != savedState) {
            setStatus("授权回调校验失败，请重试")
            return true
        }
        if (code == null) {
            setStatus("授权被取消")
            return true
        }
        setStatus("授权成功，正在连接 Google 相册…")
        val clientId = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        val secret = prefs.getString(KEY_CLIENT_SECRET, "")
        val verifier = prefs.getString(KEY_VERIFIER, "") ?: ""
        runOnBackground {
            try {
                val result = GoogleAuth.exchangeCode(clientId, secret, code, verifier)
                prefs.edit()
                    .putString(KEY_ACCESS, result.accessToken)
                    .putString(KEY_REFRESH, result.refreshToken)
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + result.expiresInSec * 1000)
                    .putString(KEY_VERIFIER, null)
                    .putString(KEY_STATE, null)
                    .apply()
                runOnUiThread { continuePickerFlow() }
            } catch (e: Exception) {
                runOnUiThread { setStatus("授权失败：${e.message}") }
            }
        }
        return true
    }

    private fun startPickerFlow() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clientId = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        if (clientId.isBlank()) {
            setStatus("还没配置 Google 客户端 ID\n请先到「设置 → Google 相册接入」填入 OAuth Client ID")
            return
        }
        ensureTokenThen { continuePickerFlow() }
    }

    private fun ensureTokenThen(after: () -> Unit) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val access = prefs.getString(KEY_ACCESS, "") ?: ""
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (access.isNotEmpty() && expiresAt > System.currentTimeMillis() + 60_000) {
            after()
            return
        }
        val refresh = prefs.getString(KEY_REFRESH, "") ?: ""
        if (refresh.isNotEmpty()) {
            setStatus("正在刷新登录状态…")
            val clientId = prefs.getString(KEY_CLIENT_ID, "") ?: ""
            val secret = prefs.getString(KEY_CLIENT_SECRET, "")
            runOnBackground {
                try {
                    val result = GoogleAuth.refreshToken(clientId, secret, refresh)
                    prefs.edit()
                        .putString(KEY_ACCESS, result.accessToken)
                        .putString(KEY_REFRESH, result.refreshToken ?: refresh)
                        .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + result.expiresInSec * 1000)
                        .apply()
                    runOnUiThread { after() }
                } catch (e: Exception) {
                    runOnUiThread {
                        setStatus("登录已过期，需要重新授权")
                        startBrowserOAuth()
                    }
                }
            }
            return
        }
        startBrowserOAuth()
    }

    private fun startBrowserOAuth() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clientId = prefs.getString(KEY_CLIENT_ID, "") ?: return
        val verifier = GoogleAuth.randomCodeVerifier()
        val state = GoogleAuth.randomState()
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val authUrl = GoogleAuth.buildAuthUrl(clientId, verifier, state)
        setStatus("正在打开浏览器登录 Google…\n登录并授权后会自动返回本应用")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
        } catch (e: Exception) {
            setStatus("无法打开浏览器：${e.message}")
        }
    }

    /** 创建会话 → 浏览器打开 pickerUri 选照片 → 轮询 → 列表 → 下载。 */
    private fun continuePickerFlow() {
        if (running) return
        running = true
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_ACCESS, "") ?: ""
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (token.isEmpty()) {
            setStatus("没有有效的登录凭证，请重试")
            running = false
            return
        }
        setStatus("正在创建选择会话…")
        runOnBackground {
            try {
                val session = GooglePhotosClient.createSession(token, apiKey)
                prefs.edit().putString(KEY_SESSION, session.id).apply()
                val pickerUri = session.pickerUri + "/autoclose"
                runOnUiThread {
                    setStatus("已打开 Google 相册选择器\n选好照片后浏览器会自动关闭并返回")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pickerUri)))
                    } catch (e: Exception) {
                        setStatus("无法打开浏览器：${e.message}")
                        running = false
                    }
                }
                // 轮询等待用户完成选择（浏览器在前台，本页在后台，轮询线程不受影响）
                var waited = 0L
                val interval = session.pollIntervalSec * 1000
                while (running && waited < MAX_WAIT_MS) {
                    Thread.sleep(interval.coerceIn(2000, 15000))
                    waited += interval.coerceIn(2000, 15000)
                    val s = GooglePhotosClient.getSession(token, apiKey, session.id)
                    if (s.mediaItemsSet) {
                        val items = GooglePhotosClient.listMediaItems(token, apiKey, session.id)
                        runOnUiThread { downloadFirst(items, token) }
                        return@runOnBackground
                    }
                }
                runOnUiThread {
                    setStatus("等待超时，未检测到选择结果\n（如果已在浏览器选好，请返回本页点右上角「重试」）")
                    running = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("出错：${e.message}")
                    running = false
                }
            }
        }
    }

    private fun downloadFirst(items: List<GooglePhotosClient.MediaItem>, token: String) {
        val item = items.firstOrNull()
        if (item == null) {
            setStatus("没有选到素材，请重试")
            running = false
            return
        }
        setStatus("正在下载：${item.filename ?: item.id}")
        runOnBackground {
            try {
                val isVideo = (item.mimeType ?: "").startsWith("video")
                val url = GooglePhotosClient.downloadUrl(item.baseUrl, isVideo)
                val bytes = GooglePhotosClient.download(url, token)
                val dir = File(cacheDir, "GooglePhotos").apply { mkdirs() }
                val name = sanitize(item.filename ?: (if (isVideo) "picked.mp4" else "picked.jpg"))
                val file = File(dir, name)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_EXTRA_URI, uri.toString()))
                runOnUiThread { setStatus("完成，返回主页…"); finish() }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("下载失败：${e.message}")
                    running = false
                }
            }
        }
    }

    private fun sanitize(name: String): String {
        var n = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (n.isBlank()) n = "picked.jpg"
        return n.take(80)
    }

    private fun setStatus(text: String) {
        if (::status.isInitialized) status.text = text
    }

    private fun runOnBackground(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Throwable) {
                runOnUiThread { setStatus("出错了：${e.message}") }
                running = false
            }
        }.start()
    }
}
