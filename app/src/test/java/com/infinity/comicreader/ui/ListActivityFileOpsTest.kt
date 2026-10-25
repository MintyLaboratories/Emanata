package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.files.FileClipboard
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import javax.crypto.spec.SecretKeySpec

/** The long-press menu's file operations, the Paste button, and the library following what they change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityFileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var shelf: File
    private lateinit var other: File
    private lateinit var book: File
    private lateinit var bytes: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds", "shares")) context.getSharedPreferences(name, 0).edit().clear().commit()
        shelf = File(Environment.getExternalStorageDirectory(), "shelf").apply {
            deleteRecursively()
            mkdirs()
        }
        other = File(shelf, "Other").apply { mkdirs() }
        bytes = ByteArray(5000) { (it * 7).toByte() }
        book = File(shelf, "one.cbz").apply { writeBytes(bytes) }
        library = Library(context)
        library.toggleFavorite(book.absolutePath)
        library.addHistory(HistoryItem(book.absolutePath, book.name, 3, 10, 1L))
        library.setRead(book.absolutePath, true)
        Prefs(context).lastPath = shelf.absolutePath
        // No covers: one being read would hold its file open, which some test hosts refuse to rename.
        Prefs(context).thumbnails = false
        FileClipboard.clip = null
        // Library screens other tests left open watch the old state, so only this test's show outcomes.
        FileWork.reset()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    @After
    fun tearDown() {
        settle()
        launched.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        FileClipboard.clip = null
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    /** Waits until [done], then for the file operation under way to end and its outcome to be shown. */
    private fun settle(done: () -> Boolean = { true }) {
        repeat(300) {
            idle()
            if (done() && FileWork.status.value == null) return
        }
        throw AssertionError("still running: ${FileWork.status.value}, toast ${ShadowToast.getTextOfLatestToast()}")
    }

    private fun AlertDialog.title(): String = findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.edit(): EditText = findEdit(window!!.decorView as android.view.ViewGroup)!!

    private val launched = ArrayList<ActivityController<ListActivity>>()

    /** A library screen; each goes at the end, so only the latest shows file operations' outcomes. */
    private fun launch(): ListActivity {
        launched.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        return Robolectric.buildActivity(ListActivity::class.java).also { launched += it }.setup().get().also { idle() }
    }

    private fun ListActivity.recycler(): RecyclerView = findViewById<RecyclerView>(R.id.recycler).apply {
        measure(0, 0)
        layout(0, 0, 1000, 4000)
    }

    /** The row of [path], waiting for a listing under way to show it. */
    private fun ListActivity.row(path: String): View {
        repeat(300) {
            val recycler = recycler()
            val adapter = recycler.adapter as BookAdapter
            recycler.findViewHolderForAdapterPosition(adapter.positionOf(path))?.let { return it.itemView }
            idle()
        }
        throw AssertionError("no row for $path")
    }

    /** Clicks Paste and waits for the dialog it asks with. */
    private fun ListActivity.pasteAsking(): AlertDialog {
        val before = ShadowDialog.getLatestDialog()
        paste().performClick()
        repeat(300) {
            idle()
            val now = ShadowDialog.getLatestDialog()
            if (now !== before && now.isShowing) return now as AlertDialog
        }
        throw AssertionError("nothing asked")
    }

    private fun ListActivity.longPress(path: String): AlertDialog {
        row(path).performLongClick()
        idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun AlertDialog.items(): List<String> = (0 until listView.adapter.count).map { listView.adapter.getItem(it).toString() }

    private fun AlertDialog.op(id: Int): View? = findViewById<View>(id)?.takeIf { (it.parent as View).visibility == View.VISIBLE }

    private fun latest() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun ListActivity.paste(): View = findViewById(R.id.paste_fab)

    private fun ListActivity.open(dir: File) {
        row(dir.absolutePath).performClick()
        idle()
    }

    @Test
    fun fileOperationsAreIconsAboveTheComicOperations() {
        val activity = launch()
        val menu = activity.longPress(book.absolutePath)
        for (id in listOf(R.id.file_op_rename, R.id.file_op_delete, R.id.file_op_move, R.id.file_op_copy)) assertNotNull(menu.op(id))
        // More shows only with something in it.
        assertNull(menu.op(R.id.file_op_more))
        assertFalse(menu.items().contains(context.getString(R.string.menu_rename)))
        assertFalse(menu.items().contains(context.getString(R.string.delete)))
        assertTrue(menu.items().contains(context.getString(R.string.menu_peek)))
        menu.dismiss()

        // A folder's startup choice is a comic operation; More holds nothing yet.
        val folder = activity.longPress(other.absolutePath)
        assertNull(folder.op(R.id.file_op_more))
        assertTrue(folder.items().contains(context.getString(R.string.menu_set_startup)))
    }

    @Test
    fun favoritesAndHistoryHaveNoFileOperations() {
        val activity = launch()
        for (tab in listOf(1, 2)) {
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
            idle()
            val menu = activity.longPress(book.absolutePath)
            assertEquals(View.GONE, menu.findViewById<LinearLayout>(R.id.file_ops)!!.visibility)
            menu.dismiss()
        }
    }

    @Test
    fun aLocationRootCanStillBeTheStartupFolder() {
        Prefs(context).lastPath = Environment.getExternalStorageDirectory().absolutePath
        val activity = launch()
        val menu = activity.longPress(shelf.absolutePath)
        assertTrue(menu.items().contains(context.getString(R.string.menu_set_startup)))
    }

    @Test
    fun bookmarksHaveNoFileOperations() {
        library.addBookmark(Bookmark(book.absolutePath, book.name, 1, "", 1L))
        val activity = launch()
        activity.findViewById<TabLayout>(R.id.tabs).getTabAt(3)!!.select()
        idle()
        val menu = activity.recycler().findViewHolderForAdapterPosition(0)!!.itemView.let {
            it.performLongClick()
            idle()
            latest()
        }
        assertEquals(View.GONE, menu.findViewById<LinearLayout>(R.id.file_ops)!!.visibility)
    }

    @Test
    fun renamingTakesTheLibraryAlong() {
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_rename)!!.performClick()
        idle()
        val ask = latest()
        ask.edit().setText("two.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        val renamed = File(shelf, "two.cbz")
        settle { renamed.exists() }
        assertTrue(renamed.isFile)
        assertFalse(book.exists())
        assertTrue(library.isFavorite(renamed.absolutePath))
        assertTrue(library.isRead(renamed.absolutePath))
        assertEquals(3, library.historyFor(renamed.absolutePath)?.page)
        assertEquals(context.getString(R.string.renamed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun renamingChangesTheCaseOnly() {
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_rename)!!.performClick()
        idle()
        val ask = latest()
        ask.edit().setText("One.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { "One.cbz" in shelf.list()!! }
        assertEquals(listOf("One.cbz", "Other"), shelf.list()!!.sorted())
        assertTrue(library.isFavorite(File(shelf, "One.cbz").absolutePath))
        assertEquals(context.getString(R.string.renamed), ShadowToast.getTextOfLatestToast())
    }

    private fun findEdit(group: android.view.ViewGroup): EditText? {
        for (i in 0 until group.childCount) {
            when (val c = group.getChildAt(i)) {
                is EditText -> return c
                is android.view.ViewGroup -> findEdit(c)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun deletingAsksFirstAndForgetsTheItem() {
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_delete)!!.performClick()
        idle()
        latest().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(book.isFile)

        activity.longPress(book.absolutePath).op(R.id.file_op_delete)!!.performClick()
        idle()
        val ask = latest()
        assertEquals(context.getString(R.string.delete_item_title, book.name), ask.title())
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !book.exists() }
        assertFalse(book.exists())
        assertFalse(library.isFavorite(book.absolutePath))
        assertNull(library.historyFor(book.absolutePath))
    }

    @Test
    fun aDeletedFolderLeavesNoForwardCrumb() {
        val activity = launch()
        activity.open(other)
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle()
        val bar = activity.findViewById<CrumbBar>(R.id.pathbar)
        assertTrue(bar.crumbs.any { it.label == "Other" })
        activity.longPress(other.absolutePath).op(R.id.file_op_delete)!!.performClick()
        idle()
        latest().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { !other.exists() }
        assertFalse(bar.crumbs.any { it.label == "Other" })
    }

    @Test
    fun movePicksUpAndPasteMovesIntoTheFolderBrowsed() {
        val activity = launch()
        assertEquals(View.GONE, activity.paste().visibility)
        activity.longPress(book.absolutePath).op(R.id.file_op_move)!!.performClick()
        idle()
        assertEquals(View.VISIBLE, activity.paste().visibility)
        activity.open(other)
        activity.paste().performClick()
        val moved = File(other, "one.cbz")
        settle { !book.exists() }
        assertArrayEquals(bytes, moved.readBytes())
        assertFalse(book.exists())
        assertTrue(library.isFavorite(moved.absolutePath))
        assertEquals(3, library.historyFor(moved.absolutePath)?.page)
        // A move is pasted once.
        assertNull(FileClipboard.clip)
        assertEquals(View.GONE, activity.paste().visibility)
    }

    @Test
    fun aTakenNameAsksReplaceKeepBothOrSkip() {
        File(other, "one.cbz").writeBytes(ByteArray(3))
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_copy)!!.performClick()
        idle()
        activity.open(other)

        val ask = activity.pasteAsking()
        assertEquals(context.getString(R.string.clash_title, "one.cbz"), ask.title())
        ask.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(listOf("one.cbz"), other.list()!!.sorted())

        activity.pasteAsking().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle { File(other, "one (2).cbz").exists() }
        assertEquals(listOf("one (2).cbz", "one.cbz"), other.list()!!.sorted())
        assertArrayEquals(bytes, File(other, "one (2).cbz").readBytes())

        activity.pasteAsking().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(other, "one.cbz").length() == bytes.size.toLong() }
        assertArrayEquals(bytes, File(other, "one.cbz").readBytes())
        // A copy stays picked up; the source stays.
        assertTrue(book.isFile)
        assertNotNull(FileClipboard.clip)

        // A long press on Paste drops it.
        activity.paste().performLongClick()
        idle()
        assertNull(FileClipboard.clip)
        assertEquals(context.getString(R.string.paste_canceled), ShadowToast.getTextOfLatestToast())
        assertEquals(View.GONE, activity.paste().visibility)
    }

    @Test
    fun aNameTakenInAnotherCaseAsksToo() {
        File(other, "ONE.cbz").writeBytes(ByteArray(3))
        // Only where the storage ignores case, as the device's shared storage does.
        assumeTrue(File(other, "one.cbz").exists())
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_copy)!!.performClick()
        idle()
        activity.open(other)
        activity.pasteAsking().getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
        settle { other.list()!!.size == 2 }
        assertEquals(listOf("ONE.cbz", "one (2).cbz"), other.list()!!.sorted())
    }

    @Test
    fun replacingAFolderSaysItIsReplacedWholeAndNeverWhatHoldsTheItem() {
        File(shelf, "Sub").mkdirs()
        File(other, "Sub").mkdirs()
        File(other, "Other").mkdirs()
        val activity = launch()
        activity.longPress(File(shelf, "Sub").absolutePath).op(R.id.file_op_copy)!!.performClick()
        idle()
        activity.open(other)
        val ask = activity.pasteAsking()
        assertEquals(context.getString(R.string.clash_folder), ask.findViewById<android.widget.TextView>(android.R.id.message)!!.text.toString())
        ask.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()

        // An item inside the folder its name takes: no Replace.
        activity.longPress(File(other, "Other").absolutePath).op(R.id.file_op_move)!!.performClick()
        idle()
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        idle()
        val inside = activity.pasteAsking()
        assertEquals(View.GONE, inside.getButton(DialogInterface.BUTTON_POSITIVE).visibility)
        assertEquals(View.VISIBLE, inside.getButton(DialogInterface.BUTTON_NEUTRAL).visibility)
    }

    @Test
    fun aCopyBesideItselfKeepsBoth() {
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_copy)!!.performClick()
        idle()
        activity.paste().performClick()
        settle { File(shelf, "one (2).cbz").exists() }
        assertArrayEquals(bytes, File(shelf, "one (2).cbz").readBytes())
    }

    @Test
    fun aFolderNeverGoesInsideItself() {
        val activity = launch()
        activity.longPress(other.absolutePath).op(R.id.file_op_move)!!.performClick()
        idle()
        activity.open(other)
        activity.paste().performClick()
        idle()
        assertEquals(context.getString(R.string.paste_into_itself), ShadowToast.getTextOfLatestToast())
        assertTrue(other.isDirectory)
    }

    @Test
    fun listingAFolderPutsBackWhatAnUnfinishedMoveSetAside() {
        File(other, ".kept.cbz.1.emanata-old").writeBytes(bytes)
        File(other, ".copy.cbz.1.emanata-new").writeBytes(ByteArray(3))
        val activity = launch()
        activity.open(other)
        activity.row(File(other, "kept.cbz").absolutePath)
        assertEquals(listOf("kept.cbz"), other.list()!!.toList())
        assertArrayEquals(bytes, File(other, "kept.cbz").readBytes())
    }

    @Test
    fun cancelShowsOnlyWhileItCanStopTheStepUnderWay() {
        val activity = launch()
        val clock = FileWork.clock
        var now = 1_000L
        FileWork.clock = { now }
        val go = java.util.concurrent.Semaphore(0)
        val at = java.util.concurrent.Semaphore(0)
        try {
            FileWork.start(FileWork.Kind.COPY, "big.cbz") { p ->
                at.release()
                go.acquire()
                p.report(5, 10)
                at.release()
                go.acquire()
                p.stoppable(false)
                at.release()
                go.acquire()
                FileWork.Outcome.Done(null)
            }
            at.acquire()
            // Long enough under way for its progress to show.
            now = 2_000L
            go.release()
            at.acquire()
            idle()
            val dialog = latest()
            assertEquals(View.VISIBLE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
            go.release()
            at.acquire()
            idle()
            assertEquals(View.GONE, dialog.getButton(DialogInterface.BUTTON_NEGATIVE).visibility)
            go.release()
            settle()
        } finally {
            FileWork.clock = clock
        }
        assertNotNull(activity)
    }

    /** Serves a share from a folder, returned. */
    private fun serveShare(): File {
        val shareDir = tmp.newFolder("share")
        val fake = FakeRemoteFs(shareDir)
        ShareStore.defaultBox = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        ShareStore(context).put(FakeRemoteFs.share())
        Remotes.init(context)
        Remotes.connector = { fake }
        return shareDir
    }

    @Test
    fun makesNewFoldersOnShares() {
        val shareDir = serveShare()
        Prefs(context).lastPath = "/net/s1"
        val activity = launch()
        val item = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.menu_new_folder)
        activity.onOptionsItemSelected(item)
        idle()
        val ask = latest()
        ask.edit().setText("Fresh")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(shareDir, "Fresh").isDirectory }
        activity.row("/net/s1/Fresh")
    }

    @Test
    fun copiesOntoAShareAndRenamesThere() {
        val shareDir = serveShare()
        val activity = launch()
        activity.longPress(book.absolutePath).op(R.id.file_op_copy)!!.performClick()
        idle()
        Prefs(context).lastPath = "/net/s1"
        val share = launch()
        assertEquals(View.VISIBLE, share.paste().visibility)
        share.paste().performClick()
        settle { File(shareDir, "one.cbz").exists() }
        assertArrayEquals(bytes, File(shareDir, "one.cbz").readBytes())
        // Only the finished copy is there.
        assertEquals(listOf("one.cbz"), shareDir.list()!!.toList())

        share.longPress("/net/s1/one.cbz").op(R.id.file_op_rename)!!.performClick()
        idle()
        val ask = latest()
        ask.edit().setText("renamed.cbz")
        ask.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle { File(shareDir, "renamed.cbz").exists() }
        assertEquals(listOf("renamed.cbz"), shareDir.list()!!.toList())
    }
}
