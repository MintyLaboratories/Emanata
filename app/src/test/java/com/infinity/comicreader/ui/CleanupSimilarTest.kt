package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.Looper
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
        val bar = activity.findViewById<View>(R.id.similar_bar)
        var rounds = 0
        do {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        } while ((bar.visibility == View.VISIBLE || rounds++ < 5) && rounds < 1000)
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

    private companion object {
        const val W = 120
        const val H = 180
    }
}
