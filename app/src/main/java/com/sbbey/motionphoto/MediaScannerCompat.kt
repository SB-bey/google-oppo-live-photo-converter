package com.sbbey.motionphoto

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File

/** API 26-28 下把文件登记进媒体库。 */
object MediaScannerCompat {
    fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    }
}
