package com.infinity.comicreader.util

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun backup(app: String) = JSONObject()
        .put("app", app)
        .put("version", 1)
        .put("library", JSONObject().put("favorites", org.json.JSONArray().put("/books/attic")))
        .put("prefs", JSONObject().put("pref_sort", "date"))
        .toString()

    @Test
    fun exportIsNamedEmanata() {
        assertEquals("Emanata", JSONObject(Backup.export(context)).getString("app"))
    }

    @Test
    fun restoresOwnAndUpstreamBackups() {
        for (app in listOf("Emanata", "ComicReader")) {
            setUp()
            assertTrue(app, Backup.import(context, backup(app)))
            assertEquals(listOf("/books/attic"), Library(context).favorites())
            // The old single sort moved to Browse: "date" ascending is oldest added first.
            assertEquals(SortLevel(SortKey.ADDED, flipped = true), Prefs(context).sort(Prefs.TAB_BROWSE).by)
        }
    }

    @Test
    fun rejectsOtherApps() {
        assertFalse(Backup.import(context, backup("SomethingElse")))
        assertTrue(Library(context).favorites().isEmpty())
    }

    @Test
    fun restoredLegacyFolderLayoutsAreSortedAgainstTheRestoredDefaults() {
        fun legacy(fit: String) = JSONObject().put("mode", "paged").put("dir", "rtl").put("fit", fit).put("two", false)
        val json = JSONObject()
            .put("app", "Emanata")
            .put("version", 1)
            .put("library", JSONObject().put("series", JSONObject().put("/books/a", legacy("width")).put("/books/b", legacy("height"))))
            .put("prefs", JSONObject().put("pref_fit", "width"))
            .toString()
        assertTrue(Backup.import(context, json))
        val library = Library(context)
        assertEquals(FolderLayout.Direction("rtl"), library.folderLayout("/books/a"))
        assertTrue(library.folderLayout("/books/b") is FolderLayout.Frozen)
        assertFalse(FolderLayout.isLegacy(JSONObject(context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("series", "{}")!!).getJSONObject("/books/b")))
    }
}
