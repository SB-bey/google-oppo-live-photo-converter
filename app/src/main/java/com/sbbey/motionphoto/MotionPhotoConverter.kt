package com.sbbey.motionphoto

/**
 * 转换器：把解析出的封面 JPEG + 视频字节 重封装为目标格式。
 *
 *  - toOppoLive2()：封面(剥掉 APP1/APP2) + 新 EXIF(UserComment=Oplus_8388608) + XMP(GCamera/OpCamera/Container) + MPF + 追加视频
 *  - toGoogleMotionPhoto()：封面(保留 EXIF，剥 XMP/MPF) + XMP(GCamera MicroVideo + MicroVideoOffset) + 追加视频
 *
 * OPPO 路径逐字节镜像 web 版 buildOppoMotionPhoto（MIT），已在 ColorOS 14+ 真机验证过该字节布局。
 */
object MotionPhotoConverter {

    /** 输出 OPPO Live2（实况图片）字节流。 */
    fun toOppoLive2(coverJpeg: ByteArray, videoBytes: ByteArray, tsUs: Long = 0L): ByteArray {
        if (videoBytes.isEmpty()) throw IllegalArgumentException("没有检测到内嵌视频，无法转换")
        val clean = JpegUtil.stripApp1App2(coverJpeg)
        val exif = ExifUtil.buildExifApp1()
        val xmp = XmpUtil.buildXmpApp1(XmpUtil.buildOppoXmpPacket(videoBytes.size.toLong(), tsUs))

        // SOI 后先插 EXIF + XMP
        val insAfterSoi = JpegUtil.findInsertionPoint(clean)
        val withMeta = JpegUtil.concat(
            clean.copyOfRange(0, insAfterSoi),
            exif,
            xmp,
            clean.copyOfRange(insAfterSoi, clean.size)
        )

        // MPF 长度恒定，可先按占位尺寸定长，再按最终 JPEG 尺寸填充
        val dummyMpf = MpfUtil.buildMpfSegment(0)
        val finalSize = withMeta.size.toLong() + dummyMpf.size
        val mpf = MpfUtil.buildMpfSegment(finalSize)
        if (mpf.size != dummyMpf.size) throw IllegalStateException("MPF 段尺寸在构建时发生偏移")

        val insAfterMeta = JpegUtil.findInsertionPoint(withMeta)
        val finalJpeg = JpegUtil.concat(
            withMeta.copyOfRange(0, insAfterMeta),
            mpf,
            withMeta.copyOfRange(insAfterMeta, withMeta.size)
        )

        return JpegUtil.concat(finalJpeg, videoBytes)
    }

    /** 输出 Google Motion Photo（MicroVideo 尾插式）字节流。 */
    fun toGoogleMotionPhoto(coverJpeg: ByteArray, videoBytes: ByteArray, tsUs: Long = 0L): ByteArray {
        if (videoBytes.isEmpty()) throw IllegalArgumentException("没有检测到内嵌视频，无法转换")
        val clean = JpegUtil.stripXmpAndMpf(coverJpeg)
        // MicroVideoOffset = 追加的 MP4 长度（尾插式布局下与 XMP 尺寸无关）
        val xmp = XmpUtil.buildXmpApp1(XmpUtil.buildGoogleXmpPacket(videoBytes.size.toLong(), tsUs))

        val insAfterSoi = JpegUtil.findInsertionPoint(clean)
        val withMeta = JpegUtil.concat(
            clean.copyOfRange(0, insAfterSoi),
            xmp,
            clean.copyOfRange(insAfterSoi, clean.size)
        )
        return JpegUtil.concat(withMeta, videoBytes)
    }
}
