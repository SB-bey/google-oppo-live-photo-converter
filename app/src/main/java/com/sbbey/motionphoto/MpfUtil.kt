package com.sbbey.motionphoto

/**
 * MPF（Multi-Picture Format）APP2 段：构造（输出 OPPO 格式必需）+ 解析（识别 Google 内嵌视频）。
 * 构造逻辑移植自 web 版 buildMpfSegment（MIT）。
 */
object MpfUtil {

    /** 构造 MPF APP2 段：NumberOfImages=1，Baseline MP Primary。 */
    fun buildMpfSegment(imageSize: Long): ByteArray {
        // TIFF 头（大端），IFD 偏移 8
        val tiff = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
        // 3 个条目：MPFVersion / NumberOfImages / MPEntry
        val entries = java.io.ByteArrayOutputStream()
        // 0xB000 MPFVersion, UNDEFINED(7), count=4, value="0100"
        entries.write(0xB0); entries.write(0x00); entries.write(0x00); entries.write(0x07)
        entries.write(0); entries.write(0); entries.write(0); entries.write(4)
        entries.write(0x30); entries.write(0x31); entries.write(0x30); entries.write(0x30)
        // 0xB001 NumberOfImages, LONG(4), count=1, value=1
        entries.write(0xB0); entries.write(0x01); entries.write(0x00); entries.write(0x04)
        entries.write(0); entries.write(0); entries.write(0); entries.write(1)
        entries.write(0); entries.write(0); entries.write(0); entries.write(1)
        // 0xB002 MPEntry, UNDEFINED(7), count=16, value=偏移
        val mpEntryOffset = 8 + 2 + 3 * 12 + 4
        entries.write(0xB0); entries.write(0x02); entries.write(0x00); entries.write(0x07)
        entries.write(0); entries.write(0); entries.write(0); entries.write(16)
        entries.write((mpEntryOffset shr 24) and 0xFF)
        entries.write((mpEntryOffset shr 16) and 0xFF)
        entries.write((mpEntryOffset shr 8) and 0xFF)
        entries.write(mpEntryOffset and 0xFF)

        val ifd = java.io.ByteArrayOutputStream()
        ifd.write(0); ifd.write(3) // 3 个条目（大端）
        ifd.write(entries.toByteArray())
        ifd.write(0); ifd.write(0); ifd.write(0); ifd.write(0) // next IFD = 0

        // MP 条目数据：图像属性(Baseline Primary) + 尺寸 + 8 字节零
        val mpEntry = ByteArray(16)
        mpEntry[0] = 0x00; mpEntry[1] = 0x03; mpEntry[2] = 0x00; mpEntry[3] = 0x00
        var v = imageSize
        for (k in 7 downTo 4) { mpEntry[k] = (v and 0xFF).toByte(); v = v shr 8 }

        val body = JpegUtil.concat(JpegUtil.utf8("MPF\u0000"), tiff, ifd.toByteArray(), mpEntry)
        val segLen = body.size + 2
        return JpegUtil.concat(byteArrayOf(0xFF.toByte(), 0xE2.toByte()), JpegUtil.u16be(segLen), body)
    }

    data class MpEntry(val attribute: Long, val size: Long, val offset: Long)

    /** 解析 MPF APP2 段（TIFF/IFD 遍历），返回 MP 条目列表。 */
    fun parseMpfSegments(app2Bodies: List<ByteArray>): List<MpEntry> {
        val out = mutableListOf<MpEntry>()
        for (body in app2Bodies) {
            if (body.size < 8 || body[0] != 'M'.code.toByte() || body[1] != 'P'.code.toByte() ||
                body[2] != 'F'.code.toByte() || body[3] != 0x00.toByte()) continue
            if (body[4] != 0x4D.toByte() || body[5] != 0x4D.toByte()) continue // 必须 MM(大端)
            if (body.size < 8 + 2 + 2) continue
            val ifdCount = JpegUtil.readU16BE(body, 8)
            if (ifdCount <= 0 || ifdCount > 64) continue
            var off = 10
            var mpEntryOffset = -1L
            var mpEntryCount = 0L
            for (e in 0 until ifdCount) {
                if (off + 12 > body.size) break
                val tag = JpegUtil.readU16BE(body, off)
                val type = JpegUtil.readU16BE(body, off + 2)
                val count = JpegUtil.readU32BE(body, off + 4)
                val value = JpegUtil.readU32BE(body, off + 8)
                if (tag == 0xB001) { // NumberOfImages
                    mpEntryCount = if (type == 4) value else count
                } else if (tag == 0xB002 && type == 7) { // MPEntry
                    mpEntryOffset = value
                }
                off += 12
            }
            if (mpEntryOffset <= 0) continue
            val start = mpEntryOffset.toInt()
            if (start < 0 || start + 16 * mpEntryCount > body.size) continue
            for (k in 0 until mpEntryCount.toInt()) {
                val base = start + 16 * k
                if (base + 16 > body.size) break
                val attr = JpegUtil.readU32BE(body, base)
                val size = JpegUtil.readU32BE(body, base + 4)
                val offset = JpegUtil.readU32BE(body, base + 8)
                out.add(MpEntry(attr, size, offset))
            }
        }
        return out
    }
}
