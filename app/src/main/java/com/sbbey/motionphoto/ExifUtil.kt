package com.sbbey.motionphoto

/**
 * 最小 EXIF APP1 段构造：仅含 UserComment = "Oplus_8388608"（ASCII）。
 * OPPO 相册识别实况图必需。逻辑移植自 web 版 buildExifApp1（MIT）。
 */
object ExifUtil {

    private class IfdEntry(val tag: Int, val type: Int, val count: Int, val valueBytes: ByteArray)

    fun buildExifApp1(): ByteArray {
        val ASCII_PREFIX = JpegUtil.utf8("ASCII\u0000\u0000\u0000")
        val COMMENT = JpegUtil.utf8("Oplus_8388608")
        val userCommentValue = JpegUtil.concat(ASCII_PREFIX, COMMENT)

        // TIFF 头（小端），IFD 偏移 8
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)

        // 布局偏移（从 TIFF 头起算）：
        //   0..7   TIFF 头
        //   8..    IFD0（2 计数 + 1 条 12 字节 + 4 next = 18 字节）
        //   26..   Exif IFD（2 计数 + 1 条 12 字节 + 4 next = 18 字节）
        //   44..   UserComment 数据
        val EXIF_IFD_OFFSET = 8 + 2 + 12 + 4 // = 26
        val userCommentDataOffset = EXIF_IFD_OFFSET + 2 + 12 + 4 // = 44

        fun u32le(n: Int): ByteArray = JpegUtil.u32le(n)

        fun ifdEntries(entries: List<IfdEntry>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(entries.size and 0xFF); out.write((entries.size shr 8) and 0xFF)
            for (e in entries) {
                out.write(e.tag and 0xFF); out.write((e.tag shr 8) and 0xFF)
                out.write(e.type and 0xFF); out.write((e.type shr 8) and 0xFF)
                val c = e.count
                out.write(c and 0xFF); out.write((c shr 8) and 0xFF)
                out.write((c shr 16) and 0xFF); out.write((c shr 24) and 0xFF)
                for (k in 0 until 4) out.write(if (k < e.valueBytes.size) e.valueBytes[k].toInt() else 0)
            }
            out.write(0); out.write(0); out.write(0); out.write(0) // next IFD = 0
            return out.toByteArray()
        }

        // IFD0 条目：ExifOffset(0x8769), LONG(4), count=1, value=Exif IFD 偏移
        val ifd0 = ifdEntries(listOf(IfdEntry(0x8769, 4, 1, u32le(EXIF_IFD_OFFSET))))
        // Exif IFD 条目：UserComment(0x9286), UNDEFINED(7), count=字节数, value=数据块偏移
        val exifIfd = ifdEntries(listOf(IfdEntry(0x9286, 7, userCommentValue.size, u32le(userCommentDataOffset))))

        val tiffBlock = JpegUtil.concat(tiff, ifd0, exifIfd, userCommentValue)

        // APP1 段：标记 + 长度 + "Exif\0\0" + TIFF 块（段长度字段是大端）
        val exifHeader = JpegUtil.utf8("Exif\u0000\u0000")
        val segBody = JpegUtil.concat(exifHeader, tiffBlock)
        val segLen = segBody.size + 2
        return JpegUtil.concat(byteArrayOf(0xFF.toByte(), 0xE1.toByte()), JpegUtil.u16be(segLen), segBody)
    }
}
