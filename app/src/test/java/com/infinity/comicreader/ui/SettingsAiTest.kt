package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.CheckedTextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiChain
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.CatalogModel
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/** Settings › AI processing: off by default, a model per step from the catalog, the chain run, battery saver pauses it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAiTest {

    private lateinit var context: Context
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        store = ModelStore.of(context)
        store.root.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settings(): PreferenceFragmentCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        return activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
    }

    /** Puts [m]'s files in place at their sizes, stamped as a finished download leaves them. */
    private fun install(m: CatalogModel) {
        for (f in m.files) {
            val file = File(store.dir(m), f.name)
            file.parentFile!!.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(f.bytes) }
            File(store.dir(m), f.name + ModelStore.STAMP).writeText(f.sha256)
        }
        assertTrue(store.installed(m))
    }

    /** Waits for [pref]'s summary to read [expected]: it shows once the store is read off the main thread. */
    private fun awaitSummary(pref: Preference, expected: String) {
        val end = System.currentTimeMillis() + 5_000
        while (pref.summary?.toString() != expected && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(10)
        }
        assertEquals(expected, pref.summary?.toString())
    }

    @Test
    fun aiIsOffByDefaultWithOnlyAnUpscaler() {
        val prefs = Prefs(context)
        assertFalse(prefs.aiEnabled)
        assertNull(prefs.aiModel(AiStep.ARTIFACTS))
        assertNull(prefs.aiModel(AiStep.DESCREEN))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, prefs.aiModel(AiStep.UPSCALE))
        assertTrue(prefs.saverAi)
        // The settings screen writes the same defaults.
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        val written = Prefs(context)
        assertFalse(written.aiEnabled)
        assertNull(written.aiModel(AiStep.ARTIFACTS))
        assertNull(written.aiModel(AiStep.DESCREEN))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, written.aiModel(AiStep.UPSCALE))
        assertTrue(written.saverAi)
    }

    @Test
    fun theSettingsScreenShowsEachStepsDefaultWithoutStoringIt() {
        val fragment = settings()
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
        for (step in AiStep.entries) {
            val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(step))!!
            assertEquals(Prefs(context).aiModel(step) ?: Prefs.AI_NONE, pref.value)
            assertFalse("${step.key}: no choice made, none stored", stored.contains(Prefs.aiKey(step)))
        }
        assertEquals("waifu2x-upconv7-2x", fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!.value)
        // A choice is stored.
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        val other = ModelCatalog.forStep(AiStep.UPSCALE).first { it.id != ModelCatalog.DEFAULT_UPSCALE }
        if (pref.callChangeListener(other.id)) pref.value = other.id
        assertEquals(other.id, stored.getString(Prefs.aiKey(AiStep.UPSCALE), null))
    }

    @Test
    fun pickingTheShownDefaultStoresIt() {
        val fragment = settings()
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, pref.value)
        if (pref.callChangeListener(ModelCatalog.DEFAULT_UPSCALE)) pref.value = ModelCatalog.DEFAULT_UPSCALE
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, stored.getString(Prefs.aiKey(AiStep.UPSCALE), null))
        // None as well, where none is shown.
        val descreen = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.DESCREEN))!!
        if (descreen.callChangeListener(Prefs.AI_NONE)) descreen.value = Prefs.AI_NONE
        assertEquals(Prefs.AI_NONE, stored.getString(Prefs.aiKey(AiStep.DESCREEN), null))
    }

    @Test
    fun aStepsPickerSpacesItsChoicesAndStoresATap() {
        Prefs(context).aiEnabled = true
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        pref.performClick()
        idle()
        val shown = fragment.parentFragmentManager.findFragmentByTag(AiChoiceDialog.TAG) as AiChoiceDialog
        val dialog = shown.dialog as AlertDialog
        val list = dialog.listView
        assertEquals(pref.entries.size, list.adapter.count)
        assertEquals(pref.findIndexOfValue(pref.value), list.checkedItemPosition)
        val row = list.adapter.getView(1, null, list) as CheckedTextView
        assertEquals(pref.entries[1].toString(), row.text.toString())
        val padding = context.resources.getDimensionPixelSize(R.dimen.ai_choice_padding)
        assertTrue(padding > 0)
        assertEquals(padding, row.paddingTop)
        assertEquals(padding, row.paddingBottom)
        // A tap picks, closes and stores.
        val other = ModelCatalog.forStep(AiStep.UPSCALE).first { it.id != pref.value && !it.slow }
        val at = pref.findIndexOfValue(other.id)
        list.performItemClick(list.adapter.getView(at, null, list), at, list.adapter.getItemId(at))
        idle()
        assertFalse(dialog.isShowing)
        assertEquals(other.id, pref.value)
        assertEquals(other.id, PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.aiKey(AiStep.UPSCALE), null))
    }

    @Test
    fun eachStepOffersNoneThenItsCatalogModels() {
        Prefs(context).keepSimple = false
        val fragment = settings()
        for (step in AiStep.entries) {
            val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(step))!!
            assertEquals(listOf(Prefs.AI_NONE) + ModelCatalog.forStep(step).map { it.id }, pref.entryValues.map { it.toString() })
            assertEquals(context.getString(R.string.ai_none), pref.entries[0].toString())
            assertTrue(pref.entries[1].toString().startsWith(ModelCatalog.forStep(step)[0].name + " · "))
            // Each with what it does below.
            for ((i, m) in ModelCatalog.forStep(step).withIndex()) assertEquals(ModelText.entry(context, m).toString(), pref.entries[i + 1].toString())
            assertFalse("needs AI processing on", pref.isEnabled)
        }
        fragment.findPreference<Preference>(Prefs.KEY_AI)!!.performClick()
        assertTrue(Prefs(context).aiEnabled)
        for (step in AiStep.entries) assertTrue(fragment.findPreference<ListPreference>(Prefs.aiKey(step))!!.isEnabled)
    }

    @Test
    fun slowModelsAreOfferedOnlyWithAdvancedSettingsShown() {
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        val simple = pref.entryValues.map { it.toString() }
        assertEquals(listOf(Prefs.AI_NONE) + ModelCatalog.forStep(AiStep.UPSCALE).filter { !it.slow }.map { it.id }, simple)
        assertTrue(pref.entries[1].toString().endsWith("Faithful, fast and reliable clarity improvement (default)"))
        val keep = fragment.findPreference<Preference>("pref_keep_simple")!!
        keep.performClick()
        idle()
        assertFalse(Prefs(context).keepSimple)
        assertEquals(listOf(Prefs.AI_NONE) + ModelCatalog.forStep(AiStep.UPSCALE).map { it.id }, pref.entryValues.map { it.toString() })
        val slow = pref.entries[pref.findIndexOfValue("realesr-animevideov3-x2")].toString()
        assertTrue(slow, slow.endsWith("\nSlow (~11 s per page)"))
        keep.performClick()
        idle()
        assertEquals(simple, pref.entryValues.map { it.toString() })
    }

    @Test
    fun choicesShowWhetherTheirModelIsDownloaded() {
        val upscalers = ModelCatalog.forStep(AiStep.UPSCALE)
        install(upscalers[0])
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        if (pref.callChangeListener(upscalers[0].id)) pref.value = upscalers[0].id
        awaitSummary(pref, upscalers[0].name)
        if (pref.callChangeListener(upscalers[1].id)) pref.value = upscalers[1].id
        awaitSummary(pref, context.getString(R.string.ai_model_missing, upscalers[1].name))
        assertEquals(upscalers[1].id, Prefs(context).aiModel(AiStep.UPSCALE))
        if (pref.callChangeListener(Prefs.AI_NONE)) pref.value = Prefs.AI_NONE
        awaitSummary(pref, context.getString(R.string.ai_none))
        assertNull(Prefs(context).aiModel(AiStep.UPSCALE))
        val manager = fragment.findPreference<Preference>("ai_models")!!
        assertTrue(manager.summary.toString().startsWith(context.getString(R.string.ai_models_summary, 1, "").trimEnd()))
    }

    @Test
    fun aChoiceShowsItsDownloadProgressAsText() {
        val m = ModelCatalog.forStep(AiStep.UPSCALE)[0]
        assertEquals(context.getString(R.string.ai_none), ModelText.choice(context, null, false, null))
        assertEquals(m.name, ModelText.choice(context, m, true, DownloadState.Running(1, 2)))
        assertEquals("${m.name} · downloading 40%", ModelText.choice(context, m, false, DownloadState.Running(40, 100)))
        assertEquals(context.getString(R.string.ai_model_failed, m.name), ModelText.choice(context, m, false, DownloadState.Failed(DownloadFailure.NETWORK)))
        assertEquals(context.getString(R.string.ai_model_missing, m.name), ModelText.choice(context, m, false, null))
        // Shown through a summary provider: a ListPreference would read "40%" as a format.
        val pref = settings().findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        idle()
        assertNotNull(pref.summaryProvider)
        pref.summaryProvider = Preference.SummaryProvider<ListPreference> { "${m.name} · downloading 40%" }
        assertEquals("${m.name} · downloading 40%", pref.summary.toString())
    }

    @Test
    fun theAiSummaryShowsTheWholeChainAndWarnsWhenItIsSlow() {
        val prefs = Prefs(context)
        val slowest = AiStep.entries.map { step -> ModelCatalog.forStep(step).maxBy { it.pageMs } }
        val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        for (m in slowest + upscale) install(m)
        assertTrue(AiChain.slow(slowest))
        prefs.aiEnabled = true
        for (m in slowest) prefs.setAiModel(m.step, m.id)
        // The advanced steps show while Keep it simple hides their pickers.
        assertTrue(prefs.keepSimple)
        var ai = settings().findPreference<Preference>(Prefs.KEY_AI)!!
        awaitSummary(ai, ModelText.chain(context, slowest))
        assertTrue(ai.summary.toString().contains(slowest[0].name))
        assertTrue(ai.summary.toString().endsWith(context.getString(R.string.ai_chain_slow)))
        prefs.setAiModel(AiStep.ARTIFACTS, null)
        prefs.setAiModel(AiStep.DESCREEN, null)
        prefs.setAiModel(AiStep.UPSCALE, upscale.id)
        ai = settings().findPreference<Preference>(Prefs.KEY_AI)!!
        awaitSummary(ai, ModelText.chain(context, listOf(upscale)))
        assertFalse(ai.summary.toString().contains(context.getString(R.string.ai_chain_slow)))
        ai.performClick()
        awaitSummary(ai, context.getString(R.string.pref_ai_desc))
    }

    @Test
    fun batterySaverPausesAiUnlessTheBatterySettingSaysNot() {
        val prefs = Prefs(context)
        assertTrue(Thrift.of(true, prefs).pausedAi)
        assertFalse(Thrift.of(false, prefs).pausedAi)
        prefs.saverAi = false
        assertFalse(Thrift.of(true, prefs).pausedAi)
    }

    @Test
    fun aiChoicesAreBackedUp() {
        val prefs = Prefs(context)
        prefs.aiEnabled = true
        prefs.setAiModel(AiStep.DESCREEN, ModelCatalog.forStep(AiStep.DESCREEN)[0].id)
        prefs.setAiModel(AiStep.UPSCALE, null)
        prefs.saverAi = false
        val saved = prefs.exportAll()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).importAll(saved)
        val back = Prefs(context)
        assertTrue(back.aiEnabled)
        assertEquals(ModelCatalog.forStep(AiStep.DESCREEN)[0].id, back.aiModel(AiStep.DESCREEN))
        assertNull(back.aiModel(AiStep.UPSCALE))
        assertFalse(back.saverAi)
    }
}
