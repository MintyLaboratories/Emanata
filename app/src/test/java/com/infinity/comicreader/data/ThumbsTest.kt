package com.infinity.comicreader.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.infinity.comicreader.reader.Avif
import com.infinity.comicreader.reader.FakeAvifCodec
import com.infinity.comicreader.reader.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Covers come at exactly the requested size and are cached without loss. */
@RunWith(RobolectricTestRunner::class)
// File-backed ImageDecoder sources do not open on the host, so files decode as on API 27.
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clear() = Thumbs.clear(context)

    /** A [w] x [h] cover: red top third, green middle, blue bottom, so the crop shows. */
    private fun cover(w: Int = 300, h: Int = 450, name: String = "cover.png"): BookEntry {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            bitmap.setPixel(x, y, if (y < h / 3) Color.RED else if (y < 2 * h / 3) Color.GREEN else Color.BLUE)
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun coverFillsTheExactSizeCroppedEvenly() {
        val thumb = Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL, CropAnchor.CENTER))
        assertNotNull(thumb)
        assertEquals(120, thumb!!.width)
        assertEquals(60, thumb.height)
        // A 2:1 box keeps only the middle of the 2:3 cover: green, but for the rows next
        // to the cut, whose kernel reaches past it.
        assertEquals(Color.GREEN, thumb.getPixel(60, 30))
        assertEquals(Color.GREEN, thumb.getPixel(0, 30))
        assertTrue(Color.red(thumb.getPixel(60, 0)) > 0)
    }

    @Test
    fun fillFromTheTopKeepsTheTop() {
        val thumb = Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL, CropAnchor.TOP))!!
        assertEquals(Color.RED, thumb.getPixel(60, 2))
        assertEquals(Color.RED, thumb.getPixel(60, 30))
    }

    @Test
    fun fitShowsTheWholeCoverOverItsBlurredCopy() {
        // A 2:3 cover in a 2:1 box: the whole cover, full height, in the middle.
        val thumb = Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FIT))!!
        assertEquals(120, thumb.width)
        assertEquals(Color.RED, thumb.getPixel(60, 3))
        assertEquals(Color.BLUE, thumb.getPixel(60, 56))
        // Beside it, an opaque, darker blur of the same colors rather than empty space.
        val side = thumb.getPixel(5, 30)
        assertEquals(255, Color.alpha(side))
        assertTrue(Color.green(side) in 1..230)
    }

    @Test
    fun autoFillsCoversOfTheBoxShapeAndFitsOthers() {
        val same = Thumbs.load(context, cover(), "UTF-8", 200, 300)!!
        assertEquals(Color.RED, same.getPixel(100, 1))
        assertEquals(Color.RED, same.getPixel(1, 1))
        // A landscape image is fitted: its top edge sits well below the box's.
        val wide = Thumbs.load(context, cover(600, 300, "wide.png"), "UTF-8", 200, 300)!!
        assertTrue(Color.red(wide.getPixel(100, 1)) < 230)
        assertEquals(Color.GREEN, wide.getPixel(100, 150))
    }

    @Test
    fun eachStyleIsItsOwnCover() {
        val entry = cover()
        Thumbs.load(context, entry, "UTF-8", 50, 75, CoverStyle(ThumbFit.FILL))
        Thumbs.load(context, entry, "UTF-8", 50, 75, CoverStyle(ThumbFit.FIT))
        assertEquals(2, File(context.cacheDir, "covers").listFiles()!!.size)
    }

    /** A 1300 x 1000 wraparound: a white back with an EAN-13 barcode beside a red front, or two red halves. */
    private fun wraparound(barcodeLeft: Boolean, name: String): BookEntry {
        val bitmap = Bitmap.createBitmap(1300, 1000, Bitmap.Config.ARGB_8888)
        val back = if (barcodeLeft) 0..649 else 650..1299
        for (y in 0 until 1000) for (x in 0 until 1300) bitmap.setPixel(x, y, if (x in back) Color.WHITE else Color.RED)
        val matrix = MultiFormatWriter().encode("9780201379624", BarcodeFormat.EAN_13, 300, 120)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) bitmap.setPixel(back.first + 100 + x, 800 + y, Color.BLACK)
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
    }

    @Test
    fun aWraparoundShowsTheHalfWithoutTheBarcode() {
        for (left in listOf(true, false)) {
            val thumb = Thumbs.load(context, wraparound(left, "wrap$left.png"), "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
            assertEquals(Color.RED, thumb.getPixel(5, 5))
            assertEquals(Color.RED, thumb.getPixel(125, 195))
        }
    }

    /** A [w] x [h] PNG: [left] up to [split], [middle] up to [w] - [split], [right] after. */
    private fun bands(w: Int, h: Int, split: Int, left: Int, middle: Int, right: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            bitmap.setPixel(x, y, if (x < split) left else if (x < w - split) middle else right)
        }
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun aWraparoundWithoutBarcodeShowsTheHalfItsDirectionStartsOn() {
        val file = File(context.cacheDir, "plain.png")
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        val entry = BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
        val ltr = Thumbs.load(context, entry.copy(direction = ReadingDirection.LTR), "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(Color.RED, ltr.getPixel(5, 100))
        assertEquals(Color.RED, ltr.getPixel(125, 100))
        val rtl = Thumbs.load(context, entry.copy(direction = ReadingDirection.RTL), "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(Color.BLUE, rtl.getPixel(5, 100))
        assertEquals(Color.BLUE, rtl.getPixel(125, 100))
        // Each direction is its own cover.
        assertEquals(2, File(context.cacheDir, "covers").listFiles()!!.size)
    }

    @Test
    fun aCoverThatLooksTheSameEitherWayIsFoundForBothDirections() {
        val book = cover(name = "plain cover.png")
        assertTrue("unknown until made", Thumbs.directionMatters(book))
        Thumbs.load(context, book, "UTF-8", 50, 75, CoverStyle(ThumbFit.FILL))!!
        val rtl = book.copy(direction = ReadingDirection.RTL)
        assertFalse(Thumbs.directionMatters(rtl))
        assertNotNull(Thumbs.inMemory(context, rtl, 50, 75, CoverStyle(ThumbFit.FILL)))

        val file = File(context.cacheDir, "sided.png")
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        val sided = BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
        Thumbs.load(context, sided, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertTrue(Thumbs.directionMatters(sided))
        assertNull(Thumbs.inMemory(context, sided.copy(direction = ReadingDirection.RTL), 130, 200, CoverStyle(ThumbFit.FILL)))
    }

    @Test
    fun aBookCoverTwoInnerPagesWideShowsItsFrontWithoutTheSpine() {
        // Back blue, a green spine 60 wide, front red; inner pages 2:3 at another scale.
        val file = Fixtures.zip(
            File(context.cacheDir, "wrapped.cbz"),
            Fixtures.entry("00.png", bands(660, 450, 300, Color.BLUE, Color.GREEN, Color.RED)),
            Fixtures.entry("01.png", png(Color.WHITE)), Fixtures.entry("02.png", png(Color.WHITE)), Fixtures.entry("03.png", png(Color.WHITE))
        )
        val book = BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
        val thumb = Thumbs.load(context, book, "UTF-8", 100, 150, CoverStyle(ThumbFit.FILL))!!
        // Red throughout but for a faint edge where the kernel reaches across the cut.
        assertEquals(Color.RED, thumb.getPixel(5, 75))
        assertEquals(Color.RED, thumb.getPixel(99, 75))
        assertTrue(Color.green(thumb.getPixel(0, 75)) < 40)
    }

    @Test
    fun aLandscapeCoverOfALandscapeBookIsOnePage() {
        // Shaped like a wraparound alone, but so are the book's pages.
        val file = Fixtures.zip(
            File(context.cacheDir, "landscape.cbz"),
            Fixtures.entry("00.png", bands(400, 300, 200, Color.BLUE, Color.BLUE, Color.RED)),
            Fixtures.entry("01.png", bands(400, 300, 200, Color.WHITE, Color.WHITE, Color.WHITE)),
            Fixtures.entry("02.png", bands(400, 300, 200, Color.WHITE, Color.WHITE, Color.WHITE))
        )
        val book = BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
        val thumb = Thumbs.load(context, book, "UTF-8", 400, 300, CoverStyle(ThumbFit.FILL))!!
        // Whole: both halves show.
        assertEquals(Color.BLUE, thumb.getPixel(5, 150))
        assertEquals(Color.RED, thumb.getPixel(395, 150))
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun aBookmarkShowsItsPageInFrontOfTheCover() {
        val file = Fixtures.zip(
            File(context.cacheDir, "book.cbz"),
            Fixtures.entry("01.png", png(Color.RED)), Fixtures.entry("02.png", png(Color.GREEN)), Fixtures.entry("03.png", png(Color.BLUE))
        )
        val book = BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
        val thumb = Thumbs.load(context, book.copy(bookmarkPage = 2), "UTF-8", 100, 150, CoverStyle(ThumbFit.FILL))!!
        // The cover shows above and left of the page, which fills 80% from the bottom right.
        assertEquals(Color.RED, thumb.getPixel(1, 1))
        assertEquals(Color.BLUE, thumb.getPixel(99, 149))
        assertEquals(Color.BLUE, thumb.getPixel(21, 31))
        assertTrue(Color.red(thumb.getPixel(19, 75)) > 0)
        // The plain cover is a cover of its own.
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150, CoverStyle(ThumbFit.FILL))!!.getPixel(99, 149))
    }

    @Test
    fun aBookmarkPagePastTheEndShowsTheLastPage() {
        val file = Fixtures.zip(
            File(context.cacheDir, "short.cbz"), Fixtures.entry("01.png", png(Color.RED)), Fixtures.entry("02.png", png(Color.GREEN))
        )
        val book = BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified(), bookmarkPage = 9)
        assertEquals(Color.GREEN, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(90, 140))
    }

    @Test
    fun diskCacheIsLossless() {
        val entry = cover()
        val made = Thumbs.load(context, entry, "UTF-8", 97, 131)!!
        val files = File(context.cacheDir, "covers").listFiles()!!
        assertEquals(1, files.size)
        val stored = BitmapFactory.decodeFile(files[0].path)
        assertEquals(97, stored.width)
        assertArrayEquals(pixels(made), pixels(stored))
    }

    /** A three-page book: red, green, blue. */
    private fun rgbBook(name: String, dir: File = context.cacheDir): BookEntry {
        val file = Fixtures.zip(
            File(dir, name), Fixtures.entry("01.png", png(Color.RED)), Fixtures.entry("02.png", png(Color.GREEN)), Fixtures.entry("03.png", png(Color.BLUE))
        )
        return BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
    }

    @Test
    fun theCoverIsTheFirstPageNotHidden() {
        val book = rgbBook("hiding.cbz")
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        // Hiding the first page makes a new cover, though the old one is cached.
        Library(context).setHiddenPages(book.path, setOf(0), 3)
        assertEquals(Color.GREEN, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        Library(context).setHiddenPages(book.path, setOf(0, 1), 3)
        assertEquals(Color.BLUE, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        // With every page hidden every page shows, so the first is the cover again.
        Library(context).setHiddenPages(book.path, setOf(0, 1, 2), 3)
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
    }

    @Test
    fun aFolderCoverSkipsTheHiddenPagesOfItsBook() {
        val dir = File(context.cacheDir, "Series").apply { mkdirs() }
        val book = rgbBook("01.cbz", dir)
        val folder = BookEntry(dir, dir.name, true, EntryType.OTHER, 0, dir.lastModified())
        assertEquals(Color.RED, Thumbs.load(context, folder, "UTF-8", 100, 150)!!.getPixel(50, 75))
        Library(context).setHiddenPages(book.path, setOf(0), 3)
        assertEquals(Color.GREEN, Thumbs.load(context, folder, "UTF-8", 100, 150)!!.getPixel(50, 75))
    }

    @Test
    fun onlyHiddenPagesThatMoveACoverChangeItsKey() {
        val dir = File(context.cacheDir, "Shelf")
        val book = BookEntry(File(dir, "a.cbz"), "a.cbz", false, EntryType.ARCHIVE, 1, 1)
        val folder = BookEntry(dir, "Shelf", true, EntryType.OTHER, 0, 1)
        val inside = File(dir, "a.cbz").path
        assertEquals("", Thumbs.hiddenTag(book, emptyMap()))
        assertEquals("", Thumbs.hiddenTag(book, mapOf(inside to setOf(3))))
        assertEquals("|c2", Thumbs.hiddenTag(book, mapOf(inside to setOf(0, 1, 4))))
        assertEquals("", Thumbs.hiddenTag(folder, mapOf(inside to setOf(3))))
        val moved = Thumbs.hiddenTag(folder, mapOf(inside to setOf(0)))
        assertTrue(moved.startsWith("|h"))
        assertTrue(moved != Thumbs.hiddenTag(folder, mapOf(inside to setOf(0, 1))))
        // A folder whose name only starts the same is not inside.
        assertEquals("", Thumbs.hiddenTag(folder, mapOf(File(context.cacheDir, "Shelf 2/a.cbz").path to setOf(0))))
    }

    /** A book whose cover page is a layered AVIF: a red first layer and a blue full image ([FakeAvifCodec]). */
    private fun layeredBook(name: String): BookEntry {
        Avif.codec = FakeAvifCodec(
            FakeAvifCodec.info(600, 900), listOf(FakeAvifCodec.info(150, 225), FakeAvifCodec.info(600, 900)), listOf(Color.RED, Color.BLUE)
        )
        val avif = javaClass.classLoader!!.getResourceAsStream("avif/layered.avif")!!.use { it.readBytes() }
        val file = Fixtures.zip(File(context.cacheDir, name), Fixtures.entry("01.avif", avif), Fixtures.entry("02.png", png(Color.WHITE)))
        return BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
    }

    @After
    fun dropCodec() {
        Avif.codec = null
    }

    @Test
    fun aLayeredCoverComesFromItsFirstLayerUntilTheFullOneIsMade() {
        val book = layeredBook("layered.cbz")
        val first = Thumbs.loadFirst(context, book, "UTF-8", 100, 150)!!
        assertFalse(first.full)
        assertEquals(100, first.bitmap.width)
        assertEquals(Color.RED, first.bitmap.getPixel(50, 75))
        // The quick cover is never kept as the cover.
        assertNull(Thumbs.cached(context, book, 100, 150))
        assertFalse(Thumbs.loadFirst(context, book, "UTF-8", 100, 150)!!.full)

        assertEquals(Color.BLUE, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        val after = Thumbs.loadFirst(context, book, "UTF-8", 100, 150)!!
        assertTrue(after.full)
        assertEquals(Color.BLUE, after.bitmap.getPixel(50, 75))
    }

    @Test
    fun aCoverWithoutLayersIsWholeAtOnce() {
        val first = Thumbs.loadFirst(context, rgbBook("plain.cbz"), "UTF-8", 100, 150)!!
        assertTrue(first.full)
        assertEquals(Color.RED, first.bitmap.getPixel(50, 75))
    }

    @Test
    fun aCanceledCoverIsNeitherMadeNorKept() {
        val book = rgbBook("dropped.cbz")
        assertEquals(null, Thumbs.load(context, book, "UTF-8", 100, 150, CoverStyle(), canceled = { true }))
        assertEquals(null, File(context.cacheDir, "covers").listFiles()?.firstOrNull())
        assertEquals(null, Thumbs.cached(context, book, 100, 150))
    }

    @Test
    fun eachSizeIsItsOwnCover() {
        val entry = cover()
        val small = Thumbs.load(context, entry, "UTF-8", 50, 75)!!
        val large = Thumbs.load(context, entry, "UTF-8", 200, 300)!!
        assertEquals(50, small.width)
        assertEquals(200, large.width)
        assertTrue(File(context.cacheDir, "covers").listFiles()!!.size == 2)
    }
}
