package minty.emanata.compact

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import minty.emanata.reader.Avif
import minty.emanata.reader.ImageKind
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The platform's image codecs ([BitmapFactory], [Bitmap.compress]), and libavif ([Avif]), for compaction. */
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
        if (maxOf(pixels.width, pixels.height) > PageWork.WEBP_MAX_SIDE) return null
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            lossless -> return null
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        return compress(pixels, format, quality)
    }

    override fun jpeg(pixels: Pixels, quality: Int): ByteArray? {
        require(pixels.channels == 4 && pixels.bits == 8)
        if (maxOf(pixels.width, pixels.height) > PageWork.JPEG_MAX_SIDE) return null
        return compress(pixels, Bitmap.CompressFormat.JPEG, quality)
    }

    private fun compress(pixels: Pixels, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        try {
            // Samples as they are: copyPixelsFromBuffer converts nothing.
            bitmap.isPremultiplied = false
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels.data))
            bitmap.setHasAlpha(PageWork.hasAlpha(pixels))
            val out = ByteArrayOutputStream(pixels.data.size / 4)
            return if (bitmap.compress(format, quality, out)) out.toByteArray() else null
        } finally {
            bitmap.recycle()
        }
    }

    override fun isoPixels(page: ByteArray): Pixels? {
        val decoded = when (ImageKind.sniff(page)) {
            ImageKind.AVIF -> runCatching { Avif.decode(page, Avif.MAX_PIXELS) }.getOrNull()
            ImageKind.HEIF -> BitmapFactory.decodeByteArray(page, 0, page.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inScaled = false
            })
            else -> null
        } ?: return null
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() } ?: return null
        try {
            val w = bitmap.width
            val out = ByteArray(w * bitmap.height * 4)
            // Row by row, as sRGB colors not premultiplied.
            val row = IntArray(w)
            var o = 0
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (c in row) {
                    out[o++] = (c shr 16).toByte()
                    out[o++] = (c shr 8).toByte()
                    out[o++] = c.toByte()
                    out[o++] = (c ushr 24).toByte()
                }
            }
            return Pixels(w, bitmap.height, 4, 8, out)
        } finally {
            bitmap.recycle()
        }
    }
}
