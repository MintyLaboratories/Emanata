package com.infinity.comicreader.util

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.BookRef
import com.infinity.comicreader.data.FolderLayout
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Likeness
import com.infinity.comicreader.data.LikenessStore
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.Share
import com.infinity.comicreader.net.SharePath
import com.infinity.comicreader.net.ShareStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

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
    fun theLikenessOfBooksWithDataRoundTrips() {
        val store = LikenessStore(context)
        store.clear()
        val kept = BookRef(BookId(0x1a), "/books/kept.cbz")
        Library(context).setRead(kept, true)
        val like = Likeness.of(null, listOf("p01.jpg", "p02.jpg"), "kept.cbz")
        store.put(kept.id, like, "kept.cbz", time = 5)
        // Opened once, nothing kept: no copy of it can take anything over.
        store.put(BookId(0x2b), Likeness.of(null, listOf("x.jpg"), "other.cbz"), "other.cbz", time = 6)
        val json = Backup.export(context)
        assertEquals(1, JSONObject(json).getJSONArray("likeness").length())

        store.clear()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(Backup.import(context, json))
        assertEquals(LikenessStore.Row(kept.id, like, "kept.cbz", 5), store.get(kept.id))
        assertNull(store.get(BookId(0x2b)))
    }

    @Test
    fun malformedLikenessRowsAreSkipped() {
        val store = LikenessStore(context)
        store.clear()
        val rows = org.json.JSONArray()
            .put(JSONObject().put("id", "zz").put("print", "p").put("strong", true).put("stems", org.json.JSONArray()).put("name", "a").put("time", 1))
            .put(JSONObject().put("id", BookId(0x3c).hex).put("print", "p").put("strong", "yes").put("stems", org.json.JSONArray()).put("name", "a").put("time", 1))
            .put(JSONObject().put("id", BookId(0x4d).hex).put("print", "p").put("strong", false).put("stems", org.json.JSONArray().put("a\nb")).put("name", "a").put("time", 1))
        assertTrue(Backup.import(context, JSONObject(backup("Emanata")).put("likeness", rows).toString()))
        assertNull(store.get(BookId(0x3c)))
        assertNull(store.get(BookId(0x4d)))
    }

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

    @Test
    fun sharesRoundTripWithPasswordsSealedForThisDevice() {
        val before = ShareStore.defaultBox
        ShareStore.defaultBox = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        try {
            val shares = context.getSharedPreferences("shares", Context.MODE_PRIVATE)
            shares.edit().clear().commit()
            val share = Share("a1b2c3", "Share A", SharePath("nas.local", 445, "books", ""), "user", "pw")
            ShareStore(context).put(share)
            val sealed = Backup.export(context, deviceId = "device-1")
            val bare = Backup.export(context, withPasswords = false, deviceId = "device-1")
            assertFalse(sealed.contains("\"pw\""))

            shares.edit().clear().commit()
            assertTrue(Backup.import(context, sealed, deviceId = "device-1"))
            assertEquals(listOf(share), ShareStore(context).all())

            shares.edit().clear().commit()
            assertTrue(Backup.import(context, bare, deviceId = "device-1"))
            assertEquals(listOf(share.copy(password = "", passwordLost = true)), ShareStore(context).all())

            // A working password here outlives a backup without passwords, or an older one.
            ShareStore(context).put(share.copy(password = "pw-new"))
            assertTrue(Backup.import(context, bare, deviceId = "device-1"))
            assertEquals(listOf(share.copy(password = "pw-new")), ShareStore(context).all())
            assertTrue(Backup.import(context, sealed, deviceId = "device-1"))
            assertEquals(listOf(share.copy(password = "pw-new")), ShareStore(context).all())
        } finally {
            ShareStore.defaultBox = before
        }
    }
}
