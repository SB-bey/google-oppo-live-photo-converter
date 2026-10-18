package com.sbbey.motionphoto

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Photos Picker API 客户端：
 * 1. sessions.create → pickerUri（浏览器打开，用户选照片）
 * 2. sessions.get 轮询直到 mediaItemsSet=true
 * 3. mediaItems.list 拿 baseUrl
 * 4. baseUrl + 参数（=d 图片原图 / =dv 视频）带 Bearer 下载
 *
 * 官方文档：https://developers.google.com/photos/picker/guides/get-started
 */
object GooglePhotosClient {

    private const val BASE = "https://photospicker.googleapis.com/v1"

    data class Session(
        val id: String,
        val pickerUri: String,
        val pollIntervalSec: Long,
        val timeoutSec: Long,
        val expireTime: String?,
        val mediaItemsSet: Boolean
    )

    data class MediaItem(
        val id: String,
        val baseUrl: String,
        val mimeType: String?,
        val filename: String?
    )

    /** 创建会话，限制用户最多选 1 个素材。 */
    fun createSession(accessToken: String, apiKey: String?): Session {
        val body = JSONObject()
            .put("pickingConfig", JSONObject().put("maxItemCount", "1"))
            .toString()
        val resp = request("POST", "$BASE/sessions", body, accessToken, apiKey)
        return parseSession(resp)
    }

    fun getSession(accessToken: String, apiKey: String?, sessionId: String): Session {
        val resp = request("GET", "$BASE/sessions/$sessionId", null, accessToken, apiKey)
        return parseSession(resp)
    }

    /** 列出会话中用户选中的素材。 */
    fun listMediaItems(accessToken: String, apiKey: String?, sessionId: String): List<MediaItem> {
        val resp = request("GET", "$BASE/mediaItems?sessionId=${java.net.URLEncoder.encode(sessionId, "UTF-8")}", null, accessToken, apiKey)
        return parseMediaItems(resp)
    }

    /** 下载素材字节。图片用 =d（保留 EXIF 原图），视频用 =dv。 */
    fun download(url: String, accessToken: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw IOException("下载失败 HTTP $code：${err.take(200)}")
        }
        return conn.inputStream.use { it.readBytes() }
    }

    /** 构造下载 URL：图片追加 =d，视频追加 =dv。 */
    fun downloadUrl(baseUrl: String, isVideo: Boolean): String {
        val suffix = if (isVideo) "=dv" else "=d"
        return if (baseUrl.endsWith("=") || baseUrl.endsWith("?") || baseUrl.endsWith("&")) {
            baseUrl + suffix.removePrefix("=")
        } else {
            baseUrl + suffix
        }
    }

    // ---------- JSON 解析（防御式：兼容 top-level 与 mediaFile 嵌套） ----------

    fun parseSession(json: String): Session {
        val obj = JSONObject(json)
        val id = obj.optString("id")
        val pickerUri = obj.optString("pickerUri")
        if (id.isEmpty() || pickerUri.isEmpty()) {
            throw IOException("创建会话失败：$json".take(300))
        }
        val poll = obj.optJSONObject("pollingConfig")
        return Session(
            id = id,
            pickerUri = pickerUri,
            pollIntervalSec = poll?.optString("pollInterval")?.let { durationToSec(it) } ?: 3,
            timeoutSec = poll?.optString("timeoutIn")?.let { durationToSec(it) } ?: 120,
            expireTime = obj.optString("expireTime").takeIf { it.isNotBlank() },
            mediaItemsSet = obj.optBoolean("mediaItemsSet", false)
        )
    }

    fun parseMediaItems(json: String): List<MediaItem> {
        val obj = JSONObject(json)
        val arr: JSONArray = obj.optJSONArray("mediaItems") ?: return emptyList()
        val items = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val file = it.optJSONObject("mediaFile")
            val baseUrl = it.optString("baseUrl", "")
                .ifEmpty { file?.optString("baseUrl", "") ?: "" }
            if (baseUrl.isEmpty()) continue
            items += MediaItem(
                id = it.optString("id"),
                baseUrl = baseUrl,
                mimeType = it.optString("mimeType", "").ifEmpty { file?.optString("mimeType", "") ?: "" },
                filename = it.optString("filename", "").ifEmpty { file?.optString("filename", "") ?: "" }
            )
        }
        return items
    }

    private fun durationToSec(d: String): Long {
        // "3.5s" → 3；"120s" → 120
        return d.removeSuffix("s").toDoubleOrNull()?.toLong()?.coerceAtLeast(1) ?: 3
    }

    private fun request(method: String, url: String, body: String?, token: String, apiKey: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Authorization", "Bearer $token")
        apiKey?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Goog-Api-Key", it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw IOException("Google Photos API 错误 HTTP $code：${text.take(300)}")
        return text
    }
}
