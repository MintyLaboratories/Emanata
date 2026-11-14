package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.CatalogModel
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelImports
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.ShaderCatalog
import com.infinity.comicreader.ai.ShaderModel
import com.infinity.comicreader.ai.UserModel
import com.infinity.comicreader.ai.UserModels
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.data.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.RandomAccessFile

/** The model manager: a row per catalog model with its details, state and action, and its attribution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelsActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        // The app's start tidies the store on the imports' thread: done before this test writes it.
        ModelImports.settle()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        store = ModelStore.of(context)
        store.root.deleteRecursively()
        UserModels.load(store)
    }

    @After
    fun tearDown() {
        store.root.deleteRecursively()
        UserModels.load(store)
    }

    /** An imported model named [name] at [step], taken in. */
    private fun imported(id: String, name: String, step: AiStep): UserModel {
        val files = store.userFiles(store.staging(id).apply { mkdirs() })
        files.param.writeText("7767517")
        files.bin.writeBytes(ByteArray(2048))
        val m = UserModel(id, name, step, if (step == AiStep.UPSCALE) 2 else 1, 12_000, files.param.length(), 2048, "a".repeat(64), "b".repeat(64))
        store.saveUser(m)
        UserModels.load(store)
        return m
    }

    private fun install(m: CatalogModel) {
        for (f in m.files) {
            store.dir(m).mkdirs()
            RandomAccessFile(File(store.dir(m), f.name), "rw").use { it.setLength(f.bytes) }
            File(store.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
    }

    /** Every model row, in screen order; not the cover text detector's. */
    private fun rows(activity: ModelsActivity): List<View> {
        val list = activity.findViewById<LinearLayout>(R.id.models_list)
        return (0 until list.childCount).map { list.getChildAt(it) }.filter { it.id != R.id.cover_text_card }.flatMap { v ->
            val box = v.findViewById<LinearLayout>(R.id.card_rows) ?: return@flatMap emptyList()
            (0 until box.childCount).map { box.getChildAt(it) }
        }
    }

    private fun open(): ModelsActivity {
        val activity = Robolectric.buildActivity(ModelsActivity::class.java).setup().get()
        // The state shows once read off the main thread.
        for (i in 0 until 50) {
            shadowOf(Looper.getMainLooper()).idle()
            if (rows(activity).all { it.findViewById<MaterialButton>(R.id.action).text.isNotEmpty() }) break
            Thread.sleep(20)
        }
        return activity
    }

    @Test
    fun everyCatalogModelHasARowUnderItsStep() {
        Prefs(context).keepSimple = false
        val activity = open()
        val rows = rows(activity)
        val all = AiStep.entries.flatMap { Models.forStep(it) }
        assertEquals(ModelCatalog.all.size + ShaderCatalog.all.size, rows.size)
        for ((m, row) in all.zip(rows)) {
            assertEquals(View.VISIBLE, row.visibility)
            assertEquals(m.name, row.findViewById<TextView>(R.id.title).text.toString())
            assertEquals(ModelText.details(activity, m), row.findViewById<TextView>(R.id.subtitle).text.toString())
            val license = if (m is ShaderModel) m.source.license else (m as CatalogModel).source.license
            assertTrue(row.findViewById<TextView>(R.id.subtitle).text.contains(license))
            val blurb = row.findViewById<TextView>(R.id.blurb)
            assertEquals(ModelText.blurb(activity, m), blurb.text.toString())
            assertEquals(m.id, blurb.text.isNotEmpty(), blurb.visibility == View.VISIBLE)
        }
        val waifu2x = rows[ModelCatalog.all.indexOfFirst { it.id == ModelCatalog.DEFAULT_UPSCALE }]
        assertEquals("Faithful, fast and reliable clarity improvement (default)", waifu2x.findViewById<TextView>(R.id.blurb).text.toString())
    }

    @Test
    fun keepingItSimpleShowsEveryLevelOfEachShaderFamily() {
        assertTrue(Prefs(context).keepSimple)
        val rows = rows(open()).drop(ModelCatalog.all.size)
        for ((m, row) in ShaderCatalog.all.zip(rows)) {
            assertEquals(m.id, !m.slow, row.visibility == View.VISIBLE)
        }
    }

    @Test
    fun keepingItSimpleHidesSlowModelsNotDownloaded() {
        assertTrue(Prefs(context).keepSimple)
        val kept = ModelCatalog.byId("realesr-animevideov3-x2")!!
        install(kept)
        val rows = rows(open())
        for ((m, row) in ModelCatalog.all.zip(rows)) {
            assertEquals(m.id, !m.slow || m == kept, row.visibility == View.VISIBLE)
        }
        assertTrue(rows[ModelCatalog.all.indexOf(kept)].findViewById<TextView>(R.id.blurb).text.endsWith("Slow (~11 s per page)"))
    }

    @Test
    fun aDownloadedModelOffersDeleteTheOthersDownload() {
        val m = ModelCatalog.all.first()
        install(m)
        val activity = open()
        val rows = rows(activity)
        assertEquals(activity.getString(R.string.delete), rows[0].findViewById<MaterialButton>(R.id.action).text.toString())
        assertEquals(activity.getString(R.string.ai_downloaded), rows[0].findViewById<TextView>(R.id.status).text.toString())
        assertEquals(activity.getString(R.string.ai_download), rows[1].findViewById<MaterialButton>(R.id.action).text.toString())
        assertFalse(rows[1].findViewById<TextView>(R.id.status).visibility == View.VISIBLE)
    }

    @Test
    fun aRowOpensItsAttribution() {
        val activity = open()
        val m = ModelCatalog.all.last()
        rows(activity)[ModelCatalog.all.size - 1].performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.contains(m.source.author) && text.contains(m.source.license) && text.contains(m.source.url))
        assertTrue(text.contains(ModelCatalog.REPOSITORY))
        dialog.dismiss()
        // A shader chain names its own repository.
        rows(activity).last().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val chain = (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(chain, chain.contains("bloc97") && chain.contains(ShaderCatalog.REPOSITORY) && !chain.contains(ModelCatalog.REPOSITORY))
    }

    @Test
    fun importedModelsShowAfterTheCatalogsMarkedAndDeletable() {
        val mine = imported("user-1", "My upscaler", AiStep.UPSCALE)
        val activity = open()
        val rows = rows(activity)
        assertEquals(ModelCatalog.all.size + ShaderCatalog.all.size + 1, rows.size)
        val row = rows.last()
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("My upscaler", row.findViewById<TextView>(R.id.title).text.toString())
        val details = row.findViewById<TextView>(R.id.subtitle).text.toString()
        assertTrue(details, details.startsWith("2× · ") && details.endsWith(" · imported"))
        // Slow here, and shown while keeping it simple.
        assertEquals("Slow (~12 s per page)", row.findViewById<TextView>(R.id.blurb).text.toString())
        assertEquals(activity.getString(R.string.delete), row.findViewById<MaterialButton>(R.id.action).text.toString())

        row.findViewById<MaterialButton>(R.id.action).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val ask = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(ask.findViewById<TextView>(android.R.id.message)!!.text.contains("My upscaler"))
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        for (i in 0 until 100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (UserModels.all.isEmpty() && rows(activity).size == ModelCatalog.all.size + ShaderCatalog.all.size) break
            Thread.sleep(20)
        }
        assertTrue(UserModels.all.isEmpty())
        assertFalse(store.userDir(mine.id).exists())
        assertEquals(ModelCatalog.all.size + ShaderCatalog.all.size, rows(activity).size)
    }

    @Test
    fun anImportedModelsRowSaysNoLicense() {
        imported("user-1", "Cleaner", AiStep.DESCREEN)
        val activity = open()
        rows(activity).single { it.findViewById<TextView>(R.id.title).text == "Cleaner" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val text = dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
        assertTrue(text, text.contains("no source or license is known") && text.contains("12 s per page"))
    }
}
