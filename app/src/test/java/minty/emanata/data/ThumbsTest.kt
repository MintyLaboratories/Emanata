package minty.emanata.data

import minty.emanata.testing.bookId
import minty.emanata.testing.coverText
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import minty.emanata.ai.CoverTextDetector
import minty.emanata.ai.ScoreMap
import minty.emanata.ai.TextDetector
import minty.emanata.reader.Avif
import minty.emanata.reader.FakeAvifCodec
import minty.emanata.reader.Fixtures
import minty.emanata.reader.Wraparound
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun aLooseImageIsNotReadForABookId() {
        val image = cover()
        assertNotNull(Thumbs.load(context, image, "UTF-8", 200, 300))
        assertNull(BookIds.known(image.file))
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

    /** Stands in a detector seeing text across rows [top] to [bottom] (shares of the height) of every image; counts its calls. */
    private fun textAt(top: Double, bottom: Double, id: String = "fake"): IntArray {
        val calls = intArrayOf(0)
        standIn(id) { bitmap ->
            calls[0]++
            textMap(bitmap, top, bottom)
        }
        return calls
    }

    /** A score map of [bitmap] with text across rows [top] to [bottom] (shares of the height). */
    private fun textMap(bitmap: Bitmap, top: Double, bottom: Double): ScoreMap {
        val w = bitmap.width
        val h = bitmap.height
        return ScoreMap(w, h, FloatArray(w * h) { i -> if (i / w in (top * h).toInt() until (bottom * h).toInt() && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
    }

    /** Stands in a detector known as [id] whose lines are the text of the score map [map] gives each image. */
    private fun standIn(id: String = "fake", map: (Bitmap) -> ScoreMap?) {
        val stand = coverText(id, map)
        Thumbs.detector = { stand }
    }

    private val detector = Thumbs.detector
    private val arrivals = Thumbs.arrivals
    private val modelInPlace = Thumbs.modelInPlace

    @After
    fun dropDetector() {
        Thumbs.detector = detector
        Thumbs.arrivals = arrivals
        Thumbs.modelInPlace = modelInPlace
    }

    /** A stand-in PP-OCR whose model is in place while [Waiting.ready]; counts its looks at that and its runs. */
    private class Waiting(var ready: Boolean = false) {
        var asked = 0
        var runs = 0
    }

    /** Stands [w] in as the detector, seeing a title on blue halves ([titleMap]) once its model is in place. */
    private fun waitingDetector(w: Waiting) {
        val stand = object : CoverTextDetector {
            override val id = "waiting"

            override fun ready(): Boolean {
                w.asked++
                return w.ready
            }

            override fun lines(src: Bitmap): List<CoverText.TextBox> {
                check(w.ready) { "looked without its model" }
                w.runs++
                return CoverText.lines(titleMap(src), src.width, src.height)
            }
        }
        Thumbs.detector = { stand }
    }

    private fun coverFiles(): List<String> = File(context.cacheDir, "covers").listFiles().orEmpty().map { it.name }.sorted()

    /** A score map of [bitmap] with a title near its top when its middle is blue, else none. */
    private fun titleMap(bitmap: Bitmap): ScoreMap =
        if (Color.blue(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200) textMap(bitmap, 0.1, 0.25) else textMap(bitmap, 0.0, 0.0)

    /** Stands in a detector known as [id] seeing a title on blue halves only ([titleMap]); counts its calls. */
    private fun titleOnBlue(id: String = "fake"): IntArray {
        val calls = intArrayOf(0)
        standIn(id) { bitmap ->
            calls[0]++
            titleMap(bitmap)
        }
        return calls
    }

    /** A 1300 x 1000 wraparound without a barcode, its left half blue, its right half red, as an image book. */
    private fun uncoded(name: String): BookEntry {
        val file = File(context.cacheDir, name)
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        return BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
    }

    /** True when [thumb], a cover of an [uncoded] wraparound, shows its blue half alone: its front by its title. */
    private fun showsBlueFront(thumb: Bitmap): Boolean =
        thumb.getPixel(5, thumb.height / 2) == Color.BLUE && thumb.getPixel(thumb.width - 6, thumb.height / 2) == Color.BLUE

    /** True when [thumb], a 130 x 200 cover of an [uncoded] wraparound, shows it whole: blue beside red. */
    private fun showsWhole(thumb: Bitmap): Boolean = thumb.getPixel(30, 100) == Color.BLUE && thumb.getPixel(100, 100) == Color.RED

    @Test
    fun focusesOfOtherVersionsAreDeleted() {
        val old = File(context.cacheDir, Thumbs.focusFile("${CoverText.RULES - 1}-${TextDetector.ID}")).apply { writeText("") }
        val gone = File(context.cacheDir, Thumbs.focusFile("${CoverText.RULES - 1}-other-detector")).apply { writeText("") }
        titleOnBlue(TextDetector.ID)
        Thumbs.load(context, uncoded("versions.png"), "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertFalse(old.exists())
        assertFalse(gone.exists())
        assertTrue(File(context.cacheDir, Thumbs.focusFile(CoverText.version(TextDetector.ID))).isFile)
    }

    @Test
    fun anotherDetectorFindsTextAgainAndShowsAnotherHalfOfAWraparoundWithoutBarcode() {
        val file = File(context.cacheDir, "switched.png")
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        val entry = BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified(), direction = ReadingDirection.LTR)
        val top = CoverStyle(ThumbFit.FILL, CropAnchor.TOP)
        titledHalf(Color.BLUE, "a")
        assertEquals(Color.BLUE, Thumbs.load(context, entry, "UTF-8", 130, 200, top)!!.getPixel(65, 100))
        assertNotNull(Thumbs.inMemory(context, entry, 130, 200, top))
        assertTrue(File(context.cacheDir, Thumbs.focusFile(CoverText.version("a"))).isFile)
        assertEquals(CoverText.version("a"), Thumbs.coverVersion(context))
        // Another detector sees the title on the red half: its own text and store; that cover is not the one made before.
        titledHalf(Color.RED, "b")
        assertNull(Thumbs.inMemory(context, entry, 130, 200, top))
        assertEquals(Color.RED, Thumbs.load(context, entry, "UTF-8", 130, 200, top)!!.getPixel(65, 100))
        assertTrue(File(context.cacheDir, Thumbs.focusFile(CoverText.version("b"))).isFile)
    }

    @Test
    fun coversNoDetectorShapedOutliveAChangeOfDetector() {
        val top = CoverStyle(ThumbFit.FILL, CropAnchor.TOP)
        val plain = cover(name = "plain.png")
        val barcoded = wraparound(true, "barcoded.png")
        val titled = uncoded("titled.png")
        val a = titleOnBlue("a")
        val first = Thumbs.load(context, plain, "UTF-8", 120, 60, top)!!
        val front = Thumbs.load(context, barcoded, "UTF-8", 130, 200, top)!!
        val shaped = Thumbs.load(context, titled, "UTF-8", 130, 200, top)!!
        assertEquals("only the wraparound without a barcode looked", 2, a[0])
        val files = coverFiles()
        // Another detector: a cover from the top is the one made before.
        val b = titleOnBlue("b")
        assertSame(first, Thumbs.inMemory(context, plain, 120, 60, top))
        assertSame(first, Thumbs.load(context, plain, "UTF-8", 120, 60, top))
        // Wraparounds, whose text it looks for, are made again.
        assertNull(Thumbs.inMemory(context, titled, 130, 200, top))
        assertNull(Thumbs.inMemory(context, barcoded, 130, 200, top))
        assertFalse(shaped === Thumbs.load(context, titled, "UTF-8", 130, 200, top))
        assertFalse(front === Thumbs.load(context, barcoded, "UTF-8", 130, 200, top))
        assertEquals(2, b[0])
        assertEquals(files.size + 2, coverFiles().size)
    }

    @Test
    fun coversNoDetectorShapedComeFromDiskAfterAChangeOfDetector() {
        val top = CoverStyle(ThumbFit.FILL, CropAnchor.TOP)
        val plain = cover(name = "plain.png")
        val barcoded = wraparound(true, "barcoded.png")
        val titled = uncoded("titled.png")
        titleOnBlue("a")
        val first = Thumbs.load(context, plain, "UTF-8", 120, 60, top)!!
        Thumbs.load(context, barcoded, "UTF-8", 130, 200, top)!!
        Thumbs.load(context, titled, "UTF-8", 130, 200, top)!!
        val files = coverFiles()
        // A restart with another detector: memory is empty, the disk keeps what was made.
        Thumbs.dropMemory()
        val b = titleOnBlue("b")
        assertNull(Thumbs.inMemory(context, plain, 120, 60, top))
        val again = Thumbs.cached(context, plain, 120, 60, top)
        assertNotNull("a cover from the top comes from disk", again)
        assertEquals(first.getPixel(60, 30), again!!.getPixel(60, 30))
        // Wraparounds, whose text it looks for, are not served from the other's.
        assertNull(Thumbs.cached(context, titled, 130, 200, top))
        assertNull(Thumbs.cached(context, barcoded, 130, 200, top))
        assertEquals("nothing looked", 0, b[0])
        assertEquals(files, coverFiles())
    }

    /** Stands in a detector known as [id] seeing a title on halves whose middle is [color] only. */
    private fun titledHalf(color: Int, id: String) {
        standIn(id) { bitmap ->
            val w = bitmap.width
            val h = bitmap.height
            val titled = bitmap.getPixel(w / 2, h / 2) == color
            ScoreMap(w, h, FloatArray(w * h) { i -> if (titled && i / w in h / 10 until h / 4 && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
        }
    }

    @Test
    fun theAppsDetectorIsPpOcr() {
        Thumbs.detector = detector
        assertEquals(CoverText.version(TextDetector.ID), Thumbs.coverVersion(context))
    }

    @Test
    fun textLookedForWithoutTheDetectorIsNeitherKeptNorStored() {
        var works = false
        val calls = intArrayOf(0)
        standIn { bitmap ->
            calls[0]++
            if (works) titleMap(bitmap) else null
        }
        val entry = uncoded("unkept.png")
        val fill = CoverStyle(ThumbFit.FILL)
        // Whole, and not written to disk.
        assertTrue(showsWhole(Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!))
        assertEquals(2, calls[0])
        assertNull(File(context.cacheDir, "covers").listFiles()?.firstOrNull())
        works = true
        assertTrue(showsBlueFront(Thumbs.load(context, entry, "UTF-8", 100, 150, fill)!!))
        assertEquals(4, calls[0])
        assertEquals(1, File(context.cacheDir, "covers").listFiles()!!.size)
        Thumbs.load(context, entry, "UTF-8", 80, 120, fill)!!
        assertEquals(4, calls[0])
    }

    @Test
    fun aCoverMadeWhileTheDetectorsModelDownloadsIsMadeAgainOnceItArrives() {
        var arrived = 0
        Thumbs.arrivals = { arrived }
        var works = false
        var calls = 0
        standIn { bitmap ->
            calls++
            if (works) titleMap(bitmap) else null
        }
        val entry = uncoded("arrives.png")
        val fill = CoverStyle(ThumbFit.FILL)
        assertTrue(showsWhole(Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!))
        assertNotNull("kept in memory meanwhile", Thumbs.inMemory(context, entry, 130, 200, fill))
        works = true
        arrived++
        assertNull(Thumbs.inMemory(context, entry, 130, 200, fill))
        assertTrue(showsBlueFront(Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!))
        assertEquals(4, calls)
        arrived++
        assertNotNull("one found with the detector stays", Thumbs.inMemory(context, entry, 130, 200, fill))
    }

    @Test
    fun aModelArrivingWhileACoverIsMadeWithoutItMakesItAgain() {
        var arrived = 0
        Thumbs.arrivals = { arrived }
        standIn { _ ->
            // Arrives as the detector found nothing.
            arrived++
            null
        }
        val entry = uncoded("arriving.png")
        val fill = CoverStyle(ThumbFit.FILL)
        assertNotNull(Thumbs.load(context, entry, "UTF-8", 130, 200, fill))
        assertNull(Thumbs.inMemory(context, entry, 130, 200, fill))
    }

    @Test
    fun aCoverMadeWhileTheModelIsMissingIsKeptOnDiskUntilItArrives() {
        var arrived = 0
        Thumbs.arrivals = { arrived }
        val w = Waiting()
        waitingDetector(w)
        val entry = uncoded("missing.png")
        val fill = CoverStyle(ThumbFit.FILL)
        // Whole, and kept on disk as a waiting cover only.
        assertTrue(showsWhole(Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!))
        assertEquals(0, w.runs)
        val files = coverFiles()
        assertEquals(1, files.size)
        assertTrue(files.toString(), files[0].substringBeforeLast('.').endsWith("-waiting"))
        // Out of memory (another arrival), it comes from disk while the model is still missing, which asks for it again.
        arrived++
        assertNull(Thumbs.inMemory(context, entry, 130, 200, fill))
        val asked = w.asked
        assertTrue(showsWhole(Thumbs.cached(context, entry, 130, 200, fill)!!))
        assertEquals(asked + 1, w.asked)
        assertEquals(0, w.runs)
        // The model arrives: the waiting cover goes, and the cover is made with its text and kept.
        w.ready = true
        arrived++
        assertNull(Thumbs.cached(context, entry, 130, 200, fill))
        assertTrue(coverFiles().isEmpty())
        assertTrue(showsBlueFront(Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!))
        assertEquals(2, w.runs)
        assertEquals(1, coverFiles().size)
        assertFalse(coverFiles()[0].contains("-waiting"))
        arrived++
        assertNotNull(Thumbs.inMemory(context, entry, 130, 200, fill))
    }

    @Test
    fun aWaitingCoverFoundWithTheModelInPlaceIsMadeAgain() {
        val w = Waiting()
        waitingDetector(w)
        val entry = uncoded("found.png")
        val fill = CoverStyle(ThumbFit.FILL)
        Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!
        // A later session: memory empty, the model downloaded meanwhile without any cover going without it.
        val waiting = File(context.cacheDir, "covers").listFiles()!!.single()
        val kept = waiting.readBytes()
        Thumbs.clear(context)
        File(context.cacheDir, "covers").mkdirs()
        waiting.writeBytes(kept)
        w.ready = true
        assertNull(Thumbs.cached(context, entry, 130, 200, fill))
        assertFalse(waiting.exists())
        assertTrue(showsBlueFront(Thumbs.load(context, entry, "UTF-8", 130, 200, fill)!!))
    }

    @Test
    fun trimDeletesWaitingCoversOnceTheModelIsInPlace() {
        waitingDetector(Waiting())
        Thumbs.load(context, uncoded("waits.png"), "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        titleOnBlue()
        Thumbs.load(context, uncoded("other.png"), "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(2, coverFiles().size)
        Thumbs.modelInPlace = { false }
        Thumbs.trim(context)
        assertEquals(2, coverFiles().size)
        Thumbs.modelInPlace = { true }
        Thumbs.trim(context)
        assertEquals(1, coverFiles().size)
        assertFalse(coverFiles()[0].contains("-waiting"))
    }

    @Test
    fun onlyCoversThatLookForTextAskForTheModel() {
        val w = Waiting()
        waitingDetector(w)
        // A cover cropped by either anchor looks for no text: no download.
        Thumbs.load(context, cover(), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL))!!
        Thumbs.load(context, cover(name = "center.png"), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL, CropAnchor.CENTER))!!
        assertEquals(0, w.asked)
        // A wraparound with a barcode under Top looks for text only to settle the reading direction.
        val (coded, codedId) = wrapBook(true, "waiting-coded.cbz")
        assertEquals(Color.RED, Thumbs.load(context, coded, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!.getPixel(65, 100))
        assertTrue(w.asked > 0)
        // That stays open while the model is missing; the barcode alone decides what shows, so that cover is kept.
        assertNull(Thumbs.knownFront(coded.file, 0))
        assertNull(BookFactsCache(context).get(codedId)?.cover)
        // Kept: the plain covers' and this one.
        assertEquals(3, coverFiles().count { !it.contains("-waiting") })
        // A wraparound without a barcode under Top looks for its title to tell its front: it asks, shows whole, and waits.
        val asked = w.asked
        val (uncoded, _) = wrapBook(false, "waiting-uncoded.cbz")
        Thumbs.load(context, uncoded, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertTrue(w.asked > asked)
        assertEquals(1, coverFiles().count { it.contains("-waiting") })
        assertEquals(0, w.runs)
    }

    @Test
    fun theStyleKeyIsUnderTheDetector() {
        val usual = coverText("usual") { null }
        Thumbs.detector = { usual }
        val style = CoverStyle(ThumbFit.FILL, CropAnchor.CENTER)
        assertEquals(style.key("usual"), Thumbs.styleKey(context, style))
    }

    @Test
    fun noCropAnchorLooksForText() {
        val calls = textAt(0.85, 0.92)
        for (anchor in CropAnchor.entries) {
            val thumb = Thumbs.load(context, cover(name = "${anchor.key}.png"), "UTF-8", 120, 60, CoverStyle(ThumbFit.FILL, anchor))!!
            // Text in the blue bottom third moves no crop.
            assertEquals(if (anchor == CropAnchor.TOP) Color.RED else Color.GREEN, thumb.getPixel(60, 30))
        }
        assertEquals(0, calls[0])
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
    fun aWraparoundWithoutBarcodeOrTitleShowsWholeOverItsBlur() {
        val file = File(context.cacheDir, "plain.png")
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        val entry = BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified(), direction = ReadingDirection.RTL)
        // Fill asked for, yet both halves show, full width, between bands of blur.
        val thumb = Thumbs.load(context, entry, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(Color.BLUE, thumb.getPixel(30, 100))
        assertEquals(Color.RED, thumb.getPixel(100, 100))
        val above = thumb.getPixel(65, 10)
        assertEquals(255, Color.alpha(above))
        assertTrue("dimmed blur: $above", Color.blue(above) < 250 && Color.red(above) < 250)
        // Not from the reading direction: one cover either way.
        assertSame(thumb, Thumbs.inMemory(context, entry.copy(direction = ReadingDirection.LTR), 130, 200, CoverStyle(ThumbFit.FILL)))
        assertEquals(1, File(context.cacheDir, "covers").listFiles()!!.size)
    }

    /** A book of [wraparound]'s cover, its barcode on the left when [barcode], and two inner pages half as wide; its facts stored. */
    private fun wrapBook(barcode: Boolean, name: String): Pair<BookEntry, BookId> {
        val cover = if (barcode) wraparound(true, "$name.png").file.readBytes() else bands(1300, 1000, 650, Color.WHITE, Color.WHITE, Color.RED)
        val file = Fixtures.zip(
            File(context.cacheDir, name), Fixtures.entry("00.png", cover), Fixtures.entry("01.png", png(Color.GRAY)), Fixtures.entry("02.png", png(Color.GRAY))
        )
        BookIds.init(context)
        val id = BookIds.find(file)!!
        BookFactsCache.reset()
        BookFactsCache(context).put(id, BookFacts(3, 0))
        return BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified(), id = id) to id
    }

    /** Stands in a detector seeing a title on halves whose middle is [color]. */
    private fun titleOn(color: Int) {
        standIn { bitmap ->
            val w = bitmap.width
            val h = bitmap.height
            val titled = bitmap.getPixel(w / 2, h / 2) == color
            ScoreMap(w, h, FloatArray(w * h) { i -> if (titled && i / w in h / 10 until h / 4 && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
        }
    }

    @Test
    fun aWraparoundWhoseBarcodeAndTitleAgreeSetsTheDirection() {
        titleOn(Color.RED)
        val (book, id) = wrapBook(true, "agreed.cbz")
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!.getPixel(65, 100))
        // For the reader this session, and with the book's facts for later ones.
        assertEquals(CoverFront(0, Wraparound.Half.RIGHT, Thumbs.coverVersion(context)), Thumbs.knownFront(book.file, 0))
        assertEquals(CoverFront(0, Wraparound.Half.RIGHT, Thumbs.coverVersion(context)), BookFactsCache(context).get(id)?.cover)
        // Another cover page is another image.
        assertNull(Thumbs.knownFront(book.file, 1))
    }

    @Test
    fun anotherDetectorSettlesTheDirectionAgain() {
        titledHalf(Color.RED, "a")
        val (book, id) = wrapBook(true, "relooked.cbz")
        Thumbs.load(context, book, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(CoverFront(0, Wraparound.Half.RIGHT, CoverText.version("a")), Thumbs.knownFront(book.file, 0))
        // Another detector sees the title on the barcode's side: the barcode alone tells nothing.
        titledHalf(Color.WHITE, "b")
        Thumbs.load(context, book, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        val found = CoverFront(0, null, CoverText.version("b"))
        assertEquals(found, Thumbs.knownFront(book.file, 0))
        assertEquals(found, BookFactsCache(context).cover(id, CoverText.version("b")))
        assertNull(BookFactsCache(context).cover(id, CoverText.version("a")))
    }

    @Test
    fun aWraparoundWhoseTitleIsOnTheBarcodesSideShowsTheBarcodesFrontAndSetsNoDirection() {
        titleOn(Color.WHITE)
        val (book, id) = wrapBook(true, "disagreed.cbz")
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!.getPixel(65, 100))
        assertEquals(CoverFront(0, null, Thumbs.coverVersion(context)), Thumbs.knownFront(book.file, 0))
        assertEquals(CoverFront(0, null, Thumbs.coverVersion(context)), BookFactsCache(context).get(id)?.cover)
    }

    @Test
    fun aWraparoundWithoutBarcodeSetsNoDirection() {
        titleOn(Color.RED)
        val (book, _) = wrapBook(false, "uncoded.cbz")
        Thumbs.load(context, book, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(CoverFront(0, null, Thumbs.coverVersion(context)), Thumbs.knownFront(book.file, 0))
    }

    @Test
    fun aBarcodeWhoseTitleWasNotLookedForLeavesTheDirectionOpen() {
        standIn { null }
        val (book, id) = wrapBook(true, "unlooked.cbz")
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!.getPixel(65, 100))
        assertNull(Thumbs.knownFront(book.file, 0))
        assertNull(BookFactsCache(context).get(id)?.cover)
        // The barcode alone decides what shows: that cover is kept.
        assertEquals(1, File(context.cacheDir, "covers").listFiles()!!.size)
    }

    @Test
    fun aWraparoundWithoutBarcodeShowsTheHalfWithTheTallerTitle() {
        // Text seen on blue halves only: the left one, though a left-to-right book starts on the right.
        val calls = intArrayOf(0)
        standIn { bitmap ->
            calls[0]++
            val w = bitmap.width
            val h = bitmap.height
            val blue = Color.blue(bitmap.getPixel(w / 2, h / 2)) > 200
            ScoreMap(w, h, FloatArray(w * h) { i -> if (blue && i / w in h / 10 until h / 4 && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
        }
        val file = File(context.cacheDir, "titled.png")
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        val entry = BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified(), direction = ReadingDirection.LTR)
        val thumb = Thumbs.load(context, entry, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(Color.BLUE, thumb.getPixel(5, 100))
        assertEquals(Color.BLUE, thumb.getPixel(125, 100))
        assertEquals(2, calls[0])
        // Another size and anchor use the text found.
        Thumbs.load(context, entry, "UTF-8", 100, 150, CoverStyle(ThumbFit.FILL, CropAnchor.CENTER))!!
        assertEquals(2, calls[0])
    }

    @Test
    fun aWraparoundWhoseTitleWasNotLookedForIsWholeAndNotKept() {
        standIn { null }
        val file = File(context.cacheDir, "unread.png")
        file.writeBytes(bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED))
        val entry = BookEntry(file, file.name, false, EntryType.IMAGE, file.length(), file.lastModified())
        val thumb = Thumbs.load(context, entry, "UTF-8", 130, 200, CoverStyle(ThumbFit.FILL))!!
        assertEquals(Color.BLUE, thumb.getPixel(30, 100))
        assertEquals(Color.RED, thumb.getPixel(100, 100))
        assertNull(File(context.cacheDir, "covers").listFiles()?.firstOrNull())
    }

    @Test
    fun aBookCoverTwoInnerPagesWideShowsItsFrontWithoutTheSpine() {
        // Back blue, a green spine 60 wide, front red with a title; inner pages 2:3 at another scale.
        standIn { bitmap ->
            val w = bitmap.width
            val h = bitmap.height
            val red = Color.red(bitmap.getPixel(w / 2, h / 2)) > 200
            ScoreMap(w, h, FloatArray(w * h) { i -> if (red && i / w in h / 10 until h / 4 && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
        }
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
        Library(context).setHiddenPages(ref(book), setOf(0), 3)
        assertEquals(Color.GREEN, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        Library(context).setHiddenPages(ref(book), setOf(0, 1), 3)
        assertEquals(Color.BLUE, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        // With every page hidden every page shows, so the first is the cover again.
        Library(context).setHiddenPages(ref(book), setOf(0, 1, 2), 3)
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
    }

    @Test
    fun aPageGivenAsCoverIsTheCoverUnlessHidden() {
        val dir = File(context.cacheDir, "Given").apply { mkdirs() }
        val book = rgbBook("given.cbz", dir)
        val folder = BookEntry(dir, dir.name, true, EntryType.OTHER, 0, dir.lastModified())
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        Library(context).setCoverPage(ref(book), 2)
        assertEquals(Color.BLUE, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        // A folder shows its book's cover as given.
        assertEquals(Color.BLUE, Thumbs.load(context, folder, "UTF-8", 100, 150)!!.getPixel(50, 75))
        // Hidden, the given page gives way to the first page shown.
        Library(context).setHiddenPages(ref(book), setOf(0, 2), 3)
        assertEquals(Color.GREEN, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        Library(context).setHiddenPages(ref(book), emptySet(), 3)
        Library(context).setCoverPage(ref(book), null)
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
    }

    @Test
    fun aBooksOwnCharsetMakesItsOwnCover() {
        // Its own charset may order its pages anew: the cover made before is not taken.
        val book = rgbBook("charset.cbz")
        assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
        try {
            Library(context).setNameCharset(ref(book), "windows-1251")
            assertEquals(null, Thumbs.cached(context, book, 100, 150))
            assertEquals(Color.RED, Thumbs.load(context, book, "UTF-8", 100, 150)!!.getPixel(50, 75))
            assertTrue(Thumbs.cached(context, book, 100, 150) != null)
        } finally {
            Library(context).setNameCharset(ref(book), null)
        }
        assertTrue(Thumbs.cached(context, book, 100, 150) != null)
    }

    @Test
    fun aFolderCoverSkipsTheHiddenPagesOfItsBook() {
        val dir = File(context.cacheDir, "Series").apply { mkdirs() }
        val book = rgbBook("01.cbz", dir)
        val folder = BookEntry(dir, dir.name, true, EntryType.OTHER, 0, dir.lastModified())
        assertEquals(Color.RED, Thumbs.load(context, folder, "UTF-8", 100, 150)!!.getPixel(50, 75))
        Library(context).setHiddenPages(ref(book), setOf(0), 3)
        assertEquals(Color.GREEN, Thumbs.load(context, folder, "UTF-8", 100, 150)!!.getPixel(50, 75))
    }

    @Test
    fun aCoverPageMovedByHiddenPagesOrCharsetIsLookedAtAgain() {
        val calls = textAt(0.1, 0.25)
        // Two wraparounds without a barcode, then inner pages: either is the cover.
        val wrap = bands(1300, 1000, 650, Color.BLUE, Color.BLUE, Color.RED)
        val file = Fixtures.zip(
            File(context.cacheDir, "refocus.cbz"), Fixtures.entry("00.png", wrap), Fixtures.entry("01.png", wrap),
            Fixtures.entry("02.png", png(Color.GRAY)), Fixtures.entry("03.png", png(Color.GRAY)), Fixtures.entry("04.png", png(Color.GRAY))
        )
        val book = BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
        val fill = CoverStyle(ThumbFit.FILL)
        Thumbs.load(context, book, "UTF-8", 100, 150, fill)!!
        Thumbs.load(context, book, "UTF-8", 80, 120, fill)!!
        assertEquals(2, calls[0])
        Library(context).setHiddenPages(ref(book), setOf(0), 5)
        Thumbs.load(context, book, "UTF-8", 100, 150, fill)!!
        assertEquals(4, calls[0])
        try {
            Library(context).setNameCharset(ref(book), "windows-1251")
            Thumbs.load(context, book, "UTF-8", 100, 150, fill)!!
            assertEquals(6, calls[0])
        } finally {
            Library(context).setNameCharset(ref(book), null)
        }
    }

    /** [book] as the library keeps it, its ID found now. */
    private fun ref(book: BookEntry) = BookRef(bookId(book.file), book.path)

    @Test
    fun onlyHiddenPagesThatMoveACoverChangeItsKey() {
        val dir = File(context.cacheDir, "Shelf")
        val id = BookId(1)
        val book = BookEntry(File(dir, "a.cbz"), "a.cbz", false, EntryType.ARCHIVE, 1, 1, id = id)
        val folder = BookEntry(dir, "Shelf", true, EntryType.OTHER, 0, 1)
        val inside = File(dir, "a.cbz").path
        fun hidden(pages: Set<Int>, at: String = inside) = Library.Hidden(mapOf(id to pages), mapOf(at to pages), emptyMap())
        assertEquals("", Thumbs.hiddenTag(book, Library.Hidden(emptyMap(), emptyMap(), emptyMap())))
        assertEquals("", Thumbs.hiddenTag(book, hidden(setOf(3))))
        assertEquals("|c2", Thumbs.hiddenTag(book, hidden(setOf(0, 1, 4))))
        // A book is known by its ID, wherever its pages were hidden.
        assertEquals("", Thumbs.hiddenTag(book.copy(id = BookId(2)), hidden(setOf(0, 1, 4))))
        assertEquals("", Thumbs.hiddenTag(folder, hidden(setOf(3))))
        val moved = Thumbs.hiddenTag(folder, hidden(setOf(0)))
        assertTrue(moved.startsWith("|h"))
        assertTrue(moved != Thumbs.hiddenTag(folder, hidden(setOf(0, 1))))
        // A cover given moves the key as hidden pages do; one hidden gives way.
        assertEquals("|c3", Thumbs.hiddenTag(book, Library.Hidden(emptyMap(), emptyMap(), emptyMap(), mapOf(id to 3), mapOf(inside to 3))))
        assertEquals("|c1", Thumbs.hiddenTag(book, Library.Hidden(mapOf(id to setOf(0, 3)), mapOf(inside to setOf(0, 3)), emptyMap(), mapOf(id to 3))))
        assertTrue(Thumbs.hiddenTag(folder, Library.Hidden(emptyMap(), emptyMap(), emptyMap(), mapOf(id to 3), mapOf(inside to 3))).startsWith("|h"))
        // A folder whose name only starts the same is not inside.
        assertEquals("", Thumbs.hiddenTag(folder, hidden(setOf(0), File(context.cacheDir, "Shelf 2/a.cbz").path)))
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
    fun aWraparoundsFirstLayerSettlesNothingItsFullImageDoes() {
        // A layered wraparound twice its inner pages' width; the first layer is too small to trust for a barcode.
        Avif.codec = FakeAvifCodec(
            FakeAvifCodec.info(1300, 1000), listOf(FakeAvifCodec.info(325, 250), FakeAvifCodec.info(1300, 1000)), listOf(Color.RED, Color.BLUE)
        )
        val avif = javaClass.classLoader!!.getResourceAsStream("avif/layered.avif")!!.use { it.readBytes() }
        val file = Fixtures.zip(
            File(context.cacheDir, "layered-wrap.cbz"),
            Fixtures.entry("01.avif", avif), Fixtures.entry("02.png", png(Color.WHITE)), Fixtures.entry("03.png", png(Color.WHITE))
        )
        val book = BookEntry(file, file.name, false, EntryType.ARCHIVE, file.length(), file.lastModified())
        assertFalse(Thumbs.loadFirst(context, book, "UTF-8", 100, 150)!!.full)
        assertNull(Thumbs.knownFront(file, 0))
        Thumbs.load(context, book, "UTF-8", 100, 150)!!
        assertEquals(CoverFront(0, null, Thumbs.coverVersion(context)), Thumbs.knownFront(file, 0))
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
