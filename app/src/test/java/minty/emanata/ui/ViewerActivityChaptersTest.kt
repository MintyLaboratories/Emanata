package minty.emanata.ui

import minty.emanata.testing.idleUntil
import minty.emanata.testing.ref
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.sidesheet.SideSheetDialog
import minty.emanata.R
import minty.emanata.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The chapter drawer lists a book's folders and its folder's files, highlights the current ones and goes to a tapped one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivityChaptersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun book(name: String, entries: List<String>, dir: File = tmp.root): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            for (e in entries) {
                zip.putNextEntry(ZipEntry(e))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
        return f
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(file: File, page: Int): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, file.absolutePath)
            .putExtra(ViewerActivity.EXTRA_PAGE, page)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        idleUntil("the book open") { progress.visibility != View.VISIBLE }
        viewer.invalidateOptionsMenu()
        idle()
        return viewer
    }

    private fun ViewerActivity.drawerButtonShown() =
        findViewById<Toolbar>(R.id.viewer_toolbar).menu.findItem(R.id.menu_book_chapters).isVisible

    private fun ViewerActivity.page(): Int = findViewById<SeekBar>(R.id.viewer_seek).progress

    /** Opens the drawer and returns it with its list laid out. */
    private fun ViewerActivity.openDrawer(): Pair<SideSheetDialog, RecyclerView> {
        shadowOf(this).clickMenuItem(R.id.menu_book_chapters)
        idle()
        val dialog = latestDialogOf(this) as SideSheetDialog
        val list = dialog.findViewById<RecyclerView>(R.id.chapters_list)!!
        list.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 720, 2000)
        return dialog to list
    }

    private fun RecyclerView.row(i: Int): View = findViewHolderForAdapterPosition(i)!!.itemView

    private val parts = listOf("Part 1/01.png", "Part 1/02.png", "Part 2/01.png", "Part 2/02.png", "Part 3/01.png")

    @Test
    fun aBookWithoutFoldersHasNoDrawer() {
        assertFalse(launch(book("flat.cbz", listOf("01.png", "02.png")), 0).drawerButtonShown())
    }

    @Test
    fun listsTheFoldersWithTheCurrentOneHighlighted() {
        val viewer = launch(book("parts.cbz", parts), 3)
        assertTrue(viewer.drawerButtonShown())
        val (_, list) = viewer.openDrawer()
        // A heading, then the three folders; the book is alone in its folder, so no folder section.
        assertEquals(4, list.adapter!!.itemCount)
        assertEquals(context.getString(R.string.chapters_in_book), list.row(0).findViewById<TextView>(R.id.chapters_section).text.toString())
        assertEquals("Part 2", list.row(2).findViewById<TextView>(R.id.chapter_name).text.toString())
        assertEquals(context.getString(R.string.chapter_pages, 3, 4), list.row(2).findViewById<TextView>(R.id.chapter_pages).text.toString())
        assertEquals(context.getString(R.string.chapter_page, 5), list.row(3).findViewById<TextView>(R.id.chapter_pages).text.toString())
        assertFalse(list.row(1).isActivated)
        assertTrue(list.row(2).isActivated)
        assertFalse(list.row(3).isActivated)
    }

    @Test
    fun tappingAChapterJumpsToItsFirstPage() {
        val viewer = launch(book("parts.cbz", parts), 0)
        val (dialog, list) = viewer.openDrawer()
        list.row(3).performClick()
        idle()
        assertEquals(4, viewer.page())
        assertFalse(dialog.isShowing)
    }

    @Test
    fun filesInTheFolderShowAndOpen() {
        val shelf = tmp.newFolder("shelf")
        val first = book("Book 01.cbz", listOf("01.png", "02.png"), shelf)
        // Another book, not a copy of the first: one page more.
        val second = book("Book 02.cbz", listOf("01.png", "02.png", "03.png"), shelf)
        Library(context).setRead(ref(second), true)
        val viewer = launch(first, 0)
        assertTrue(viewer.drawerButtonShown())
        val (dialog, list) = viewer.openDrawer()
        assertEquals(3, list.adapter!!.itemCount)
        assertEquals(context.getString(R.string.chapters_in_folder), list.row(0).findViewById<TextView>(R.id.chapters_section).text.toString())
        assertEquals("Book 01", list.row(1).findViewById<TextView>(R.id.chapter_name).text.toString())
        assertTrue(list.row(1).isActivated)
        assertEquals(View.GONE, list.row(1).findViewById<View>(R.id.chapter_pages).visibility)
        assertEquals(context.getString(R.string.chapter_read), list.row(2).findViewById<TextView>(R.id.chapter_pages).text.toString())
        list.row(2).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(second.absolutePath, shadowOf(viewer).nextStartedActivity.getStringExtra(ViewerActivity.EXTRA_PATH))
    }
}
