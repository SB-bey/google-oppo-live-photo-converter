package com.sbbey.motionphoto

import java.nio.charset.StandardCharsets

/**
 * 字节工具 + JPEG 段扫描器。
 * 逻辑移植自 oppo-live-photo-maker（MIT）的 muxer.ts / muxer.py。
 */
object JpegUtil {

    fun u16be(n: Int): ByteArray =
        byteArrayOf(((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())

    fun u32be(n: Long): ByteArray =
        byteArrayOf(
            ((n shr 24) and 0xFF).toByte(), ((n shr 16) and 0xFF).toByte(),
            ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte()
        )

    fun u32le(n: Int): ByteArray =
        byteArrayOf(
            (n and 0xFF).toByte(), ((n shr 8) and 0xFF).toByte(),
            ((n shr 16) and 0xFF).toByte(), ((n shr 24) and 0xFF).toByte()
        )

    fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var i = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, i, p.size)
            i += p.size
        }
        return out
    }

    /** 在 haystack 中从 from 开始查找 needle，找不到返回 -1。 */
    fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from.coerceIn(0, haystack.size)
        var i = from.coerceAtLeast(0)
        val max = haystack.size - needle.size
        outer@ while (i <= max) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { i++; continue@outer }
            }
            return i
        }
        return -1
    }

    fun readU16BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    fun readU32BE(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    fun startsWith(b: ByteArray, prefix: ByteArray): Boolean {
        if (b.size < prefix.size) return false
        for (i in prefix.indices) if (b[i] != prefix[i]) return false
        return true
    }

    /**
     * 找到安全插入 APPn 段的位置：紧跟 SOI 后所有 APPn 之后、第一个 DQT/DHT/SOF/SOS 之前。
     * 镜像 Python 版 _find_app_insertion_point。
     */
    fun findInsertionPoint(jpeg: ByteArray): Int {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("不是 JPEG：缺少 SOI 标记")
        }
        var i = 2
        var lastAppEnd = 2
        val n = jpeg.size
        while (i < n - 1) {
            while (i < n && jpeg[i] == 0xFF.toByte() && i + 1 < n && jpeg[i + 1] == 0xFF.toByte()) i++
            if (i >= n - 1 || jpeg[i] != 0xFF.toByte()) return lastAppEnd
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return lastAppEnd          // SOS / EOI
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return lastAppEnd // SOF
            if (marker == 0xDB || marker == 0xC4) return lastAppEnd          // DQT / DHT
            if (marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue } // TEM / RSTn
            if (i + 4 > n) return lastAppEnd
            val segLen = readU16BE(jpeg, i + 2)
            if (segLen < 2 || i + 2 + segLen > n) return lastAppEnd
            i += 2 + segLen
            lastAppEnd = i
        }
        return lastAppEnd
    }

    /**
     * 剥掉现有 APP1（EXIF/XMP）与 APP2（MPF/FlashPix）段，其余原样保留。
     * 镜像 web 版 stripExistingMetadata。返回全新数组。
     */
    fun stripApp1App2(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("不是 JPEG：缺少 SOI 标记")
        }
        val out = java.io.ByteArrayOutputStream(jpeg.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        val n = jpeg.size
        while (i < n - 1) {
            while (i < n && jpeg[i] == 0xFF.toByte() && i + 1 < n && jpeg[i + 1] == 0xFF.toByte()) i++
            if (i >= n - 1 || jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) {
                out.write(jpeg, i, n - i)
                return out.toByteArray()
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(0xFF); out.write(marker); i += 2; continue
            }
            if (i + 4 > n) break
            val segLen = readU16BE(jpeg, i + 2)
            if (segLen < 2 || i + 2 + segLen > n) break
            if (marker != 0xE1 && marker != 0xE2) {
                out.write(jpeg, i, 2 + segLen)
            }
            i += 2 + segLen
        }
        return jpeg
    }

    /** 只剥 APP2(MPF) 和 XMP 的 APP1，保留 EXIF APP1 与其他段（用于输出 Google 格式时保 EXIF）。 */
    fun stripXmpAndMpf(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            throw IllegalArgumentException("不是 JPEG：缺少 SOI 标记")
        }
        val XMP_NS = utf8("http://ns.adobe.com/xap/1.0/\u0000")
        val out = java.io.ByteArrayOutputStream(jpeg.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        val n = jpeg.size
        while (i < n - 1) {
            while (i < n && jpeg[i] == 0xFF.toByte() && i + 1 < n && jpeg[i + 1] == 0xFF.toByte()) i++
            if (i >= n - 1 || jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) {
                out.write(jpeg, i, n - i)
                return out.toByteArray()
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(0xFF); out.write(marker); i += 2; continue
            }
            if (i + 4 > n) break
            val segLen = readU16BE(jpeg, i + 2)
            if (segLen < 2 || i + 2 + segLen > n) break
            val body = jpeg.copyOfRange(i + 4, i + 2 + segLen)
            val drop = when {
                marker == 0xE2 -> true                     // MPF / FlashPix
                marker == 0xE1 && startsWith(body, XMP_NS) -> true
                else -> false
            }
            if (!drop) out.write(jpeg, i, 2 + segLen)
            i += 2 + segLen
        }
        return jpeg
    }

    /** 扫描所有 APP1 / APP2 段，返回 (marker, segmentBody) 列表。 */
    fun scanAppSegments(jpeg: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return result
        var i = 2
        val n = jpeg.size
        while (i < n - 1) {
            while (i < n && jpeg[i] == 0xFF.toByte() && i + 1 < n && jpeg[i + 1] == 0xFF.toByte()) i++
            if (i >= n - 1 || jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            if (marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            if (i + 4 > n) break
            val segLen = readU16BE(jpeg, i + 2)
            if (segLen < 2 || i + 2 + segLen > n) break
            if (marker == 0xE1 || marker == 0xE2) {
                result.add(marker to jpeg.copyOfRange(i + 4, i + 2 + segLen))
            }
            i += 2 + segLen
        }
        return result
    }
}
