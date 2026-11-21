package com.infinity.comicreader.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.FakeModel
import com.infinity.comicreader.ai.FakeRuntime
import com.infinity.comicreader.ai.ImportState
import com.infinity.comicreader.ai.ImportWarning
import com.infinity.comicreader.ai.LibNcnn
import com.infinity.comicreader.ai.ModelImports
import com.infinity.comicreader.ai.ModelMemory
import com.infinity.comicreader.ai.ModelRefusal
import com.infinity.comicreader.ai.ModelStore
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
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Importing a model the check warns of as slow: Import anyway goes on to naming it, Cancel or Back drops it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImportWarningTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = ModelStore.of(context)

    @Before
    fun setUp() {
        // The app's start tidies the store on the imports' thread: done before this test writes it.
        ModelImports.settle()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        store.root.deleteRecursively()
        UserModels.load(store)
        ModelImports.cancel()
        // Every tile takes 2 s on the check's clock: a page of about a minute, warned of as slow.
        val now = AtomicLong()
        ModelImports.clock = now::get
        ModelImports.runtime = FakeRuntime(gpu = null, make = { onGpu, _ -> FakeModel(onGpu = onGpu, onRun = { now.addAndGet(2_000) }) })
        ModelImports.memory = { Long.MAX_VALUE }
    }

    @After
    fun tearDown() {
        // Stopped, it no longer shows this test's import states in the next test's dialogs.
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

    /** The model manager, open. */
    private var manager: ActivityController<ModelsActivity>? = null

    /** The model manager open, with a slow model checked: its warning shown. */
    private fun warned(): Pair<ImportState.Fit, AlertDialog> {
        manager = Robolectric.buildActivity(ModelsActivity::class.java).setup()
        ModelImports.start(context, pair("Slow lines"))
        // The check's thread sets the state before the main thread hears of it: wait for the dialog too.
        until { (ModelImports.state.value as? ImportState.Fit)?.warned == true && warning() != null }
        val fit = ModelImports.state.value as ImportState.Fit
        val dialog = warning()
        assertNotNull("the warning", dialog)
        return fit to dialog!!
    }

    /** The latest dialog when it is the warning, showing; else null. */
    private fun warning(): AlertDialog? = (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf {
        it.isShowing && it.getButton(AlertDialog.BUTTON_POSITIVE)?.text?.toString() == context.getString(R.string.ai_import_anyway)
    }

    @Test
    fun theWarningSaysWhyAndOffersImportAnywayOrCancel() {
        val (fit, dialog) = warned()
        assertEquals(setOf(ImportWarning.SLOW), fit.warnings)
        assertEquals("Import Slow lines anyway?", dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertEquals(context.getString(R.string.ai_import_warn_slow, ModelText.pageSeconds(fit), 30L), text)
        assertEquals(context.getString(R.string.ai_import_anyway), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        assertEquals(context.getString(R.string.cancel), dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
    }

    @Test
    fun importAnywayGoesOnToNamingIt() {
        val (fit, dialog) = warned()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(fit.copy(accepted = true), ModelImports.state.value)
        val named = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(named.isShowing)
        assertNotNull("the naming dialog", named.findViewById(R.id.import_name))
        assertTrue(store.staging(fit.id).isDirectory)
    }

    @Test
    fun cancelDropsIt() {
        val (fit, dialog) = warned()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(ImportState.Idle, ModelImports.state.value)
        until { !store.staging(fit.id).exists() }
        assertFalse(store.staging(fit.id).exists())
        assertTrue(UserModels.all.isEmpty())
    }

    @Test
    fun backCancelsToo() {
        val (fit, dialog) = warned()
        dialog.onBackPressed()
        idle()
        assertEquals(ImportState.Idle, ModelImports.state.value)
        assertFalse(dialog.isShowing)
        until { !store.staging(fit.id).exists() }
        assertFalse(store.staging(fit.id).exists())
    }

    @Test
    fun rotatingKeepsTheWarningAndTheModel() {
        val (fit, dialog) = warned()
        manager!!.recreate()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(fit, ModelImports.state.value)
        assertTrue(store.staging(fit.id).isDirectory)
        val again = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(again !== dialog && again.isShowing)
        assertEquals("Import Slow lines anyway?", again.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
    }

    @Test
    fun rotatingOnceAcceptedKeepsTheNamingAndTheModel() {
        val (fit, dialog) = warned()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val named = ShadowDialog.getLatestDialog() as AlertDialog
        manager!!.recreate()
        idle()
        assertEquals(fit.copy(accepted = true), ModelImports.state.value)
        assertTrue(store.staging(fit.id).isDirectory)
        val again = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(again !== named && again.isShowing)
        assertNotNull("the naming dialog", again.findViewById(R.id.import_name))
    }

    @Test
    fun theSlowWarningSaysThePageTime() {
        val fit = ImportState.Fit("user-1", "Slow lines", 2, 44_600, warnings = setOf(ImportWarning.SLOW))
        val text = ModelText.warnings(context, fit)
        assertEquals(context.getString(R.string.ai_import_warn_slow, 45L, 30L), text)
        assertTrue(text, text.contains("about 45 s") && text.contains("more than 30 s"))
        assertTrue(fit.warned)
        assertFalse(fit.copy(accepted = true).warned)
        assertFalse(fit.copy(warnings = emptySet()).warned)
        // Just past the limit: never "about 30 s, more than 30 s".
        assertTrue(ModelText.warnings(context, fit.copy(pageMs = 30_001)).contains("about 31 s"))
    }

    @Test
    fun theNamingSaysTheSecondsTheWarningSaid() {
        val slow = ImportState.Fit("user-1", "Slow", 2, 30_400, warnings = setOf(ImportWarning.SLOW))
        assertTrue(ModelText.warnings(context, slow).contains("about 31 s"))
        assertEquals(31L, ModelText.pageSeconds(slow.copy(accepted = true)))
        assertEquals("not warned of: to the nearest", 30L, ModelText.pageSeconds(slow.copy(warnings = emptySet())))
        assertEquals(30L, ModelText.pageSeconds(slow.copy(pageMs = 29_600, warnings = emptySet())))
    }

    @Test
    fun aModelPastTheReadersLimitIsRefusedSayingSo() {
        val text = ModelText.refusal(context, ImportState.Refused("Glacial", ModelRefusal.TOO_SLOW, 0, 0, 150_000))
        assertTrue(text, text.contains("about 150 s") && text.contains("120 s") && text.contains("none of its pages could show"))
    }
}
