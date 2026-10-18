package com.sbbey.motionphoto

/**
 * 动态图片解析器：识别 Google Motion Photo / OPPO Live2 / 普通图片，提取内嵌视频与封面 JPEG。
 */
class MotionPhotoParser(private val fileBytes: ByteArray) {

    enum class Format { GOOGLE, OPPO, GENERIC_MOTION, PLAIN_JPEG, UNKNOWN }

    data class ParseResult(
        val format: Format,
        val coverJpeg: ByteArray,
        val videoBytes: ByteArray?,
        val videoStart: Int,
        val videoCodecHint: String?  // "H.264" / "HEVC" / null
    )

    private val XMP_NS_BYTES = JpegUtil.utf8(XmpUtil.XMP_NS)

    fun parse(): ParseResult {
        val appSegs = JpegUtil.scanAppSegments(fileBytes)
        val xmpBodies = appSegs.filter { it.first == 0xE1 && JpegUtil.startsWith(it.second, XMP_NS_BYTES) }
            .map { it.second }
        var xmp: String? = null
        for (b in xmpBodies) {
            val s = String(b, Charsets.UTF_8)
            if (s.contains("MotionPhoto") || s.contains("MicroVideo")) {
                xmp = s
                break
            }
        }
        val mpfEntries = MpfUtil.parseMpfSegments(appSegs.filter { it.first == 0xE2 }.map { it.second })

        val isOppo = xmp?.let { XmpUtil.attr(it, "OpCamera:OLivePhotoVersion") != null || XmpUtil.attr(it, "OpCamera:MotionPhotoOwner") != null } == true
        val isGoogle = xmp?.let {
            XmpUtil.attr(it, "GCamera:MicroVideo") == "1" ||
                    (XmpUtil.attr(it, "GCamera:MotionPhoto") == "1" && XmpUtil.attr(it, "GCamera:MicroVideoOffset") != null)
        } == true

        val n = fileBytes.size

        // 1) 视频起点判定优先级：MicroVideoOffset > Container:Directory MotionPhoto > OpCamera:VideoLength > MPF 条目 > ftyp 扫描
        var videoStart = -1
        var videoLen = -1L
        if (xmp != null) {
            XmpUtil.attr(xmp, "GCamera:MicroVideoOffset")?.toLongOrNull()?.let { off ->
                if (off in 1..n.toLong()) { videoStart = n - off.toInt(); videoLen = off }
            }
        }
        if (videoStart < 0 && xmp != null) {
            for ((semantic, len) in XmpUtil.containerItems(xmp)) {
                if (semantic == "MotionPhoto" && len in 1..n.toLong()) {
                    videoStart = n - len.toInt(); videoLen = len
                    break
                }
            }
        }
        if (videoStart < 0 && xmp != null) {
            XmpUtil.attr(xmp, "OpCamera:VideoLength")?.toLongOrNull()?.let { len ->
                if (len in 1..n.toLong()) { videoStart = n - len.toInt(); videoLen = len }
            }
        }
        if (videoStart < 0 && mpfEntries.size >= 2) {
            // 第二个条目通常是视频（Google MPF 式）；优先选 Dependent 属性条目
            val videoEntry = mpfEntries.drop(1).firstOrNull { (it.attribute and 0x00020000L) != 0L }
                ?: mpfEntries[1]
            if (videoEntry.offset in 1..n.toLong() && videoEntry.size > 0) {
                videoStart = videoEntry.offset.toInt()
                videoLen = videoEntry.size
            }
        }
        if (videoStart < 0) {
            // ftyp 扫描：找最后一个合法 MP4 盒子起点（ftyp 前的 4 字节是该盒子大小）
            val ftyp = JpegUtil.utf8("ftyp")
            var lastFtyp = -1
            var from = 0
            while (true) {
                val idx = JpegUtil.indexOf(fileBytes, ftyp, from)
                if (idx < 0) break
                lastFtyp = idx
                from = idx + 4
            }
            if (lastFtyp >= 4) {
                val boxStart = lastFtyp - 4
                val boxSize = JpegUtil.readU32BE(fileBytes, boxStart)
                if (boxSize >= 8 && boxStart + boxSize <= n) {
                    videoStart = boxStart
                    videoLen = n - boxStart.toLong()
                }
            }
        }

        val format = when {
            isOppo -> Format.OPPO
            isGoogle -> Format.GOOGLE
            videoStart > 0 -> Format.GENERIC_MOTION
            n >= 2 && fileBytes[0] == 0xFF.toByte() && fileBytes[1] == 0xD8.toByte() -> Format.PLAIN_JPEG
            else -> Format.UNKNOWN
        }

        if (videoStart <= 0 || videoStart >= n) {
            return ParseResult(format, fileBytes, null, 0, null)
        }

        val cover = fileBytes.copyOfRange(0, videoStart)
        val video = fileBytes.copyOfRange(videoStart, n)

        // 视频编码提示：在视频字节里搜 sample entry fourcc
        val probe = if (video.size > 1 shl 20) video.copyOfRange(0, 1 shl 20) else video
        val probeStr = String(probe, Charsets.ISO_8859_1)
        val codecHint = when {
            probeStr.contains("hvc1") || probeStr.contains("hev1") -> "HEVC"
            probeStr.contains("avc1") || probeStr.contains("avc3") -> "H.264"
            else -> null
        }

        return ParseResult(format, cover, video, videoStart, codecHint)
    }
}
