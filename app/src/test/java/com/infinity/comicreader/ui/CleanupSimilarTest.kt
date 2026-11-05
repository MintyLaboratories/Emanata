package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.SimilarStore
import com.infinity.comicreader.reader.BookPrints
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.SyntheticArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File

/** Books alike in other files show side by side under their own header, never selected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanupSimilarTest {

    private lateinit var context: Application
    private lateinit var root: File
    private lateinit var tale: File
    private lateinit var retold: File

    private fun page(seed: Long, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(SyntheticArt.art(W, H, seed), W, H, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
    }

    /** A zip book of pages [seeds] in [ext] pages made by [format] at [quality]. */
    private fun book(path: String, seeds: List<Long>, format: Bitmap.CompressFormat, ext: String, quality: Int = 90): File {
        val file = File(root, path).apply { parentFile!!.mkdirs() }
        val pages = seeds.mapIndexed { i, seed -> Fixtures.entry("p%02d.$ext".format(i + 1), page(seed, format, quality)) }
        return Fixtures.zip(file, *pages.toTypedArray())
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        SimilarStore.of(context.cacheDir).clear()
        root = File(Environment.getExternalStorageDirectory(), "alike").apply { deleteRecursively(); mkdirs() }
        val seeds = (1L..8L).toList()
        tale = book("first/Tale.cbz", seeds, Bitmap.CompressFormat.PNG, "png")
        // Re-encoded, with a tag page added at the end.
        retold = book("second/Tale retold.cbz", seeds + 99L, Bitmap.CompressFormat.JPEG, "jpg", quality = 80)
        book("third/Other.cbz", (101L..108L).toList(), Bitmap.CompressFormat.WEBP, "webp")
    }

    /** Runs the main looper until the similar-books search is over. */
    private fun settle(activity: CleanupActivity) {
        val stop = activity.findViewById<View>(R.id.similar_stop)
        val bar = activity.findViewById<View>(R.id.similar_bar)
        var rounds = 0
        do {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        } while (((bar.isShown && stop.isShown) || rounds++ < 5) && rounds < 1000)
    }

    private fun launch(): Pair<CleanupActivity, RecyclerView> {
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, root.absolutePath)
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        settle(activity)
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        lay(list)
        return activity to list
    }

    private fun lay(list: RecyclerView) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1600, 2400)
    }

    private fun RecyclerView.row(position: Int): View = findViewHolderForAdapterPosition(position)!!.itemView

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun reEncodedBooksShowSideBySideUnderTheirHeader() {
        val (activity, list) = launch()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.similar_bar).visibility)
        assertEquals(2, list.adapter!!.itemCount)
        assertEquals(context.getString(R.string.dupes_similar), list.row(0).text(R.id.title))
        val columns = list.row(1).findViewById<LinearLayout>(R.id.columns)
        assertEquals(2, columns.childCount)
        val first = columns.getChildAt(0)
        val second = columns.getChildAt(1)
        assertEquals("Tale.cbz", first.text(R.id.title))
        assertEquals("CBZ · PNG", first.text(R.id.format))
        assertTrue(first.text(R.id.pages), first.text(R.id.pages).startsWith("8 pages"))
        assertEquals("$W × $H px", first.text(R.id.dimensions))
        assertEquals("Tale retold.cbz", second.text(R.id.title))
        assertEquals("CBZ · JPG", second.text(R.id.format))
        assertTrue(second.text(R.id.pages).startsWith("9 pages"))
        assertNotNull(first.findViewById<View>(R.id.box))
        // What was read is kept by Book ID.
        BookIds.init(context)
        assertNotNull(SimilarStore.of(context.cacheDir).get(BookIds.of(tale)))
    }

    @Test
    fun keepOneOfEachLeavesSimilarBooksAlone() {
        val (activity, list) = launch()
        activity.findViewById<MaterialButton>(R.id.cleanup_select).performClick()
        assertFalse(activity.findViewById<MaterialButton>(R.id.cleanup_delete).isEnabled)
        lay(list)
        val columns = list.row(1).findViewById<LinearLayout>(R.id.columns)
        for (i in 0 until columns.childCount) {
            val place = columns.getChildAt(i).findViewById<LinearLayout>(R.id.locations).getChildAt(0)
            assertFalse(place.findViewById<CheckBox>(R.id.check).isChecked)
        }
    }

    @Test
    fun deletingOneOfTwoDropsTheGroupAndItsHeader() {
        val (activity, list) = launch()
        val columns = list.row(1).findViewById<LinearLayout>(R.id.columns)
        columns.getChildAt(1).findViewById<LinearLayout>(R.id.locations).getChildAt(0).performClick()
        activity.findViewById<MaterialButton>(R.id.cleanup_delete).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle(activity)
        assertFalse(retold.exists())
        assertTrue(tale.exists())
        assertEquals(0, list.adapter!!.itemCount)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cleanup_empty).visibility)
    }

    @Test
    fun manyBooksNotReadYetWaitForCompare() {
        // With the three of setUp, more books than a search reads unasked.
        for (n in 1..CleanupActivity.AUTO_READS) book("more/Extra %02d.cbz".format(n), listOf(1000L + 2 * n, 1001L + 2 * n), Bitmap.CompressFormat.PNG, "png")
        val (activity, list) = launch()
        val bar = activity.findViewById<View>(R.id.similar_bar)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.similar_compare).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.similar_stop).visibility)
        assertEquals(context.getString(R.string.similar_unread, CleanupActivity.AUTO_READS + 3, CleanupActivity.AUTO_READS + 3), activity.findViewById<TextView>(R.id.similar_text).text.toString())
        assertEquals(0, list.adapter!!.itemCount)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.cleanup_empty).visibility)
        activity.findViewById<MaterialButton>(R.id.similar_compare).performClick()
        settle(activity)
        lay(list)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(context.getString(R.string.dupes_similar), list.row(0).text(R.id.title))
        assertEquals(2, list.row(1).findViewById<LinearLayout>(R.id.columns).childCount)
    }

    @Test
    fun batterySaverWaitsForCompare() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val (activity, list) = launch()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.similar_compare).visibility)
        assertEquals(0, list.adapter!!.itemCount)
        activity.findViewById<MaterialButton>(R.id.similar_compare).performClick()
        settle(activity)
        lay(list)
        assertEquals(2, list.adapter!!.itemCount)
    }

    @Test
    fun booksDeletedBeforeTheSearchEndsAreNotListed() {
        // Retold is known already and has a bit-identical copy; the rest wait for Compare.
        val copy = File(retold.parentFile, "Tale retold copy.cbz").also { retold.copyTo(it) }
        BookIds.init(context)
        SimilarStore.of(context.cacheDir).put(BookIds.of(retold), BookPrints.read(retold, "UTF-8", context.cacheDir)!!.entry)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val (activity, list) = launch()
        // Only the bit-identical group: both copies of retold, ticked and deleted.
        assertEquals(2, list.adapter!!.itemCount)
        val places = list.row(1).findViewById<LinearLayout>(R.id.locations)
        for (i in 0 until places.childCount) places.getChildAt(i).performClick()
        activity.findViewById<MaterialButton>(R.id.cleanup_delete).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        settle(activity)
        assertFalse(copy.exists())
        activity.findViewById<MaterialButton>(R.id.similar_compare).performClick()
        settle(activity)
        lay(list)
        assertEquals(0, list.adapter!!.itemCount)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cleanup_empty).visibility)
    }

    @Test
    fun vectorBooksShowTheirSizeInPoints() {
        BookIds.init(context)
        val read = BookPrints.read(tale, "UTF-8", context.cacheDir)!!.entry
        val pdf = read.copy(print = read.print.copy(vector = true))
        SimilarStore.of(context.cacheDir).put(BookIds.of(tale), pdf)
        SimilarStore.of(context.cacheDir).put(BookIds.of(retold), pdf)
        val (_, list) = launch()
        val first = list.row(1).findViewById<LinearLayout>(R.id.columns).getChildAt(0)
        assertEquals("$W × $H pt", first.text(R.id.dimensions))
    }

    @Test
    fun stoppedSearchCountsWhatIsLeftAndCompareGoesOn() {
        val intent = Intent(context, CleanupActivity::class.java)
            .putExtra(CleanupActivity.EXTRA_MODE, CleanupActivity.MODE_DUPES)
            .putExtra(CleanupActivity.EXTRA_ROOT, root.absolutePath)
        val activity = Robolectric.buildActivity(CleanupActivity::class.java, intent).setup().get()
        activity.findViewById<MaterialButton>(R.id.similar_stop).performClick()
        settle(activity)
        val list = activity.findViewById<RecyclerView>(R.id.cleanup_list)
        lay(list)
        val bar = activity.findViewById<View>(R.id.similar_bar)
        // Reads done before Stop are compared; the rest, the one under way included, are counted.
        if (bar.visibility == View.VISIBLE) {
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.similar_compare).visibility)
            assertTrue(activity.findViewById<TextView>(R.id.similar_text).text.toString().endsWith("of 3 books not compared yet"))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.cleanup_empty).visibility)
            activity.findViewById<MaterialButton>(R.id.similar_compare).performClick()
            settle(activity)
            lay(list)
        }
        assertEquals(View.GONE, bar.visibility)
        assertEquals(context.getString(R.string.dupes_similar), list.row(0).text(R.id.title))
    }

    @Test
    fun similarBookWithBitIdenticalCopiesListsEveryPlaceInItsColumn() {
        tale.copyTo(File(root, "fourth/Tale.cbz").apply { parentFile!!.mkdirs() })
        val (_, list) = launch()
        // The bit-identical group, then the similar books.
        assertEquals(4, list.adapter!!.itemCount)
        assertEquals(context.getString(R.string.dupes_identical), list.row(0).text(R.id.title))
        assertEquals(context.getString(R.string.dupes_similar), list.row(2).text(R.id.title))
        val columns = list.row(3).findViewById<LinearLayout>(R.id.columns)
        assertEquals(2, columns.childCount)
        assertEquals(2, columns.getChildAt(0).findViewById<LinearLayout>(R.id.locations).childCount)
        assertEquals(1, columns.getChildAt(1).findViewById<LinearLayout>(R.id.locations).childCount)
    }

    @Test
    fun aBookThatCannotBeReadIsCountedButNotOfferedAgain() {
        File(root, "fifth/Broken.cbz").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(2048) { (it * 7).toByte() })
        val (activity, list) = launch()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.similar_bar).visibility)
        assertEquals(context.getString(R.string.dupes_similar_partial, 3, 4), list.row(0).text(R.id.title))
    }

    private companion object {
        const val W = 120
        const val H = 180
    }
}
