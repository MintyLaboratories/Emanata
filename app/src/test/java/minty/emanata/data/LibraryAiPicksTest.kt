package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import minty.emanata.ai.AiPicks
import minty.emanata.ai.AiStep
import minty.emanata.ai.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A book's own AI chain is kept by its ID, moves with its data, and goes with library data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryAiPicksTest {

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        library = Library(context)
    }

    private val a = BookRef(BookId(0xa), "/books/a.cbz")
    private val b = BookRef(BookId(0xb), "/books/b.cbz")

    private val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first().id
    private val upscale = ModelCatalog.forStep(AiStep.UPSCALE).last().id

    @Test
    fun aBooksPicksAreKeptStepByStep() {
        assertNull(library.aiPicks(a.id))
        val picks = AiPicks.NONE.with(AiStep.DESCREEN, descreen).with(AiStep.UPSCALE, upscale)
        library.setAiPicks(a, picks)
        val got = library.aiPicks(a.id)!!
        assertNull(got.id(AiStep.ARTIFACTS))
        assertEquals(descreen, got.id(AiStep.DESCREEN))
        assertEquals(upscale, got.id(AiStep.UPSCALE))
        assertEquals(true, library.kept()[a.id]?.settings)
        assertNull(library.aiPicks(b.id))
    }

    @Test
    fun noAiIsABooksOwnChoiceToo() {
        library.setAiPicks(a, AiPicks.NONE)
        assertEquals(emptyList<Any>(), library.aiPicks(a.id)!!.models)
        library.setAiPicks(a, null)
        assertNull("back to the default", library.aiPicks(a.id))
    }

    @Test
    fun picksMoveWithTheBookAndGoWithLibraryData() {
        library.setAiPicks(a, AiPicks.NONE.with(AiStep.UPSCALE, upscale))
        library.carryOver(a.id, b, IntArray(0), 0)
        assertNull(library.aiPicks(a.id))
        assertEquals(upscale, library.aiPicks(b.id)!!.id(AiStep.UPSCALE))
        library.forgetBooks(setOf(b.id))
        assertNull(library.aiPicks(b.id))
        library.setAiPicks(a, AiPicks.NONE)
        assertTrue(library.mayHaveData(a.id))
        library.clearLibraryData()
        assertNull(library.aiPicks(a.id))
    }

    @Test
    fun picksSurviveABackup() {
        library.setAiPicks(a, AiPicks.NONE.with(AiStep.DESCREEN, descreen))
        val backup = library.exportAll()
        library.clearLibraryData()
        library.importAll(backup)
        assertEquals(descreen, library.aiPicks(a.id)!!.id(AiStep.DESCREEN))
    }
}
