package minty.emanata.reader

import android.graphics.Bitmap

/**
 * What [Avif] needs of an AVIF decoder: open a file (all of it, or only its start),
 * step through its images, and write the current one into a bitmap. A handle holds
 * one open file until [close]. On the device this is [LibAvif]; JVM tests stand in
 * their own, since they cannot load native code.
 */
internal interface AvifCodec {
    /**
     * Parses the container of the first [length] bytes of [data], the whole file when
     * [complete]. With [layers], a layered image is stepped through layer by layer.
     * Returns 0 when the bytes of an incomplete file end before its metadata does.
     */
    fun open(data: ByteArray, length: Int, complete: Boolean, threads: Int, layers: Boolean): Long

    /** What is known of the current image ([AvifInfo]). */
    fun info(handle: Long): IntArray

    /** Decodes the next image or layer; false when the bytes given end before it. */
    fun next(handle: Long): Boolean

    /**
     * Writes the current image into [bitmap], an ARGB_8888 bitmap of its size divided by
     * [sample] (rounded up), keeping every [sample]-th pixel of every [sample]-th row.
     */
    fun render(handle: Long, bitmap: Bitmap, sample: Int, threads: Int)

    fun close(handle: Long)
}

/** libavif with the dav1d AV1 decoder, built from source (`src/main/cpp`), through JNI. */
internal object LibAvif : AvifCodec {
    init {
        System.loadLibrary("avifjni")
    }

    override fun open(data: ByteArray, length: Int, complete: Boolean, threads: Int, layers: Boolean): Long =
        nOpen(data, length, complete, threads, layers)

    override fun info(handle: Long): IntArray = nInfo(handle)

    override fun next(handle: Long): Boolean = nNext(handle)

    override fun render(handle: Long, bitmap: Bitmap, sample: Int, threads: Int) = nRender(handle, bitmap, sample, threads)

    override fun close(handle: Long) = nClose(handle)

    @JvmStatic
    private external fun nOpen(data: ByteArray, length: Int, complete: Boolean, threads: Int, layers: Boolean): Long

    @JvmStatic
    private external fun nInfo(handle: Long): IntArray

    @JvmStatic
    private external fun nNext(handle: Long): Boolean

    @JvmStatic
    private external fun nRender(handle: Long, bitmap: Bitmap, sample: Int, threads: Int)

    @JvmStatic
    private external fun nClose(handle: Long)
}
