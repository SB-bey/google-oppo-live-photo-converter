package com.sbbey.motionphoto

/**
 * XMP 包构造 + 解析。
 * OPPO 输出包的模板与字段完全移植自 web 版 buildXmpPacket（MIT）。
 */
object XmpUtil {

    val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    /** 构造 OPPO Live2 所需 XMP 包（GCamera + OpCamera + Container Directory）。 */
    fun buildOppoXmpPacket(videoLength: Long, tsUs: Long = 0L): String {
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"google-oppo-live-photo-converter\">\n" +
                " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                "  <rdf:Description rdf:about=\"\"\n" +
                "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
                "    xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"\n" +
                "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
                "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
                "    GCamera:MotionPhoto=\"1\"\n" +
                "    GCamera:MotionPhotoVersion=\"1\"\n" +
                "    GCamera:MotionPhotoPresentationTimestampUs=\"$tsUs\"\n" +
                "    OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"$tsUs\"\n" +
                "    OpCamera:MotionPhotoOwner=\"oplus\"\n" +
                "    OpCamera:OLivePhotoVersion=\"2\"\n" +
                "    OpCamera:VideoLength=\"$videoLength\"\n" +
                "    OpCamera:MotionPhotoFeatureFlag=\"1\">\n" +
                "   <Container:Directory>\n" +
                "    <rdf:Seq>\n" +
                "     <rdf:li rdf:parseType=\"Resource\">\n" +
                "      <Container:Item rdf:parseType=\"Resource\">\n" +
                "       <Item:Mime>image/jpeg</Item:Mime>\n" +
                "       <Item:Semantic>Primary</Item:Semantic>\n" +
                "       <Item:Length>0</Item:Length>\n" +
                "       <Item:Padding>0</Item:Padding>\n" +
                "      </Container:Item>\n" +
                "     </rdf:li>\n" +
                "     <rdf:li rdf:parseType=\"Resource\">\n" +
                "      <Container:Item rdf:parseType=\"Resource\">\n" +
                "       <Item:Mime>video/mp4</Item:Mime>\n" +
                "       <Item:Semantic>MotionPhoto</Item:Semantic>\n" +
                "       <Item:Length>$videoLength</Item:Length>\n" +
                "       <Item:Padding>0</Item:Padding>\n" +
                "      </Container:Item>\n" +
                "     </rdf:li>\n" +
                "    </rdf:Seq>\n" +
                "   </Container:Directory>\n" +
                "  </rdf:Description>\n" +
                " </rdf:RDF>\n" +
                "</x:xmpmeta>\n" +
                "<?xpacket end=\"w\"?>"
    }

    /** 构造 Google Motion Photo（MicroVideo 尾插式）XMP 包。 */
    fun buildGoogleXmpPacket(videoLength: Long, tsUs: Long = 0L): String {
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"google-oppo-live-photo-converter\">\n" +
                " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
                "  <rdf:Description rdf:about=\"\"\n" +
                "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
                "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
                "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
                "    GCamera:MotionPhoto=\"1\"\n" +
                "    GCamera:MicroVideo=\"1\"\n" +
                "    GCamera:MicroVideoVersion=\"1\"\n" +
                "    GCamera:MicroVideoOffset=\"$videoLength\">\n" +
                "   <Container:Directory>\n" +
                "    <rdf:Seq>\n" +
                "     <rdf:li rdf:parseType=\"Resource\">\n" +
                "      <Container:Item rdf:parseType=\"Resource\">\n" +
                "       <Item:Mime>image/jpeg</Item:Mime>\n" +
                "       <Item:Semantic>Primary</Item:Semantic>\n" +
                "       <Item:Length>0</Item:Length>\n" +
                "       <Item:Padding>0</Item:Padding>\n" +
                "      </Container:Item>\n" +
                "     </rdf:li>\n" +
                "     <rdf:li rdf:parseType=\"Resource\">\n" +
                "      <Container:Item rdf:parseType=\"Resource\">\n" +
                "       <Item:Mime>video/mp4</Item:Mime>\n" +
                "       <Item:Semantic>MotionPhoto</Item:Semantic>\n" +
                "       <Item:Length>$videoLength</Item:Length>\n" +
                "       <Item:Padding>0</Item:Padding>\n" +
                "      </Container:Item>\n" +
                "     </rdf:li>\n" +
                "    </rdf:Seq>\n" +
                "   </Container:Directory>\n" +
                "  </rdf:Description>\n" +
                " </rdf:RDF>\n" +
                "</x:xmpmeta>\n" +
                "<?xpacket end=\"w\"?>"
    }

    /** 构造 XMP 的 APP1 段（含 XMP 命名空间头）。 */
    fun buildXmpApp1(xmp: String): ByteArray {
        val body = JpegUtil.concat(JpegUtil.utf8(XMP_NS), JpegUtil.utf8(xmp))
        if (body.size > 65533) throw IllegalArgumentException("XMP 包超过 APP1 上限")
        val segLen = body.size + 2
        return JpegUtil.concat(byteArrayOf(0xFF.toByte(), 0xE1.toByte()), JpegUtil.u16be(segLen), body)
    }

    /** 从 XMP 文本提取形如 Name="value" 的属性。 */
    fun attr(xmp: String, name: String): String? {
        val re = Regex("""$name\s*=\s*"([^"]*)"""")
        return re.find(xmp)?.groupValues?.get(1)
    }

    /** 从 XMP 文本提取 Container Directory 中 Semantic 与 Item:Length 的配对。 */
    fun containerItems(xmp: String): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        val itemRe = Regex(
            """<Container:Item\s+rdf:parseType="Resource">.*?<Item:Semantic>([^<]+)</Item:Semantic>.*?<Item:Length>(\d+)</Item:Length>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in itemRe.findAll(xmp)) {
            out.add(m.groupValues[1] to (m.groupValues[2].toLongOrNull() ?: 0L))
        }
        return out
    }
}
