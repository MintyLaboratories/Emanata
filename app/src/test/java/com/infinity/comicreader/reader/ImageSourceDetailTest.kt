package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** A loose image's detail reads a rectangle at a time where its page is drawn in bands, on a share too. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSourceDetailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var share: File
    private val opened = ArrayList<IntArray>()
    private val platform = Decoders.openRegions
    private val rebuild = Decoders.jpegOf

    @Before
    fun setUp() {
        share = tmp.newFolder("share")
        val fake = FakeRemoteFs(share)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
        // Robolectric has no region decoder: crops of the whole decode stand in.
        Decoders.openRegions = { bytes ->
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            opened += intArrayOf(image.width, image.height)
            object : RegionReader {
                override val width = image.width
                override val height = image.height
                override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap =
                    Bitmap.createBitmap(image, left, top, right - left, bottom - top)

                override fun close() = image.recycle()
            }
        }
    }

    @After
    fun tearDown() {
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
        Decoders.openRegions = platform
        Decoders.jpegOf = rebuild
    }

    /** A page 100 wide and 20,000 tall, far taller than one bitmap may be, as a PNG. */
    private fun tallPng(): ByteArray {
        val w = 100
        val h = 20_000
        val pixels = IntArray(w * h) { i -> (0xFF shl 24) or ((i / w) % 256 shl 8) or (i % w) }
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private val banded = Target(100, 0, banded = true)

    private fun checkRegions(source: ImageSource) {
        val detail = source.loadDetail(0, banded, Long.MAX_VALUE)
        assertTrue(detail is RegionDetail)
        assertEquals(listOf(100 to 20_000), opened.map { it[0] to it[1] })
        val band = detail.render(Region(0.0, 0.5, 1.0, 0.01), 100, 200, false)
        assertEquals(Rect(0, 0, 100, 200), Rect(0, 0, band.width, band.height))
        detail.release()
    }

    @Test
    fun aLocalImageReadsRegions() {
        checkRegions(ImageSource(File(tmp.root, "p.png").apply { writeBytes(tallPng()) }))
    }

    @Test
    fun anImageOnAShareReadsRegions() {
        File(share, "p.png").writeBytes(tallPng())
        checkRegions(ImageSource(Remotes.file("/net/s1/p.png") as RemoteFile))
    }

    /** Blank reads of an image stored [w] x [h]. */
    private fun blank(w: Int, h: Int) = object : RegionReader {
        override val width = w
        override val height = h
        override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap =
            Bitmap.createBitmap((right - left) / sample, (bottom - top) / sample, Bitmap.Config.ARGB_8888)

        override fun close() = Unit
    }

    @Test
    fun aJpegTurnedByExifReadsRegionsAsShown() {
        // Stored 20,000 x 100, shown turned a quarter: 100 x 20,000.
        Decoders.openRegions = { blank(20_000, 100) }
        val detail = Decoders.detail(TestImages.jpegHead(20_000, 100, orientation = 6), banded, Long.MAX_VALUE)
        assertTrue(detail is RegionDetail)
        val band = detail.render(Region(0.0, 0.5, 1.0, 0.01), 100, 200, false)
        assertEquals(100, band.width)
        assertEquals(200, band.height)
    }

    /** The start of a JPEG XL codestream [w] x [h], [w] and [h] below 512 and 262,144: its header alone. */
    private fun jxlHead(w: Int, h: Int): ByteArray {
        val bits = ArrayList<Boolean>()
        fun u(value: Int, n: Int) { for (k in 0 until n) bits += (value shr k) and 1 == 1 }
        // Not small; height in 18 bits; no fixed ratio; width in 9 bits; default metadata.
        u(0, 1); u(2, 2); u(h - 1, 18); u(0, 3); u(0, 2); u(w - 1, 9); u(1, 1)
        val out = ByteArray(2 + (bits.size + 7) / 8 + 8)
        out[0] = 0xFF.toByte()
        out[1] = 0x0A
        bits.forEachIndexed { i, b -> if (b) out[2 + i / 8] = (out[2 + i / 8].toInt() or (1 shl (i % 8))).toByte() }
        return out
    }

    @Test
    fun aJpegXlHoldingAJpegReadsRegionsOfIt() {
        val jxl = jxlHead(100, 20_000)
        assertEquals(listOf(100, 20_000), ImageHeaders.jxlSize(jxl)?.toList())
        val jpeg = TestImages.jpegHead(100, 20_000)
        Decoders.jpegOf = { if (it.contentEquals(jxl)) jpeg else null }
        Decoders.openRegions = { if (it.contentEquals(jpeg)) blank(100, 20_000) else null }
        assertTrue(Decoders.detail(jxl, banded, Long.MAX_VALUE) is RegionDetail)
    }

    @Test
    fun aJpegXlOfPixelsIsNotReadByRegions() {
        Decoders.jpegOf = { null }
        Decoders.openRegions = { blank(100, 20_000) }
        assertEquals(null, Decoders.regionDetail(jxlHead(100, 20_000), banded))
    }
}
