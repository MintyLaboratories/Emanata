package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A folder keeps a direction alone or a frozen layout; older entries are sorted into one of the two. */
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
            assertFalse(FolderLayout.isLegacy(stored))
            assertEquals(layout, FolderLayout.decode(stored))
        }
    }

    @Test
    fun oddValuesFallBack() {
        assertEquals(FolderLayout.Direction("ltr"), FolderLayout.decode(JSONObject("""{"dir":"sideways"}""")))
        val odd = FolderLayout.decode(JSONObject("""{"all":true,"mode":"sideways","dir":"rtl","two":"maybe","gap":400}"""))
        assertEquals(FolderLayout.Frozen(ReaderLayout("paged", "rtl", "screen", TwoPage.OFF, false, Prefs.PAGE_SPACING_MAX)), odd)
    }

    private fun legacy(mode: String = "paged", fit: String = "width", two: Boolean = false) =
        JSONObject().put("mode", mode).put("dir", "rtl").put("fit", fit).put("two", two)

    @Test
    fun aLegacyEntryReadsAsFrozenWithTheDefaultsFilledIn() {
        assertTrue(FolderLayout.isLegacy(legacy()))
        assertEquals(
            FolderLayout.Frozen(ReaderLayout("paged", "rtl", "width", TwoPage.ON, autoCrop = true, spacing = 8)),
            FolderLayout.decode(legacy(two = true), defaults)
        )
    }

    @Test
    fun retiredModesReadTopToBottomWithTheDirectionChosen() {
        // Vertical paged one page a screen; the long strip was one column that never cropped.
        assertEquals(
            FolderLayout.Frozen(ReaderLayout("paged", "ttb", "width", TwoPage.ON, autoCrop = true, spacing = 8)),
            FolderLayout.decode(legacy(mode = "vertical", two = true), defaults)
        )
        assertEquals(
            FolderLayout.Frozen(ReaderLayout("continuous", "ttb", "width", autoCrop = false, spacing = 8)),
            FolderLayout.decode(legacy(mode = "longstrip"), defaults)
        )
        val unchosen = JSONObject().put("all", true).put("mode", "longstrip").put("dir", "rtl").put("chosen", false)
        assertTrue(FolderLayout.hasRetiredMode(unchosen))
        assertFalse(FolderLayout.isLegacy(unchosen))
        assertEquals(FolderLayout.Frozen(ReaderLayout("continuous", "ttb")), FolderLayout.decode(unchosen))
    }

    @Test
    fun aRetiredModeMatchingTheDefaultsKeepsOnlyTopToBottom() {
        val column = ReaderLayout("continuous", "ttb", "width")
        assertEquals(FolderLayout.Direction("ttb"), FolderLayout.migrate(legacy(mode = "longstrip"), column))
        assertEquals(FolderLayout.Direction("ttb"), FolderLayout.migrate(legacy(mode = "vertical"), column.copy(mode = "paged")))
    }

    @Test
    fun aLegacyEntryMatchingTheDefaultsKeepsOnlyItsDirection() {
        assertEquals(FolderLayout.Direction("rtl"), FolderLayout.migrate(legacy(), defaults))
    }

    @Test
    fun aLegacyEntryThatDiffersStaysFrozen() {
        for (entry in listOf(legacy(mode = "longstrip"), legacy(fit = "screen"), legacy(two = true))) {
            assertTrue(FolderLayout.migrate(entry, defaults) is FolderLayout.Frozen)
        }
    }

    @Test
    fun theLibraryMigratesOnlyLegacyEntries() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
            .putString(
                "series",
                JSONObject().put("/a", legacy()).put("/b", legacy(mode = "longstrip")).put("/c", FolderLayout.Frozen(frozen).encode())
                    .put("/e", JSONObject().put("all", true).put("mode", "vertical").put("dir", "ltr").put("fit", "screen")).toString()
            )
            .commit()
        val library = Library(context)
        library.migrateFolderLayouts(defaults)
        assertEquals(FolderLayout.Direction("rtl"), library.folderLayout("/a"))
        assertEquals(FolderLayout.Frozen(ReaderLayout("continuous", "ttb", "width", TwoPage.OFF, false, 8)), library.folderLayout("/b"))
        assertEquals(FolderLayout.Frozen(frozen), library.folderLayout("/c"))
        // A frozen entry in a retired mode is written again in today's form.
        val stored = JSONObject(context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("series", null)!!)
        assertEquals("paged", stored.getJSONObject("/e").getString("mode"))
        assertEquals("ttb", stored.getJSONObject("/e").getString("dir"))
        assertEquals(ReadingDirection.TTB, library.folderDirections()["/e"])
        assertEquals(ReadingDirection.RTL, library.folderDirections()["/a"])
        library.setFolderLayout("/d", FolderLayout.Frozen(frozen, dirChosen = false))
        assertFalse("/d" in library.folderDirections())
        library.setFolderLayout("/a", null)
        assertNull(library.folderLayout("/a"))
    }
}
