package minty.emanata.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import minty.emanata.R
import minty.emanata.testing.idleUntil
import minty.emanata.data.BookId
import minty.emanata.data.Bookmark
import minty.emanata.data.DetailDisplay
import minty.emanata.data.FavoriteKind
import minty.emanata.data.FavoriteKindCache
import minty.emanata.data.HistoryItem
import minty.emanata.data.Library
import minty.emanata.data.MoreAction
import minty.emanata.data.Prefs
import minty.emanata.data.PresenceCheck
import minty.emanata.testing.bookId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Favorites, History and Bookmarks show items not where they were last seen faint and
 * marked, those that cannot be reached with an offline icon, and offer to take them out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityMissingTest {

    private lateinit var context: Application
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var dir: File
    private lateinit var here: File
    private lateinit var gone: File
    private var goneId = BookId(0)

    /** A book on a volume not mounted. */
    private val away = "/storage/1234-ABCD/Comics/away.cbz"
    private val awayId = BookId(0xa1)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        PresenceCheck.Memo.shared.clear()
        library = Library(context)
        prefs = Prefs(context)
        dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        here = File(dir, "here.cbz").apply { writeText("here") }
        gone = File(dir, "gone.cbz").apply { writeText("gone") }
        goneId = bookId(gone)
        prefs.lastPath = dir.absolutePath
    }

    /** True once the screen waits on nothing but the test: listed, favorites classified, facts read, presence asked, its menu looked into. */
    private fun ListActivity.quiet() =
        listJob?.isCompleted == true && listOf(classifyJob, factsJob, presenceJob, moreLook).none { it?.isCompleted == false }

    /** Runs the main looper until the screen is quiet. */
    private fun ListActivity.settle() = idleUntil("the library screen quiet") { quiet() }

    /** The screen [start] started last. */
    private lateinit var shown: ListActivity

    /** Runs the main looper until the screen started last is quiet. */
    private fun idle() = shown.settle()

    private fun history(file: File, id: BookId = bookId(file), time: Long = 1L) =
        library.addHistory(HistoryItem(file.absolutePath, file.name, 1, 10, time, id))

    private fun fillHistory() {
        history(here, time = 3L)
        history(gone, goneId, time = 2L)
        library.addHistory(HistoryItem(away, "away.cbz", 1, 10, 1L, awayId))
        gone.delete()
    }

    private fun launch(tab: Int = 2): Pair<ListActivity, RecyclerView> {
        val activity = start(tab).get()
        return activity to activity.laidOut()
    }

    /** The library started on [tab]. */
    private fun start(tab: Int = 2): ActivityController<ListActivity> {
        val controller = Robolectric.buildActivity(ListActivity::class.java).setup()
        shown = controller.get()
        idle()
        controller.get().findViewById<TabLayout>(R.id.tabs).getTabAt(tab)!!.select()
        idle()
        return controller
    }

    /** The list, its rows laid out. */
    private fun ListActivity.laidOut(): RecyclerView {
        val recycler = findViewById<RecyclerView>(R.id.recycler)
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 4000)
        return recycler
    }

    /** The row whose title is [name]. */
    private fun RecyclerView.row(name: String): View =
        (0 until childCount).map { getChildAt(it) }.first { (it.findViewById<View>(R.id.title) as? TitleView)?.text?.toString() == name }

    private fun RecyclerView.hasRow(name: String): Boolean =
        (0 until childCount).any { (getChildAt(it).findViewById<View>(R.id.title) as? TitleView)?.text?.toString() == name }

    /** What a row draws inside its card: faint for a missing item. */
    private fun View.body(): View = (this as android.view.ViewGroup).getChildAt(0)

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    private fun AlertDialog.press(which: Int) {
        getButton(which).performClick()
        idle()
    }

    private fun ListActivity.menuItem(id: Int) = findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(id)

    /** No work of this test's screens runs on into the next. */
    @After
    fun awaitLeftovers() = awaitLeftoverWork()

    @Test
    fun missingRowsAreFaintAndSaySoUnreachableOnesShowTheOfflineIcon() {
        fillHistory()
        val (_, recycler) = launch()
        val missing = recycler.row("gone.cbz")
        assertEquals(0.38f, missing.body().alpha, 0.001f)
        assertTrue(missing.findViewById<TextView>(R.id.subtitle).text.startsWith(context.getString(R.string.presence_missing)))
        assertEquals(View.GONE, missing.findViewById<View>(R.id.offline).visibility)

        val there = recycler.row("here.cbz")
        assertEquals(1f, there.body().alpha, 0.001f)
        assertFalse(there.findViewById<TextView>(R.id.subtitle).text.startsWith(context.getString(R.string.presence_missing)))

        val unreachable = recycler.row("away.cbz")
        assertEquals(1f, unreachable.body().alpha, 0.001f)
        assertEquals(View.VISIBLE, unreachable.findViewById<View>(R.id.offline).visibility)
    }

    @Test
    fun denseAndHiddenDetailsStillSayMissing() {
        fillHistory()
        for (detail in listOf(DetailDisplay.DENSE, DetailDisplay.HIDDEN)) {
            prefs.setDetail(Prefs.TAB_HISTORY, detail)
            val (_, recycler) = launch()
            val title = recycler.row("gone.cbz").findViewById<TitleView>(R.id.title)
            assertTrue("$detail", title.detail.toString().startsWith(context.getString(R.string.presence_missing)))
            val there = recycler.row("here.cbz").findViewById<TitleView>(R.id.title).detail
            if (detail == DetailDisplay.HIDDEN) assertNull(there) else assertFalse(there.toString().startsWith(context.getString(R.string.presence_missing)))
        }
    }

    @Test
    fun tappingAMissingRowOffersToRemoveIt() {
        fillHistory()
        val (activity, recycler) = launch()
        recycler.row("gone.cbz").performClick()
        idle()
        val sheet = activity.missingSheet
        assertNotNull(sheet)
        assertTrue(sheet!!.isShowing)
        assertNotNull(sheet.findViewById<View>(R.id.missing_show))
        assertNotNull(sheet.findViewById<View>(R.id.missing_forget))
        sheet.findViewById<View>(R.id.missing_remove)!!.performClick()
        idle()
        assertEquals(listOf("away.cbz", "here.cbz"), library.history().map { it.name })
        // Nothing opened the reader.
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun clearMissingTakesOnlyBooksMissingForSure() {
        fillHistory()
        val (activity, _) = launch()
        activity.invalidateOptionsMenu()
        idle()
        assertTrue(activity.menuItem(R.id.menu_clear_missing).isVisible)
        assertEquals(context.getString(R.string.menu_clear_missing_history), activity.menuItem(R.id.menu_clear_missing).title.toString())
        shadowOf(activity).clickMenuItem(R.id.menu_clear_missing)
        idle()
        val dialog = latestDialog()
        assertTrue(dialog.message().startsWith(context.resources.getQuantityString(R.plurals.confirm_clear_missing_history_msg, 1, 1)))
        assertEquals(context.getString(R.string.find_moved_first), dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString())
        dialog.press(DialogInterface.BUTTON_NEGATIVE)
        assertEquals(3, library.history().size)

        shadowOf(activity).clickMenuItem(R.id.menu_clear_missing)
        idle()
        latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        // The book on storage not mounted stays.
        assertEquals(listOf("away.cbz", "here.cbz"), library.history().map { it.name })
        activity.invalidateOptionsMenu()
        idle()
        assertFalse(activity.menuItem(R.id.menu_clear_missing).isVisible)
    }

    @Test
    fun findMovedBooksFirstOpensTheScan() {
        fillHistory()
        val (activity, _) = launch()
        shadowOf(activity).clickMenuItem(R.id.menu_clear_missing)
        idle()
        latestDialog().press(DialogInterface.BUTTON_NEUTRAL)
        assertEquals(MissingBooksActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        assertEquals(3, library.history().size)
    }

    @Test
    fun clearAllIsTitledForEachTabAndAsksFirst() {
        library.setFavorite(minty.emanata.data.BookRef(bookId(here), here.absolutePath), true)
        library.addBookmark(Bookmark(here.absolutePath, here.name, 2, "", 1L, bookId(here)))
        for ((tab, title, count) in listOf(
            Triple(1, R.string.menu_clear_all_favorites) { library.favoriteItems().size },
            Triple(3, R.string.menu_clear_all_bookmarks) { library.bookmarks().size }
        )) {
            val (activity, _) = launch(tab)
            activity.invalidateOptionsMenu()
            idle()
            assertEquals(context.getString(title), activity.menuItem(R.id.menu_clear_all).title.toString())
            assertFalse(activity.menuItem(R.id.menu_clear_missing).isVisible)
            shadowOf(activity).clickMenuItem(R.id.menu_clear_all)
            idle()
            latestDialog().press(DialogInterface.BUTTON_NEGATIVE)
            assertEquals(1, count())
            shadowOf(activity).clickMenuItem(R.id.menu_clear_all)
            idle()
            latestDialog().press(DialogInterface.BUTTON_POSITIVE)
            assertEquals(0, count())
        }
    }

    @Test
    fun aMissingFavoriteBookShowsNoSizeAndItsSheetOpensWhole() {
        library.setFavorite(minty.emanata.data.BookRef(goneId, gone.absolutePath), true)
        gone.delete()
        val (activity, recycler) = launch(1)
        val row = recycler.row("gone.cbz")
        assertEquals(context.getString(R.string.presence_missing), row.findViewById<TextView>(R.id.subtitle).text.toString())
        row.performClick()
        idle()
        assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED, activity.missingSheet!!.behavior.state)
    }

    @Test
    fun aMissingFavoriteFolderKeepsItsIconAndSection() {
        val series = File(dir, "Series").apply { mkdirs() }
        library.toggleFavoriteFolder(series.absolutePath)
        FavoriteKindCache(context).put(series.absolutePath, 1L, FavoriteKind.LIBRARY)
        series.delete()
        val (activity, recycler) = launch(1)
        val row = recycler.row("Series")
        assertEquals(0.38f, row.body().alpha, 0.001f)
        val headers = (0 until recycler.childCount).mapNotNull { (recycler.getChildAt(it).findViewById<View>(R.id.title) as? TextView)?.takeIf { v -> v !is TitleView }?.text?.toString() }
        assertEquals(listOf(context.getString(R.string.section_libraries)), headers)
        assertEquals(FavoriteKind.LIBRARY, FavoriteKindCache(context).peek(series.absolutePath))
        // Still a folder: tapping it offers the sheet, not the reader.
        row.performClick()
        idle()
        assertTrue(activity.missingSheet!!.isShowing)
        activity.missingSheet!!.findViewById<View>(R.id.missing_remove)!!.performClick()
        idle()
        assertTrue(library.favoriteItems().isEmpty())
    }

    @Test
    fun aShareIsAskedOffTheMainThreadAndRowsFollow() {
        val path = "/net/s1/Comics/far.cbz"
        library.addHistory(HistoryItem(path, "far.cbz", 1, 10, 1L, BookId(0xf1)))
        val asked = ArrayList<String>()
        val world = object : PresenceCheck.World {
            override fun shareOf(path: String) = if (path.startsWith("/net/")) "s1" else null
            override fun shareKnown(id: String) = true
            override fun shareUnreachable(id: String) = false
            override fun statKept(path: String) = false
            override fun namesKept(path: String): Set<String>? = null
            override fun listedAt(path: String): Long? = null
            override fun stat(path: String): Boolean {
                assertFalse(Looper.getMainLooper().isCurrentThread)
                synchronized(asked) { asked += path }
                return false
            }
            override fun exists(path: String) = File(path).exists()
            override fun mounted(root: String) = false
            override fun changes() = 0L
            override fun now() = 0L
        }
        val (activity, recycler) = launch()
        activity.presenceCheck = PresenceCheck(world, PresenceCheck.Memo())
        shadowOf(activity).clickMenuItem(R.id.menu_refresh)
        idle()
        recycler.measure(0, 0)
        recycler.layout(0, 0, 1000, 4000)
        assertEquals(listOf(path), asked)
        assertEquals(0.38f, recycler.row("far.cbz").body().alpha, 0.001f)
    }

    @Test
    fun sharesAreAskedAboutTheFirstRowsFirst() {
        val paths = (1..12).map { "/net/s1/Comics/far$it.cbz" }
        for ((i, path) in paths.withIndex()) library.addHistory(HistoryItem(path, path.substringAfterLast('/'), 1, 10, 100L - i, BookId(0xf00L + i)))
        val asked = ArrayList<String>()
        val world = object : PresenceCheck.World {
            override fun shareOf(path: String) = if (path.startsWith("/net/")) "s1" else null
            override fun shareKnown(id: String) = true
            override fun shareUnreachable(id: String) = false
            override fun statKept(path: String) = true
            override fun namesKept(path: String): Set<String>? = null
            override fun listedAt(path: String): Long? = null
            override fun stat(path: String): Boolean {
                synchronized(asked) { asked += path }
                return true
            }
            override fun exists(path: String) = File(path).exists()
            override fun mounted(root: String) = false
            override fun changes() = 0L
            override fun now() = 0L
        }
        val (activity, recycler) = launch()
        activity.presenceCheck = PresenceCheck(world, PresenceCheck.Memo())
        shadowOf(activity).clickMenuItem(R.id.menu_refresh)
        idle()
        val rows = (recycler.adapter as BookAdapter).entries().map { it.path }
        assertEquals(paths.toSet(), asked.toSet())
        assertEquals(rows.take(PresenceCheck.LANES).toSet(), asked.take(PresenceCheck.LANES).toSet())
        assertEquals(rows.drop(PresenceCheck.LANES).take(PresenceCheck.LANES).toSet(), asked.drop(PresenceCheck.LANES).take(PresenceCheck.LANES).toSet())
    }

    @Test
    fun theSelectionMenuRemovesFromHistoryAndBookmarks() {
        history(here)
        library.addBookmark(Bookmark(here.absolutePath, here.name, 2, "", 1L, bookId(here)))
        for ((tab, action) in listOf(2 to MoreAction.REMOVE_FROM_HISTORY, 3 to MoreAction.REMOVE_BOOKMARK)) {
            val (activity, recycler) = launch(tab)
            recycler.getChildAt(0).performLongClick()
            idle()
            activity.findViewById<View>(R.id.selection_more).performClick()
            idle()
            val menu = activity.moreMenu!!
            val tags = (0 until menu.childCount).map { menu.getChildAt(it).tag }
            assertTrue("$tags", action in tags)
            assertFalse(tags.contains(if (action == MoreAction.REMOVE_BOOKMARK) MoreAction.REMOVE_FROM_HISTORY else MoreAction.REMOVE_BOOKMARK))
            (0 until menu.childCount).map { menu.getChildAt(it) }.first { it.tag == action }.performClick()
            idle()
            latestDialog().press(DialogInterface.BUTTON_POSITIVE)
        }
        assertTrue(library.history().isEmpty())
        assertTrue(library.bookmarks().isEmpty())
        // The book itself stays.
        assertTrue(here.exists())
    }

    @Test
    fun theMissingSheetClosesWithTheScreen() {
        fillHistory()
        val controller = start()
        controller.get().laidOut().row("gone.cbz").performClick()
        idle()
        val sheet = controller.get().missingSheet!!
        assertTrue(sheet.isShowing)
        controller.pause().stop().destroy()
        idle()
        assertFalse(sheet.isShowing)
    }

    @Test
    fun listsClearedElsewhereAreListedAgainOnReturn() {
        library.setFavorite(minty.emanata.data.BookRef(bookId(here), here.absolutePath), true)
        history(here)
        for ((tab, clear) in listOf(1 to { library.clearFavorites() }, 2 to { library.clearHistory() })) {
            val controller = start(tab)
            assertTrue(controller.get().laidOut().hasRow("here.cbz"))
            controller.pause().stop()
            clear()
            controller.restart().resume()
            idle()
            assertFalse("tab $tab", controller.get().laidOut().hasRow("here.cbz"))
        }
    }
}
