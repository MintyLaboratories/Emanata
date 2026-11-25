package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A folder keeps a direction alone or a frozen layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderLayoutTest {

    private val defaults = ReaderLayout(fit = "width", autoCrop = true, spacing = 8)

    private val frozen = ReaderLayout("paged", "rtl", "height", TwoPage.LANDSCAPE, autoCrop = false, spacing = 12)

    @Test
    fun aDirectionOverridesOnlyTheDirection() {
        assertEquals(defaults.copy(dir = "rtl"), FolderLayout.Direction("rtl").over(defaults))
    }

    @Test
    fun aFrozenLayoutIgnoresTheDefaults() {
        assertEquals(frozen, FolderLayout.Frozen(frozen).over(defaults))
        assertEquals("rtl", FolderLayout.Frozen(frozen).dir)
    }

    @Test
    fun aFrozenLayoutWithoutAChosenDirectionTakesTheDefaultOne() {
        val unchosen = FolderLayout.Frozen(frozen, dirChosen = false)
        assertEquals(frozen.copy(dir = defaults.dir), unchosen.over(defaults))
        assertFalse(unchosen.dirChosen)
    }

    @Test
    fun thawingKeepsOnlyAChosenDirection() {
        assertEquals(FolderLayout.Direction("rtl"), FolderLayout.Frozen(frozen).thawed())
        assertNull(FolderLayout.Frozen(frozen, dirChosen = false).thawed())
    }

    @Test
    fun bothFormsReadBackAsWritten() {
        val column = ReaderLayout("continuous", "ttb", "width", spacing = 0)
        val forms = listOf(
            FolderLayout.Direction("rtl"), FolderLayout.Direction("ltr"), FolderLayout.Direction("ttb"),
            FolderLayout.Frozen(frozen), FolderLayout.Frozen(frozen, false), FolderLayout.Frozen(column)
        )
        for (layout in forms) {
            val stored = JSONObject(layout.encode().toString())
            assertEquals(layout, FolderLayout.decode(stored))
        }
    }

    @Test
    fun oddValuesFallBack() {
        assertEquals(FolderLayout.Direction("ltr"), FolderLayout.decode(JSONObject("""{"dir":"sideways"}""")))
        val odd = FolderLayout.decode(JSONObject("""{"all":true,"mode":"sideways","dir":"rtl","two":"maybe","gap":400}"""))
        assertEquals(FolderLayout.Frozen(ReaderLayout("paged", "rtl", "screen", TwoPage.OFF, false, Prefs.PAGE_SPACING_MAX)), odd)
    }

    @Test
    fun theLibraryKeepsFolderLayoutsAndTheirDirections() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        val library = Library(context)
        library.setFolderLayout("/a", FolderLayout.Direction("rtl"))
        library.setFolderLayout("/c", FolderLayout.Frozen(frozen))
        library.setFolderLayout("/e", FolderLayout.Frozen(ReaderLayout("continuous", "ttb", "width")))
        assertEquals(FolderLayout.Direction("rtl"), library.folderLayout("/a"))
        assertEquals(FolderLayout.Frozen(frozen), library.folderLayout("/c"))
        assertEquals(ReadingDirection.TTB, library.folderDirections()["/e"])
        assertEquals(ReadingDirection.RTL, library.folderDirections()["/a"])
        library.setFolderLayout("/d", FolderLayout.Frozen(frozen, dirChosen = false))
        assertFalse("/d" in library.folderDirections())
        library.setFolderLayout("/a", null)
        assertNull(library.folderLayout("/a"))
    }
}
