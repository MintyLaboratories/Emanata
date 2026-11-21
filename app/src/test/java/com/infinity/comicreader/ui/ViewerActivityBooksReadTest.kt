package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.reader.TestImages
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.testing.ref
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
import java.io.File

/** Reading the last unread image of a folder of loose images counts the folder as one book read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerActivityBooksReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var library: Library

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        Prefs(context).resumeMode = ResumeMode.NO
        library = Library(context)
    }

    @Test
    fun theLastImageReadCountsItsFolderOnce() {
        val dir = tmp.newFolder("Loose")
        val images = (1..2).map { i -> File(dir, "$i.png").apply { writeBytes(TestImages.png(100 + i, 140)) } }
        library.setRead(ref(images[0]), true)
        assertEquals(0, library.stats().books)
        val intent = Intent(ApplicationProvider.getApplicationContext(), ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, images[1].absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        idleUntil("its folder counted read") { library.isFolderRead(dir.absolutePath) }
        assertTrue(library.isRead(ref(images[1]).id))
        assertEquals(1, library.stats().books)
    }
}
