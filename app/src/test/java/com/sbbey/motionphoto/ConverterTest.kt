package com.sbbey.motionphoto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转换逻辑的 JVM 自测：用合成样本验证 Google 动态图 → OPPO Live2 → Google 双向字节结构。
 */
class ConverterTest {

    /** 构造最小可扫描 JPEG：SOI + APP0 + APP1(伪EXIF) + APP2(伪MPF) + SOS + 熵编码字节 + EOI。 */
    private fun fakeJpeg(): ByteArray {
        fun seg(marker: Int, body: ByteArray): ByteArray =
            JpegUtil.concat(byteArrayOf(0xFF.toByte(), marker.toByte()), JpegUtil.u16be(body.size + 2), body)

        val app0 = seg(0xE0, JpegUtil.utf8("JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000"))
        val fakeExif = seg(0xE1, JpegUtil.utf8("Exif\u0000\u0000" + "FAKEEXIFDATA"))
        val fakeMpf = seg(0xE2, JpegUtil.utf8("MPF\u0000" + "FAKEMPF"))
        val sos = seg(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0x00))
        val scan = byteArrayOf(0x12, 0x34, 0x56, 0x78.toByte(), 0x9A.toByte())
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return JpegUtil.concat(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), app0, fakeExif, fakeMpf, sos, scan, eoi)
    }

    /** 构造伪 MP4：ftyp + moov + mdat（含 avc1 采样条目，模拟 H.264 视频）。 */
    private fun fakeMp4(size: Int = 4096): ByteArray {
        val ftyp = JpegUtil.concat(
            JpegUtil.u32be(24), JpegUtil.utf8("ftyp"), JpegUtil.utf8("mp42"),
            JpegUtil.u32be(0), JpegUtil.utf8("mp42isom")
        )
        val moovBody = JpegUtil.concat(JpegUtil.utf8("moov"), JpegUtil.utf8("avc1"))
        val moov = JpegUtil.concat(JpegUtil.u32be(8 + moovBody.size.toLong()), moovBody)
        val mdatBody = ByteArray(size - ftyp.size - moov.size) { (it % 251).toByte() }
        val mdat = JpegUtil.concat(JpegUtil.u32be(8 + mdatBody.size.toLong()), JpegUtil.utf8("mdat"), mdatBody)
        return JpegUtil.concat(ftyp, moov, mdat)
    }

    /** 构造 Google 动态图：JPEG(含 Google XMP) + 视频尾插。 */
    private fun fakeGoogleMotionPhoto(): ByteArray {
        val jpeg = fakeJpeg()
        val video = fakeMp4()
        val xmp = XmpUtil.buildXmpApp1(XmpUtil.buildGoogleXmpPacket(video.size.toLong()))
        val ins = JpegUtil.findInsertionPoint(jpeg)
        val withXmp = JpegUtil.concat(
            jpeg.copyOfRange(0, ins), xmp, jpeg.copyOfRange(ins, jpeg.size)
        )
        return JpegUtil.concat(withXmp, video)
    }

    private fun String.utf8Bytes(): ByteArray = JpegUtil.utf8(this)

    @Test
    fun detectGoogleMotionPhoto() {
        val bytes = fakeGoogleMotionPhoto()
        val r = MotionPhotoParser(bytes).parse()
        assertEquals(MotionPhotoParser.Format.GOOGLE, r.format)
        assertNotNull(r.videoBytes)
        assertTrue(r.videoBytes!!.isNotEmpty())
        assertEquals("H.264", r.videoCodecHint)
    }

    @Test
    fun googleToOppoLayout() {
        val src = fakeGoogleMotionPhoto()
        val parsed = MotionPhotoParser(src).parse()
        val out = MotionPhotoConverter.toOppoLive2(parsed.coverJpeg, parsed.videoBytes!!)

        // 1) 以 SOI 开头
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0xD8, out[1].toInt() and 0xFF)
        // 2) 含 EXIF UserComment
        assertTrue(JpegUtil.indexOf(out, "Oplus_8388608".utf8Bytes(), 0) > 0)
        // 3) 含 OPPO XMP 字段
        val apps = JpegUtil.scanAppSegments(out)
        val xmpBodies = apps.filter { it.first == 0xE1 && JpegUtil.startsWith(it.second, JpegUtil.utf8(XmpUtil.XMP_NS)) }
        assertTrue(xmpBodies.isNotEmpty())
        val xmpStr = String(xmpBodies[0].second, Charsets.UTF_8)
        assertTrue(xmpStr.contains("OLivePhotoVersion=\"2\""))
        assertTrue(xmpStr.contains("OpCamera:MotionPhotoOwner=\"oplus\""))
        assertTrue(xmpStr.contains("MotionPhoto=\"1\""))
        // 4) 含 MPF APP2
        assertTrue(apps.any { it.first == 0xE2 && JpegUtil.startsWith(it.second, JpegUtil.utf8("MPF\u0000")) })
        // 5) 视频尾插完整保留
        val tail = out.copyOfRange(out.size - parsed.videoBytes!!.size, out.size)
        assertArrayEquals(parsed.videoBytes, tail)
    }

    @Test
    fun oppoDetectedAfterConvert() {
        val src = fakeGoogleMotionPhoto()
        val parsed = MotionPhotoParser(src).parse()
        val oppo = MotionPhotoConverter.toOppoLive2(parsed.coverJpeg, parsed.videoBytes!!)
        val r = MotionPhotoParser(oppo).parse()
        assertEquals(MotionPhotoParser.Format.OPPO, r.format)
        assertArrayEquals(parsed.videoBytes, r.videoBytes)
    }

    @Test
    fun oppoToGoogleRoundTrip() {
        val src = fakeGoogleMotionPhoto()
        val parsed = MotionPhotoParser(src).parse()
        val video = parsed.videoBytes!!
        val oppo = MotionPhotoConverter.toOppoLive2(parsed.coverJpeg, video)
        val oppoParsed = MotionPhotoParser(oppo).parse()
        val video2 = oppoParsed.videoBytes!!

        val google = MotionPhotoConverter.toGoogleMotionPhoto(oppoParsed.coverJpeg, video2)
        // Google XMP：MicroVideoOffset = 视频长度，MicroVideo=1
        val apps = JpegUtil.scanAppSegments(google)
        val xmpBodies = apps.filter { it.first == 0xE1 && JpegUtil.startsWith(it.second, JpegUtil.utf8(XmpUtil.XMP_NS)) }
        assertTrue(xmpBodies.isNotEmpty())
        val xmpStr = String(xmpBodies[0].second, Charsets.UTF_8)
        assertTrue(xmpStr.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(xmpStr.contains("GCamera:MicroVideoOffset=\"${video.size}\""))
        // 视频尾插保留
        val tail = google.copyOfRange(google.size - video.size, google.size)
        assertArrayEquals(video, tail)

        // 再次识别为 Google
        val r = MotionPhotoParser(google).parse()
        assertEquals(MotionPhotoParser.Format.GOOGLE, r.format)
    }

    @Test
    fun plainJpegRejected() {
        val jpeg = fakeJpeg()
        val r = MotionPhotoParser(jpeg).parse()
        assertEquals(MotionPhotoParser.Format.PLAIN_JPEG, r.format)
        assertTrue(r.videoBytes == null)
    }

    /** 三星 Motion Photo：SamsungCamera:MotionPhotoOffset 尾插式布局。 */
    @Test
    fun detectSamsungMotionPhoto() {
        val jpeg = fakeJpeg()
        val video = fakeMp4()
        val xmpPacket = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n" +
                " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                "  <rdf:Description rdf:about=\"\"\n" +
                "    xmlns:SamsungCamera=\"http://ns.samsung.com/photos/1.0/camera/\"\n" +
                "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
                "    SamsungCamera:MotionPhoto=\"1\"\n" +
                "    SamsungCamera:MotionPhotoVersion=\"1\"\n" +
                "    SamsungCamera:MotionPhotoOffset=\"${video.size}\"\n" +
                "    GCamera:MotionPhoto=\"1\"/>\n" +
                " </rdf:RDF>\n" +
                "</x:xmpmeta>\n" +
                "<?xpacket end=\"w\"?>"
        val xmp = XmpUtil.buildXmpApp1(xmpPacket)
        val ins = JpegUtil.findInsertionPoint(jpeg)
        val withXmp = JpegUtil.concat(jpeg.copyOfRange(0, ins), xmp, jpeg.copyOfRange(ins, jpeg.size))
        val bytes = JpegUtil.concat(withXmp, video)

        val r = MotionPhotoParser(bytes).parse()
        assertEquals(MotionPhotoParser.Format.SAMSUNG, r.format)
        assertNotNull(r.videoBytes)
        assertArrayEquals(video, r.videoBytes)

        // 三星样本也应能转换为 OPPO Live2
        val oppo = MotionPhotoConverter.toOppoLive2(r.coverJpeg, r.videoBytes!!)
        assertEquals(MotionPhotoParser.Format.OPPO, MotionPhotoParser(oppo).parse().format)
        assertArrayEquals(video, MotionPhotoParser(oppo).parse().videoBytes)
    }

    /** 端到端：真实 Google 动态图样本（真实 JPEG + H.264 MP4）→ 解析 → 双向转换 → 落盘供外部核验。 */
    @Test
    fun realSampleEndToEnd() {
        val res = java.io.File("src/test/resources/sample_google_motion.jpg")
        assertTrue("样本缺失：$res", res.exists())
        val bytes = res.readBytes()

        val r = MotionPhotoParser(bytes).parse()
        assertEquals(MotionPhotoParser.Format.GOOGLE, r.format)
        val video = r.videoBytes ?: throw AssertionError("未提取到视频")
        // 样本里的视频正是生成的 video.mp4，长度 353935
        assertTrue(video.size > 300000)

        val oppo = MotionPhotoConverter.toOppoLive2(r.coverJpeg, video)
        val google = MotionPhotoConverter.toGoogleMotionPhoto(r.coverJpeg, video)

        val outDir = java.io.File("build/test-output").apply { mkdirs() }
        java.io.File(outDir, "oppo_live.jpg").writeBytes(oppo)
        java.io.File(outDir, "google_motion.jpg").writeBytes(google)

        // 回归断言
        assertEquals(MotionPhotoParser.Format.OPPO, MotionPhotoParser(oppo).parse().format)
        val g2 = MotionPhotoParser(google).parse()
        assertEquals(MotionPhotoParser.Format.GOOGLE, g2.format)
        assertArrayEquals(video, g2.videoBytes)
    }
}
