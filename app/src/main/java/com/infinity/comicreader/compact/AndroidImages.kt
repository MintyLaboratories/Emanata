package com.infinity.comicreader.compact

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The platform's image codecs ([BitmapFactory], [Bitmap.compress]) for compaction. */
object AndroidImages : PlatformImages {
    override fun decode(bytes: ByteArray): Pixels? {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            if (bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.rowBytes != bitmap.width * 4) return null
            // Pixels in another profile would be stored as sRGB.
            val space = bitmap.colorSpace
            if (space != null && space != ColorSpace.get(ColorSpace.Named.SRGB)) return null
            val data = ByteArray(bitmap.width * bitmap.height * 4)
            bitmap.copyPixelsToBuffer(ByteBuffer.wrap(data))
            return Pixels(bitmap.width, bitmap.height, 4, 8, data)
        } finally {
            bitmap.recycle()
        }
    }

    override fun webp(pixels: Pixels, lossless: Boolean, quality: Int): ByteArray? {
        require(pixels.channels == 4 && pixels.bits == 8)
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            lossless -> return null
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            // Samples as they are: copyPixelsFromBuffer converts nothing.
            bitmap.isPremultiplied = false
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels.data))
            bitmap.setHasAlpha(hasAlpha(pixels.data))
            val out = ByteArrayOutputStream(pixels.data.size / 4)
            return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasAlpha(rgba: ByteArray): Boolean {
        var i = 3
        while (i < rgba.size) {
            if (rgba[i] != (-1).toByte()) return true
            i += 4
        }
        return false
    }
}
