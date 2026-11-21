package com.infinity.comicreader.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
import com.infinity.comicreader.ai.UserModel
import com.infinity.comicreader.ai.UserModels
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.testing.idleUntil
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
        idleUntil("every model row's state") { rows(activity).all { it.findViewById<MaterialButton>(R.id.action).text.isNotEmpty() } }
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
            // Scale only: size and license show once the row is tapped.
            assertEquals("${m.scale}×", row.findViewById<TextView>(R.id.subtitle).text.toString())
            assertEquals(View.GONE, row.findViewById<View>(R.id.model_info).visibility)
            val blurb = row.findViewById<TextView>(R.id.blurb)
            assertEquals(ModelText.blurb(activity, m), blurb.text.toString())
            assertEquals(m.id, blurb.text.isNotEmpty(), blurb.visibility == View.VISIBLE)
        }
        val waifu2x = rows[ModelCatalog.all.indexOfFirst { it.id == ModelCatalog.DEFAULT_UPSCALE }]
        assertEquals("Faithful, fast and reliable clarity improvement; does little for blurry art or jagged lines (default)", waifu2x.findViewById<TextView>(R.id.blurb).text.toString())
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
    fun aTapShowsSizeLicenseAndSourceUnderTheRowAnotherHidesThem() {
        val activity = open()
        val m = ModelCatalog.all.last()
        val row = rows(activity)[ModelCatalog.all.size - 1]
        val info = row.findViewById<View>(R.id.model_info)
        assertEquals(View.GONE, info.visibility)
        row.performClick()
        assertEquals(View.VISIBLE, info.visibility)
        assertTrue("no dialog", ShadowDialog.getLatestDialog() == null)
        val text = row.findViewById<TextView>(R.id.model_info_text).text.toString()
        assertTrue(text, text.startsWith(ModelText.details(activity, m)))
        assertTrue(text, text.contains(ModelText.size(activity, m.bytes)) && text.contains(m.source.license))
        assertTrue(text, text.contains(m.source.author) && text.contains(m.source.url) && text.contains(ModelCatalog.REPOSITORY))
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.model_info_link).visibility)
        row.performClick()
        assertEquals(View.GONE, info.visibility)
        // A shader chain names its own repository.
        val chain = rows(activity).last()
        chain.performClick()
        val said = chain.findViewById<TextView>(R.id.model_info_text).text.toString()
        assertTrue(said, said.contains("bloc97") && said.contains(ShaderCatalog.REPOSITORY) && !said.contains(ModelCatalog.REPOSITORY))
    }

    @Test
    fun aTapSaysToScreenReadersWhetherItShowsOrHidesTheInfo() {
        val activity = open()
        val m = ModelCatalog.all.first()
        val row = rows(activity).first()
        fun label(): String? = AccessibilityNodeInfoCompat.wrap(row.createAccessibilityNodeInfo()).actionList
            .firstOrNull { it.id == AccessibilityNodeInfoCompat.ACTION_CLICK }?.label?.toString()
        assertEquals(activity.getString(R.string.ai_model_more_info, m.name), label())
        row.performClick()
        assertEquals(activity.getString(R.string.ai_model_less_info, m.name), label())
    }

    @Test
    fun openRowsStayOpenAcrossRotation() {
        val controller = Robolectric.buildActivity(ModelsActivity::class.java).setup()
        rows(controller.get())[0].performClick()
        controller.recreate()
        val rows = rows(controller.get())
        assertEquals(View.VISIBLE, rows[0].findViewById<View>(R.id.model_info).visibility)
        assertEquals(View.GONE, rows[1].findViewById<View>(R.id.model_info).visibility)
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
        assertEquals("2× · imported", row.findViewById<TextView>(R.id.subtitle).text.toString())
        row.performClick()
        assertEquals(ModelText.imported(activity, mine), row.findViewById<TextView>(R.id.model_info_text).text.toString())
        assertEquals(View.GONE, row.findViewById<View>(R.id.model_info_link).visibility)
        // Slow here, and shown while keeping it simple.
        assertEquals("Slow (~12 s per page)", row.findViewById<TextView>(R.id.blurb).text.toString())
        assertEquals(activity.getString(R.string.delete), row.findViewById<MaterialButton>(R.id.action).text.toString())

        row.findViewById<MaterialButton>(R.id.action).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val ask = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(ask.findViewById<TextView>(android.R.id.message)!!.text.contains("My upscaler"))
        ask.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // The delete is queued with the imports' work; the screen hears of it after.
        ModelImports.settle()
        idleUntil("the deleted model's row gone") { UserModels.all.isEmpty() && rows(activity).size == ModelCatalog.all.size + ShaderCatalog.all.size }
        assertTrue(UserModels.all.isEmpty())
        assertFalse(store.userDir(mine.id).exists())
        assertEquals(ModelCatalog.all.size + ShaderCatalog.all.size, rows(activity).size)
    }

    @Test
    fun anImportedModelsRowSaysNoLicense() {
        imported("user-1", "Cleaner", AiStep.DESCREEN)
        val activity = open()
        val row = rows(activity).single { it.findViewById<TextView>(R.id.title).text == "Cleaner" }
        row.performClick()
        val text = row.findViewById<TextView>(R.id.model_info_text).text.toString()
        assertTrue(text, text.contains("no source or license is known") && text.contains("12 s per page"))
    }
}
