package com.infinity.comicreader.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.FakeModel
import com.infinity.comicreader.ai.FakeRuntime
import com.infinity.comicreader.ai.ImportState
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.ModelImports
import com.infinity.comicreader.ai.ModelMemory
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.PreviewPicture
import com.infinity.comicreader.ai.UserModels
import com.infinity.comicreader.ai.fakeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** A checked model's before and after on the test pictures: shown before it is named, judging nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImportPreviewTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = ModelStore.of(context)

    /** The model manager, open. */
    private var manager: ActivityController<ModelsActivity>? = null

    @Before
    fun setUp() {
        // The app's start tidies the store on the imports' thread: done before this test writes it.
        ModelImports.settle()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        store.root.deleteRecursively()
        UserModels.load(store)
        ModelImports.cancel()
        // Each tile run takes 100 ms on the check's clock: a page of a few seconds, not slow.
        val now = AtomicLong()
        ModelImports.clock = now::get
        ModelImports.runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, onRun = { now.addAndGet(100) }) })
        ModelImports.memory = { Long.MAX_VALUE }
    }

    @After
    fun tearDown() {
        manager?.pause()?.stop()?.destroy()
        ModelImports.cancel()
        ModelImports.runtime = LibNcnn
        ModelImports.memory = ModelMemory::of
        ModelImports.clock = SystemClock::elapsedRealtime
        store.root.deleteRecursively()
        UserModels.load(store)
    }

    private fun pair(name: String): List<Uri> {
        val dir = tmp.newFolder()
        val files = fakeFiles(dir)
        val param = File(dir, "$name.param").also { files.param.renameTo(it) }
        val bin = File(dir, "$name.bin").also { files.bin.renameTo(it) }
        return listOf(Uri.fromFile(param), Uri.fromFile(bin))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Idles the main thread until [done], within 20 s. */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            idle()
            if (done()) return
            Thread.sleep(10)
        }
        idle()
    }

    /** The latest dialog when it is the before and after, showing; else null. */
    private fun preview(): AlertDialog? =
        (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing && it.findViewById<LinearLayout>(R.id.import_preview_list) != null }

    /** The model manager open, with a model checked: its before and after shown. */
    private fun previewed(): Pair<ImportState.Fit, AlertDialog> {
        manager = Robolectric.buildActivity(ModelsActivity::class.java).setup()
        ModelImports.start(context, pair("Sharp lines"))
        until { (ModelImports.state.value as? ImportState.Fit)?.previewing == true && preview() != null }
        val fit = ModelImports.state.value as ImportState.Fit
        return fit to preview()!!
    }

    @Test
    fun aCheckedModelShowsBeforeAndAfterOnEachTestPictureZoomed2x() {
        val (fit, dialog) = previewed()
        assertTrue(fit.warnings.isEmpty())
        assertEquals("Sharp lines: before and after", dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        val list = dialog.findViewById<LinearLayout>(R.id.import_preview_list)!!
        // The intro, then a row per picture.
        assertEquals(1 + PreviewPicture.entries.size, list.childCount)
        val labels = listOf(R.string.ai_import_preview_gradients, R.string.ai_import_preview_jpeg, R.string.ai_import_preview_dots).map(context::getString)
        for ((i, p) in fit.previews.withIndex()) {
            val row = list.getChildAt(i + 1)
            assertEquals(labels[i], row.findViewById<TextView>(R.id.import_preview_label).text.toString())
            val side = p.before.width * 2
            for (id in listOf(R.id.import_preview_before, R.id.import_preview_after)) {
                val image = row.findViewById<ImageView>(id)
                assertEquals(side, image.drawable.intrinsicWidth)
                assertEquals(side, image.drawable.intrinsicHeight)
            }
            assertEquals("${labels[i]}, Before", row.findViewById<ImageView>(R.id.import_preview_before).contentDescription)
            assertEquals("${labels[i]}, After", row.findViewById<ImageView>(R.id.import_preview_after).contentDescription)
        }
        assertEquals(context.getString(R.string.ai_import_preview_next), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(context.getString(R.string.ai_import_discard), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
    }

    @Test
    fun continueGoesOnToNamingIt() {
        val (fit, dialog) = previewed()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(fit.copy(seen = true), ModelImports.state.value)
        val named = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(named.isShowing)
        assertNotNull("the naming dialog", named.findViewById(R.id.import_name))
    }

    @Test
    fun discardDropsIt() {
        val (fit, dialog) = previewed()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(ImportState.Idle, ModelImports.state.value)
        until { !store.staging(fit.id).exists() }
        assertFalse(store.staging(fit.id).exists())
        assertTrue(UserModels.all.isEmpty())
    }

    @Test
    fun backDropsItATapOutsideDoesNot() {
        val (fit, dialog) = previewed()
        assertFalse(shadowOf(dialog).isCancelableOnTouchOutside)
        dialog.onBackPressed()
        idle()
        assertEquals(ImportState.Idle, ModelImports.state.value)
        until { !store.staging(fit.id).exists() }
        assertFalse(store.staging(fit.id).exists())
    }

    @Test
    fun rotatingKeepsTheBeforeAndAfter() {
        val (fit, dialog) = previewed()
        manager!!.recreate()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(fit, ModelImports.state.value)
        val again = preview()
        assertNotNull(again)
        assertTrue(again !== dialog)
    }

    @Test
    fun beforeShowsWholePixelsAfterFillsTheSameSquare() {
        val before = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.BLACK)
            setPixel(1, 0, Color.WHITE)
            setPixel(0, 1, Color.WHITE)
            setPixel(1, 1, Color.BLACK)
        }
        val shown = ImportPreviewZoom.shown(before, 4)
        assertEquals(4, shown.width)
        // Each pixel repeated, not blended.
        assertEquals(Color.BLACK, shown.getPixel(1, 1))
        assertEquals(Color.WHITE, shown.getPixel(2, 1))
        assertEquals(Color.WHITE, shown.getPixel(1, 2))
        // A 2x model's after is shown as it is; a 4x one's made smaller.
        val twice = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(ImportPreviewZoom.shown(twice, 4) === twice)
        assertEquals(4, ImportPreviewZoom.shown(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), 4).width)
    }

    @Test
    fun beforeAndAfterGoSideBySideOnlyWhereBothFit() {
        assertTrue(ImportPreviewZoom.sideBySide(256, 8, 520))
        assertFalse(ImportPreviewZoom.sideBySide(256, 8, 519))
    }
}
