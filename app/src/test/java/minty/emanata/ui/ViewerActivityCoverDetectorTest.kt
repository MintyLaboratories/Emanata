package minty.emanata.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import minty.emanata.R
import minty.emanata.ai.CoverTextDetector
import minty.emanata.ai.ScoreMap
import minty.emanata.data.Prefs
import minty.emanata.data.ResumeMode
import minty.emanata.data.Thumbs
import minty.emanata.reader.Fixtures
import minty.emanata.testing.coverText
import minty.emanata.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** The reader looks at a cover with the library's detector once, whatever the cover itself told. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivityCoverDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val detector = Thumbs.detector

    /** Runs of the stand-in detector. */
    @Volatile
    private var usualRuns = 0

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        Thumbs.clear(context)
        Thumbs.detector = { titleOnRed("usual") { usualRuns++ } }
    }

    @After
    fun dropDetectors() {
        Thumbs.detector = detector
        Thumbs.clear(context)
    }

    /** A detector known as [id] seeing a title on halves whose middle is red; [ran] counts its runs. */
    private fun titleOnRed(id: String, ran: () -> Unit): CoverTextDetector = coverText(id) { bitmap ->
        ran()
        val w = bitmap.width
        val h = bitmap.height
        val titled = bitmap.getPixel(w / 2, h / 2) == Color.RED
        ScoreMap(w, h, FloatArray(w * h) { i -> if (titled && i / w in h / 10 until h / 4 && i % w in w / 10 until w * 9 / 10) 0.9f else 0f })
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** A book whose first page is a wraparound of a red front on the left beside a white back with an EAN-13 barcode. */
    private fun book(): File {
        val cover = Bitmap.createBitmap(1300, 1000, Bitmap.Config.ARGB_8888)
        for (y in 0 until 1000) for (x in 0 until 1300) cover.setPixel(x, y, if (x < 650) Color.RED else Color.WHITE)
        val matrix = MultiFormatWriter().encode("9780201379624", BarcodeFormat.EAN_13, 300, 120)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix.get(x, y)) cover.setPixel(750 + x, 800 + y, Color.BLACK)
        val inner = png(Bitmap.createBitmap(650, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) })
        return Fixtures.zip(
            File(tmp.newFolder("books"), "Series C 01.cbz"),
            Fixtures.entry("00.png", png(cover)), Fixtures.entry("01.png", inner), Fixtures.entry("02.png", inner)
        )
    }

    private fun open(book: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun ViewerActivity.pagerDirection() = findViewById<View>(R.id.viewer_pager).layoutDirection

    @Test
    fun aCoverThatTurnsTheBookRightToLeftIsLookedAtOnce() {
        val book = book()
        val first = open(book)
        idleUntil("the first open turned right to left, all it started done") { first.pagerDirection() == View.LAYOUT_DIRECTION_RTL && first.openSettled() }
        assertEquals(View.LAYOUT_DIRECTION_RTL, first.pagerDirection())
        val runs = usualRuns
        assertTrue("the detector looked", runs > 0)
        first.finish()

        val second = open(book)
        assertEquals("right to left before the first page", View.LAYOUT_DIRECTION_RTL, second.pagerDirection())
        // Late detection, if any, has run.
        idleUntil("all the second open started done") { second.openSettled() }
        assertEquals("not looked at again", runs, usualRuns)
        assertEquals(View.LAYOUT_DIRECTION_RTL, second.pagerDirection())
    }
}
