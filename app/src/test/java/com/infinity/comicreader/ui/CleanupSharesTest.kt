package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SimilarStore
import com.infinity.comicreader.net.FakeRemoteFs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareSpeed
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.SyntheticArt
import com.infinity.comicreader.testing.Xxh3Port
import com.infinity.comicreader.testing.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Find duplicate files in a share folder: read only, share books hashed unless the connection is metered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanupSharesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var dir: File
    private lateinit var fake: FakeRemoteFs

    private fun page(seed: Long, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(SyntheticArt.art(W, H, seed), W, H, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    private fun book(path: String, seeds: List<Long>, format: Bitmap.CompressFormat, ext: String, quality: Int = 90): File {
        val file = File(dir, path).apply { parentFile!!.mkdirs() }
        val pages = seeds.mapIndexed { i, seed -> Fixtures.entry("p%02d.$ext".format(i + 1), page(seed, format, quality)) }
        return Fixtures.zip(file, *pages.toTypedArray())
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).similarShares = true
        SimilarStore.of(context.cacheDir).clear()
        BookIds.hasher = Xxh3Port
        dir = tmp.newFolder("share")
        val seeds = (1L..8L).toList()
        book("first/Tale.cbz", seeds, Bitmap.CompressFormat.PNG, "png")
        book("second/Tale retold.cbz", seeds + 99L, Bitmap.CompressFormat.JPEG, "jpg", quality = 80)
        book("third/Other.cbz", (101L..108L).toList(), Bitmap.CompressFormat.WEBP, "webp")
        File(dir, "first/Tale.cbz").copyTo(File(dir, "fourth/Tale.cbz").apply { parentFile!!.mkdirs() })
        fake = FakeRemoteFs(dir)
        Remotes.serve(listOf(FakeRemoteFs.share())) { fake }
    }

    @After
    fun tearDown() {
        fake.slow?.countDown()
        Metered.probe = { Metered.Link(connected = true, notMetered = true, activeMetered = false) }
        Metered.forget()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** Waits until the search is over: its progress gone and the similar-books bar no longer running. */
    private fun settle(activity: CleanupActivity) {
        val progress = activity.findViewById<View>(R.id.cleanup_progress)
        val bar = activity.findViewById<View>(R.id.similar_bar)
        val stop = activity.findViewById<View>(R.id.similar_stop)
        idleUntil("the search over") { progress.visibility != View.VISIBLE && !(bar.visibility == View.VISIBLE && stop.visibility == View.VISIBLE) }
    }

    /** Holds reads of [bytes] until the test opens [FakeRemoteFs.slow], however long that takes; [tearDown] opens it. */
    private fun holdReads(bytes: LongRange) {
        fake.slowReads = bytes
        fake.slowCapMs = Long.MAX_VALUE
        fake.slow = java.util.concurrent.CountDownLatch(1)
    }

    private fun launch(): Pair<CleanupActivity, RecyclerView> {
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, Remotes.rootOf("s1"))
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        settle(activity)
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        list.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1600, 2400)
        return activity to list
    }

    private fun RecyclerView.row(position: Int): View = findViewHolderForAdapterPosition(position)!!.itemView

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun shareBooksAreHashedAndListedReadOnly() {
        val (activity, list) = launch()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.similar_bar).visibility)
        // The bit-identical copies, then the similar books.
        assertEquals(context.getString(R.string.dupes_identical), list.row(0).text(R.id.title))
        assertEquals(context.getString(R.string.dupes_similar), list.row(2).text(R.id.title))
        val columns = list.row(3).findViewById<LinearLayout>(R.id.columns)
        assertEquals(2, columns.childCount)
        // Nothing on a share can be ticked or deleted.
        val place = list.row(1).findViewById<LinearLayout>(R.id.locations).getChildAt(0)
        assertEquals(View.GONE, place.findViewById<CheckBox>(R.id.check).visibility)
        place.performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cleanup_delete).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cleanup_select).visibility)
        assertTrue(File(dir, "fourth/Tale.cbz").exists())
    }

    @Test
    fun onAMeteredConnectionShareBooksAreNotReadAndTheBarSaysWhy() {
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true, cellular = true) }
        Metered.forget()
        val (activity, list) = launch()
        val bar = activity.findViewById<View>(R.id.similar_bar)
        assertEquals(View.VISIBLE, bar.visibility)
        // The two copies sharing a size are not read for the exact-copy check either.
        val notes = listOf(context.resources.getQuantityString(R.plurals.dupes_metered, 2, 2), context.resources.getQuantityString(R.plurals.similar_metered, 4, 4))
        assertEquals(notes.joinToString("\n"), activity.findViewById<TextView>(R.id.similar_text).text.toString())
        assertEquals(0, list.adapter!!.itemCount)
        assertEquals(0L, fake.bytesRead)
        assertNull(SimilarStore.of(context.cacheDir).get(BookIds.of(Remotes.file(Remotes.rootOf("s1") + "/third/Other.cbz"))))
        // Compare is asked for: it reads them anyway.
        val compare = activity.findViewById<View>(R.id.similar_compare)
        assertEquals(View.VISIBLE, compare.visibility)
        compare.performClick()
        settle(activity)
        assertTrue(fake.bytesRead > 0)
        assertNotNull(SimilarStore.of(context.cacheDir).get(BookIds.of(Remotes.file(Remotes.rootOf("s1") + "/third/Other.cbz"))))
        // The exact copies are checked too.
        relayout(list)
        assertEquals(context.getString(R.string.dupes_identical), list.row(0).text(R.id.title))
        assertEquals(View.GONE, bar.visibility)
    }

    private fun relayout(list: RecyclerView) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1600, 2400)
    }

    @Test
    fun onASlowShareTheExactCopyCheckWaitsForCompare() {
        Remotes.serve(listOf(FakeRemoteFs.share().copy(speed = ShareSpeed.SLOW))) { fake }
        val (activity, list) = launch()
        val counted = context.resources.getQuantityString(R.plurals.dupes_slow, 2, 2)
        assertTrue(activity.findViewById<TextView>(R.id.similar_text).text.toString().startsWith(counted))
        val compare = activity.findViewById<View>(R.id.similar_compare)
        assertEquals(View.VISIBLE, compare.visibility)
        compare.performClick()
        settle(activity)
        relayout(list)
        assertEquals(context.getString(R.string.dupes_identical), list.row(0).text(R.id.title))
    }

    @Test
    fun aConnectionTurningMeteredStopsTheExactCopyCheckBetweenPieces() {
        val big = kotlin.random.Random(5).nextBytes(BIG)
        File(dir, "fifth/Large.cbz").apply { parentFile!!.mkdirs() }.writeBytes(big)
        File(dir, "sixth/Large.cbz").apply { parentFile!!.mkdirs() }.writeBytes(big)
        // The full hash's piece covering the second MB waits while the connection turns metered.
        holdReads((1L shl 20)..(1L shl 20))
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, Remotes.rootOf("s1"))
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        idleUntil("a read held") { fake.held.get() != 0 }
        assertEquals(1, fake.held.get())
        Metered.probe = { Metered.Link(connected = true, notMetered = false, activeMetered = true) }
        Metered.forget()
        fake.slow!!.countDown()
        settle(activity)
        // Both large copies are counted; the first stopped part way, the second never read past its quick hash.
        val text = activity.findViewById<TextView>(R.id.similar_text).text.toString()
        val counted = listOf(2, 4).map { context.resources.getQuantityString(R.plurals.dupes_metered, it, it) }
        assertTrue(text, counted.any { text.startsWith(it) })
        assertTrue(fake.bytesRead < 2L * BIG)
    }

    @Test
    fun shareFilesThatComeOnlyWholeAreLeftOutOfTheExactCopyCheck() {
        fake.seekable = false
        val (_, list) = launch()
        assertEquals(0L, fake.bytesRead)
        assertEquals(0, list.adapter!!.itemCount)
    }

    @Test
    fun withTheSettingOffAShareFolderClosesUnread() {
        Prefs(context).similarShares = false
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, Remotes.rootOf("s1"))
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        // Closed as it was made, before any search could start.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertEquals(0, fake.lists)
        assertEquals(0L, fake.bytesRead)
    }

    @Test
    fun theExactCopyCheckShowsProgressAndStops() {
        // Reads of the files' first bytes wait: the check is under way.
        holdReads(0L..0L)
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, Remotes.rootOf("s1"))
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        idleUntil("a read held") { fake.held.get() != 0 }
        shadowOf(Looper.getMainLooper()).idle()
        val text = activity.findViewById<TextView>(R.id.similar_text)
        val stop = activity.findViewById<View>(R.id.similar_stop)
        assertTrue(text.text.startsWith(context.getString(R.string.dupes_checking).removeSuffix("…")))
        assertEquals(View.VISIBLE, stop.visibility)
        stop.performClick()
        fake.slow!!.countDown()
        settle(activity)
        // Stopped: nothing more read, and the bar says so; the similar books are left to Compare.
        assertTrue(text.text.toString().startsWith(context.getString(R.string.dupes_stopped)))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.similar_compare).visibility)
        assertNull(SimilarStore.of(context.cacheDir).get(BookIds.of(Remotes.file(Remotes.rootOf("s1") + "/third/Other.cbz"))))
    }

    private companion object {
        const val W = 120
        const val H = 180
        const val BIG = 5 shl 19
    }
}
