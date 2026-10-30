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
}
