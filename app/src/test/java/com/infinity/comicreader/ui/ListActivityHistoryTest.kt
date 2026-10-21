package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** History rows open their book; their menu offers the folder next to Continue. */
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
        Library(context).addHistory(HistoryItem(book.absolutePath, book.name, 4, 10, 1L))
        Prefs(context).startupTab = Prefs.TAB_HISTORY
    }

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    private fun launch(): Pair<ListActivity, RecyclerView> {
        val activity = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        idle()
        val recycler = activity.findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 2000)
        return activity to recycler
    }

    @Test
    fun tapOpensTheBookWhereTheResumeSettingSays() {
        val (activity, recycler) = launch()
        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        idle()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ViewerActivity::class.java.name, started.component?.className)
        assertEquals(book.absolutePath, started.getStringExtra(ViewerActivity.EXTRA_PATH))
        assertEquals(-1, started.getIntExtra(ViewerActivity.EXTRA_PAGE, 0))
    }

    @Test
    fun longPressOffersShowInFolderRightAfterContinue() {
        val (_, recycler) = launch()
        recycler.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
        idle()
        val list = (ShadowDialog.getLatestDialog() as AlertDialog).listView
        val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        val at = labels.indexOf(context.getString(R.string.menu_continue))
        assertEquals(context.getString(R.string.menu_open_location), labels[at + 1])
        assertEquals(1, labels.count { it == context.getString(R.string.menu_open_location) })
    }
}
