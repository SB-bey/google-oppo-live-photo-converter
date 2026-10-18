package com.sbbey.motionphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google 云端接入的纯逻辑测试：PKCE、URL 构造、回调解析、JSON 解析。 */
class GoogleCloudTest {

    @Test
    fun `PKCE verifier 符合规范`() {
        val v = GoogleAuth.randomCodeVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })
        // 两次随机不重复
        assertTrue(GoogleAuth.randomCodeVerifier() != v)
    }

    @Test
    fun `PKCE challenge 使用官方 RFC 7636 测试向量`() {
        // RFC 7636 Appendix B 示例
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, GoogleAuth.codeChallenge(verifier))
    }

    @Test
    fun `认证 URL 包含必需参数`() {
        val url = GoogleAuth.buildAuthUrl("client-id-abc", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "state123")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id=client-id-abc"))
        assertTrue(url.contains("redirect_uri=sbbeylivephoto%3A%2F%2Foauth2callback"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("scope=" + java.net.URLEncoder.encode(GoogleAuth.SCOPE, "UTF-8")))
        assertTrue(url.contains("code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state123"))
        assertTrue(url.contains("access_type=offline"))
    }

    @Test
    fun `回调 URI 解析 code 和 state`() {
        val uri = "sbbeylivephoto://oauth2callback?code=AUTH_CODE_XYZ&state=state123"
        val (code, state) = GoogleAuth.parseCallback(uri)
        assertEquals("AUTH_CODE_XYZ", code)
        assertEquals("state123", state)
    }

    @Test
    fun `回调无 code 时返回 null`() {
        val (code, state) = GoogleAuth.parseCallback("sbbeylivephoto://oauth2callback?error=access_denied")
        assertEquals(null, code)
        assertEquals(null, state)
    }

    @Test
    fun `token 响应解析`() {
        val json = """{"access_token":"tok123","refresh_token":"ref456","expires_in":3599,"token_type":"Bearer"}"""
        val t = GoogleAuth.parseTokenResponse(json)
        assertEquals("tok123", t.accessToken)
        assertEquals("ref456", t.refreshToken)
        assertEquals(3599L, t.expiresInSec)
    }

    @Test
    fun `会话 JSON 解析`() {
        val json = """{
            "id":"SES1","pickerUri":"https://photos.google.com/picker/xyz",
            "pollingConfig":{"pollInterval":"3.5s","timeoutIn":"120s"},
            "mediaItemsSet":false
        }"""
        val s = GooglePhotosClient.parseSession(json)
        assertEquals("SES1", s.id)
        assertTrue(s.pickerUri.contains("picker/xyz"))
        assertEquals(3L, s.pollIntervalSec)
        assertEquals(120L, s.timeoutSec)
        assertEquals(false, s.mediaItemsSet)
    }

    @Test
    fun `媒体项 JSON 解析（兼容嵌套 mediaFile）`() {
        val json = """{"mediaItems":[
            {"id":"M1","mediaFile":{"filename":"photo.jpg","mimeType":"image/jpeg","baseUrl":"https://lh3.googleusercontent.com/abc"},"mediaMetadata":{"status":"READY"}},
            {"id":"M2","filename":"clip.mp4","mimeType":"video/mp4","baseUrl":"https://lh3.googleusercontent.com/def"}
        ]}"""
        val items = GooglePhotosClient.parseMediaItems(json)
        assertEquals(2, items.size)
        assertEquals("M1", items[0].id)
        assertEquals("photo.jpg", items[0].filename)
        assertEquals("https://lh3.googleusercontent.com/abc", items[0].baseUrl)
        assertEquals("video/mp4", items[1].mimeType)
    }

    @Test
    fun `下载 URL 构造`() {
        assertEquals("https://x/abc=d", GooglePhotosClient.downloadUrl("https://x/abc", false))
        assertEquals("https://x/abc=dv", GooglePhotosClient.downloadUrl("https://x/abc", true))
        // baseUrl 末尾已带 = 时直接拼接
        assertEquals("https://x/abc=d", GooglePhotosClient.downloadUrl("https://x/abc=", false))
    }

    @Test
    fun `非法 token 响应抛错`() {
        try {
            GoogleAuth.parseTokenResponse("""{"error":"invalid_grant","error_description":"Token expired"}""")
            assertTrue("应当抛异常", false)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Token expired"))
        }
    }
}
