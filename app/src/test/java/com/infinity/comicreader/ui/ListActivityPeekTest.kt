package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w900dp-h1400dp")
class ListActivityPeekTest {

    private lateinit var book: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", 0).edit().clear().commit()
        val dir = File(Environment.getExternalStorageDirectory(), "attic/Series B").apply { mkdirs() }
        val page = TestImages.png(4, 6)
        book = Fixtures.zip(File(dir, "Vol 1.cbz"), "01.png" to page, "02.png" to page, "03.png" to page)
        File(dir, "Extras").mkdirs()
        Prefs(context).lastPath = dir.absolutePath
    }

    private fun idle(ms: Long = 0) {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms / 10 + 20))
            Thread.sleep(20)
        }
    }

    private fun ListActivity.peek(): PeekOverlay {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        return (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<PeekOverlay>().single()
    }

    private fun ListActivity.longPress(file: File) {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        val position = (recycler.adapter as BookAdapter).positionOf(file.absolutePath)
        recycler.findViewHolderForAdapterPosition(position)!!.itemView.performLongClick()
    }

    private fun AlertDialog.click(label: String) {
        val items = (0 until listView.adapter.count).map { listView.adapter.getItem(it).toString() }
        listView.performItemClick(null, items.indexOf(label), 0)
    }

    @Test
    fun peekFromTheMenuOpensAndBackClosesIt() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.longPress(book)
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        menu.click("Peek")
        idle(500)
        val peek = activity.peek()
        assertTrue(peek.isShowing)
        assertEquals(View.VISIBLE, peek.visibility)
        assertEquals(1f, peek.progress, 0f)

        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle(500)
        assertFalse(peek.isShowing)
        assertEquals(View.GONE, peek.visibility)
        // Back closed the peek, not the folder.
        assertEquals(book.parentFile!!.absolutePath, Prefs(activity).lastPath)
    }

    @Test
    fun foldersOfferNoPeek() {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        activity.longPress(File(book.parentFile, "Extras"))
        val menu = ShadowDialog.getLatestDialog() as AlertDialog
        val items = (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it).toString() }
        assertFalse("Peek" in items)
    }
}
