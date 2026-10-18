package com.sbbey.motionphoto

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google OAuth 2.0（PKCE 公共客户端）：
 * 浏览器中完成授权，自定义 scheme 回跳取 code，再换 access_token / refresh_token。
 * 客户端类型在 Google Cloud Console 选择「Web 应用」，回调地址填 sbbeylivephoto://oauth2callback。
 */
object GoogleAuth {

    const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val REDIRECT_URI = "sbbeylivephoto://oauth2callback"

    // ---------- PKCE ----------

    private const val VERIFIER_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun randomCodeVerifier(): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(64)
        repeat(64) { sb.append(VERIFIER_CHARS[rnd.nextInt(VERIFIER_CHARS.length)]) }
        return sb.toString()
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun randomState(): String {
        val rnd = SecureRandom()
        val bytes = ByteArray(16)
        rnd.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------- 认证 URL / 回调解析 ----------

    fun buildAuthUrl(clientId: String, codeVerifier: String, state: String): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to SCOPE,
            "code_challenge" to codeChallenge(codeVerifier),
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state
        )
        return AUTH_URL + "?" + params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    /** 从回调 URI 解析 code 与 state；格式 sbbeylivephoto://oauth2callback?code=..&state=.. */
    fun parseCallback(uri: String): Pair<String?, String?> {
        val q = uri.substringAfter('?', "")
        fun get(name: String): String? {
            val re = Regex("""(?:^|&)$name=([^&]*)""")
            return re.find(q)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        }
        return get("code") to get("state")
    }

    // ---------- Token 交换 / 刷新 ----------

    data class TokenResult(val accessToken: String, val refreshToken: String?, val expiresInSec: Long)

    /** 用授权码换 token（application/x-www-form-urlencoded）。clientSecret 可选（PKCE 公共客户端）。 */
    fun exchangeCode(
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String
    ): TokenResult {
        val body = mutableListOf(
            "grant_type=authorization_code",
            "code=${enc(code)}",
            "client_id=${enc(clientId)}",
            "redirect_uri=${enc(REDIRECT_URI)}",
            "code_verifier=${enc(codeVerifier)}"
        )
        clientSecret?.takeIf { it.isNotBlank() }?.let { body += "client_secret=${enc(it)}" }
        val resp = postForm(TOKEN_URL, body.joinToString("&"))
        return parseTokenResponse(resp)
    }

    fun refreshToken(clientId: String, clientSecret: String?, refreshToken: String): TokenResult {
        val body = mutableListOf(
            "grant_type=refresh_token",
            "refresh_token=${enc(refreshToken)}",
            "client_id=${enc(clientId)}"
        )
        clientSecret?.takeIf { it.isNotBlank() }?.let { body += "client_secret=${enc(it)}" }
        val resp = postForm(TOKEN_URL, body.joinToString("&"))
        return parseTokenResponse(resp)
    }

    // ---------- JSON ----------

    fun parseTokenResponse(json: String): TokenResult {
        val obj = org.json.JSONObject(json)
        val access = obj.optString("access_token", "")
        if (access.isEmpty()) {
            throw IllegalStateException("Google 授权失败：${obj.optString("error_description", obj.optString("error", "未知错误"))}")
        }
        return TokenResult(
            accessToken = access,
            refreshToken = obj.optString("refresh_token", null).takeIf { !it.isNullOrEmpty() },
            expiresInSec = obj.optLong("expires_in", 3600)
        )
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun postForm(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw IllegalStateException("Token 请求失败 HTTP $code：${text.take(300)}")
        return text
    }
}
