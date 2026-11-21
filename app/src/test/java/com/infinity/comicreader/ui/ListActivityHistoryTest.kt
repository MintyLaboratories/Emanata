package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.Manifest
import android.app.Application
import android.os.Environment
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.MoreAction
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** History rows open their book; selected, the button continues it and the menu shows its folder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityHistoryTest {

    private lateinit var context: Application
    private lateinit var book: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library")) context.getSharedPreferences(name, 0).edit().clear().commit()
        val dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        book = File(dir, "one.cbz").apply { createNewFile() }
        Library(context).addHistory(HistoryItem(book.absolutePath, book.name, 4, 10, 1L, bookId(book)))
        Prefs(context).startupTab = Prefs.TAB_HISTORY
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isActive == true }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    private fun launch(): Pair<ListActivity, RecyclerView> {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        activity.settle()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 2000)
        return activity to recycler
    }

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun tapOpensTheBookWhereTheResumeSettingSays() {
        val (activity, recycler) = launch()
        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        activity.settle()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ViewerActivity::class.java.name, started.component?.className)
        assertEquals(book.absolutePath, started.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(-1, started.getIntExtra(ViewerActivity.EXTRA_PAGE, 0))
    }

    @Test
    fun selectedTheButtonContinuesAndTheMenuShowsTheFolder() {
        val (activity, recycler) = launch()
        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
        activity.settle()
        activity.findViewById<View>(R.id.selection_more).performClick()
        activity.settle()
        val menu = activity.moreMenu!!
        assertTrue((0 until menu.childCount).any { menu.getChildAt(it).tag == MoreAction.SHOW_IN_FOLDER })
        // The floating button opens it where reading goes on.
        val fab = activity.findViewById<View>(R.id.fab)
        assertEquals(context.getString(R.string.fab_open), fab.contentDescription)
        fab.performClick()
        activity.settle()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(book.absolutePath, started.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(-1, started.getIntExtra(ViewerActivity.EXTRA_PAGE, 0))
        assertFalse(activity.selection.active)
    }
}
