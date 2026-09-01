package com.musicplayer.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object ImageUtils {

    fun loadBitmapFromPath(path: String?, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null

        return try {
            if (targetWidth > 0 && targetHeight > 0) {
                // 采样率加载
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, options)

                options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(path, options)
            } else {
                BitmapFactory.decodeFile(path)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadBitmapFromBytes(bytes: ByteArray?, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap? {
        if (bytes == null) return null
        return try {
            if (targetWidth > 0 && targetHeight > 0) {
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
