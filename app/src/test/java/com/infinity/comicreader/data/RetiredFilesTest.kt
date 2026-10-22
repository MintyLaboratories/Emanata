package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RetiredFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefsFile(name: String) = File(context.dataDir, "shared_prefs/$name.xml")

    @Test
    fun dropsRetiredPreferencesAndKeepsTheRest() {
        context.getSharedPreferences("book_facts", Context.MODE_PRIVATE).edit().putString("/a.cbz", "1:2:3").commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().putString("k", "v").commit()
        assertTrue(prefsFile("book_facts").exists())

        RetiredFiles.drop(context)

        assertFalse(prefsFile("book_facts").exists())
        assertEquals("v", context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("k", null))
    }

    @Test
    fun dropsRetiredCacheFoldersAndKeepsTheRest() {
        val old = File(context.cacheDir, "thumbnails").apply { mkdirs() }
        File(old, "a.webp").writeText("x")
        File(context.cacheDir, "previews").mkdirs()
        val kept = File(context.cacheDir, "covers").apply { mkdirs() }

        RetiredFiles.drop(context)

        assertFalse(old.exists())
        assertFalse(File(context.cacheDir, "previews").exists())
        assertTrue(kept.isDirectory)
    }

    @Test
    fun droppingTwiceIsHarmless() {
        RetiredFiles.drop(context)
        RetiredFiles.drop(context)
        assertFalse(prefsFile("book_facts").exists())
    }
}
