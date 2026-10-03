package com.srrtracker.data

import android.content.Context
import java.io.File

class PhotoStore(context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }

    fun save(jpeg: ByteArray, ts: Long): String {
        val file = File(dir, "roll-$ts.jpg")
        file.writeBytes(jpeg)
        return file.absolutePath
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        File(path).delete()
    }
}
