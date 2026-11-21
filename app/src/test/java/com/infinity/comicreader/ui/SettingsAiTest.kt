package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.TwoStatePreference
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiChain
import com.infinity.comicreader.ai.AiLoad
import com.infinity.comicreader.ai.AiModel
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.AiThresholds
import com.infinity.comicreader.ai.CatalogModel
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.OfflineDownloads
import com.infinity.comicreader.ai.PageTimes
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    /** Picking a model starts its download: kept off the network and ended with the test. */
    @get:Rule
    val downloads = OfflineDownloads()

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
        assertFalse(prefs.aiDot)
        // The settings screen writes the same defaults.
        PreferenceManager.setDefaultValues(context, R.xml.settings, true)
        val written = Prefs(context)
        assertFalse(written.aiEnabled)
        assertNull(written.aiModel(AiStep.ARTIFACTS))
        assertNull(written.aiModel(AiStep.DESCREEN))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, written.aiModel(AiStep.UPSCALE))
        assertTrue(written.saverAi)
        assertFalse(written.aiDot)
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

    /** The upscale step's picker, opened from its preference. */
    private fun openUpscalePicker(fragment: PreferenceFragmentCompat): AlertDialog {
        fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!.performClick()
        idle()
        val shown = fragment.parentFragmentManager.findFragmentByTag(AiChoiceDialog.TAG) as AiChoiceDialog
        return shown.dialog as AlertDialog
    }

    @Test
    fun aStepsPickerShowsItsRowsAndStoresATap() {
        Prefs(context).aiEnabled = true
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        val dialog = openUpscalePicker(fragment)
        val rows = ModelPickerTest.rows(dialog)
        val expected = PickerRows.of(AiStep.UPSCALE, keepSimple = true, current = pref.value, noneName = context.getString(R.string.ai_none))
        assertEquals(expected.size, rows.size)
        assertEquals("waifu2x 2× (default)", ModelPickerTest.name(rows[1]))
        assertTrue(ModelPickerTest.checked(rows[1]))
        // A tap picks, closes and stores.
        val at = expected.indexOfFirst { it.models.singleOrNull()?.id == "opencomic-ai-upscale-2x-compact-v2" }
        ModelPickerTest.tap(rows[at])
        idle()
        assertFalse(dialog.isShowing)
        assertEquals("opencomic-ai-upscale-2x-compact-v2", pref.value)
        assertEquals("opencomic-ai-upscale-2x-compact-v2", PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.aiKey(AiStep.UPSCALE), null))
    }

    @Test
    fun aLevelPickedOnASliderIsStoredAndShownNextTime() {
        Prefs(context).aiEnabled = true
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        var dialog = openUpscalePicker(fragment)
        val rows = PickerRows.of(AiStep.UPSCALE, keepSimple = true, current = pref.value, noneName = context.getString(R.string.ai_none))
        val at = rows.indexOfFirst { it.models.firstOrNull()?.id == "anime4k-a-s" }
        val family = ModelPickerTest.rows(dialog)[at]
        // Unpicked, the family shows its largest level.
        assertEquals(4f, ModelPickerTest.slider(family).value)
        ModelPickerTest.slide(family, 1)
        idle()
        // Stored at once, the picker still open.
        assertTrue(dialog.isShowing)
        assertEquals("anime4k-a-m", pref.value)
        assertEquals("anime4k-a-m", PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.aiKey(AiStep.UPSCALE), null))
        assertTrue(ModelPickerTest.checked(family))
        assertFalse(ModelPickerTest.checked(ModelPickerTest.rows(dialog)[1]))
        dialog.dismiss()
        idle()
        // Opened again, the slider is where it was left.
        dialog = openUpscalePicker(fragment)
        val again = ModelPickerTest.rows(dialog)[at]
        assertEquals(1f, ModelPickerTest.slider(again).value)
        assertTrue(ModelPickerTest.checked(again))
        assertEquals("anime4k-a-m", Prefs(context).aiModel(AiStep.UPSCALE))
    }

    @Test
    fun aLevelStillSettlingIsStoredAsThePickerCloses() {
        Prefs(context).aiEnabled = true
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        val dialog = openUpscalePicker(fragment)
        val rows = PickerRows.of(AiStep.UPSCALE, keepSimple = true, current = pref.value, noneName = context.getString(R.string.ai_none))
        val at = rows.indexOfFirst { it.models.firstOrNull()?.id == "anime4k-b-s" }
        ModelPickerTest.press(ModelPickerTest.rows(dialog)[at], 2)
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, Prefs(context).aiModel(AiStep.UPSCALE))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertFalse(dialog.isShowing)
        assertEquals("anime4k-b-l", pref.value)
        assertEquals("anime4k-b-l", Prefs(context).aiModel(AiStep.UPSCALE))
    }

    @Test
    fun onlyTheLevelPickedLastDownloadsAsThePickerCloses() {
        Prefs(context).aiEnabled = true
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        val dialog = openUpscalePicker(fragment)
        val rows = PickerRows.of(AiStep.UPSCALE, keepSimple = true, current = pref.value, noneName = context.getString(R.string.ai_none))
        val at = rows.indexOfFirst { it.models.firstOrNull()?.id == "anime4k-a-s" }
        fun asked(id: String) = ModelDownloads.state.value.states.containsKey(id)
        // Let go at M, then at L: each is stored, neither downloads while the picker is open.
        ModelPickerTest.slide(ModelPickerTest.rows(dialog)[at], 1)
        ModelPickerTest.slide(ModelPickerTest.rows(dialog)[at], 2)
        idle()
        assertEquals("anime4k-a-l", pref.value)
        assertFalse(asked("anime4k-a-m"))
        assertFalse(asked("anime4k-a-l"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals("anime4k-a-l", Prefs(context).aiModel(AiStep.UPSCALE))
        assertTrue(asked("anime4k-a-l"))
        assertFalse(asked("anime4k-a-m"))
    }

    @Test
    fun aRebuiltPickerKeepsItsOpenPanels() {
        Prefs(context).aiEnabled = true
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        idle()
        var fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val dialog = openUpscalePicker(fragment)
        val name = "Anime4K A (sharp) 2×"
        ModelPickerTest.rows(dialog).first { ModelPickerTest.name(it) == name }.findViewById<android.view.View>(R.id.ai_model_info).performClick()
        controller.recreate()
        idle()
        fragment = controller.get().supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val shown = fragment.parentFragmentManager.findFragmentByTag(AiChoiceDialog.TAG) as AiChoiceDialog
        val row = ModelPickerTest.rows(shown.dialog!!).first { ModelPickerTest.name(it) == name }
        assertEquals(android.view.View.VISIBLE, row.findViewById<android.view.View>(R.id.ai_model_details).visibility)
        val other = ModelPickerTest.rows(shown.dialog!!).first { ModelPickerTest.name(it) == "CuNNy 2×" }
        assertEquals(android.view.View.GONE, other.findViewById<android.view.View>(R.id.ai_model_details).visibility)
    }

    @Test
    fun eachStepOffersNoneThenItsCatalogModels() {
        Prefs(context).keepSimple = false
        val fragment = settings()
        for (kind in ChainKind.entries) for (step in AiStep.entries) {
            val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(step, kind))!!
            val offered = Models.offered(step, keepSimple = false, current = null)
            assertEquals(listOf(Prefs.AI_NONE) + offered.map { it.id }, pref.entryValues.map { it.toString() })
            assertEquals(context.getString(R.string.ai_none), pref.entries[0].toString())
            // Each by its short name.
            for ((i, m) in offered.withIndex()) assertEquals(PickerRows.shortName(m), pref.entries[i + 1].toString())
            assertFalse("needs AI processing on", pref.isEnabled)
        }
        fragment.findPreference<Preference>(Prefs.KEY_AI)!!.performClick()
        assertTrue(Prefs(context).aiEnabled)
        for (kind in ChainKind.entries) for (step in AiStep.entries) assertTrue(fragment.findPreference<ListPreference>(Prefs.aiKey(step, kind))!!.isEnabled)
    }

    @Test
    fun slowModelsAreOfferedOnlyWithAdvancedSettingsShown() {
        val fragment = settings()
        val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE))!!
        val simple = pref.entryValues.map { it.toString() }
        assertEquals(listOf(Prefs.AI_NONE) + Models.offered(AiStep.UPSCALE, keepSimple = true, current = null).map { it.id }, simple)
        val keep = fragment.findPreference<Preference>("pref_keep_simple")!!
        keep.performClick()
        idle()
        assertFalse(Prefs(context).keepSimple)
        assertEquals(listOf(Prefs.AI_NONE) + Models.offered(AiStep.UPSCALE, keepSimple = false, current = null).map { it.id }, pref.entryValues.map { it.toString() })
        assertEquals("Real-ESRGAN 2×", pref.entries[pref.findIndexOfValue("realesr-animevideov3-x2")].toString())
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
        assertTrue(AiChain.slow(AiChain.pageMs(slowest)))
        prefs.aiEnabled = true
        for (m in slowest) prefs.setAiModel(m.step, m.id)
        // The advanced steps show while Keep it simple hides their pickers.
        assertTrue(prefs.keepSimple)
        var ai = settings().findPreference<Preference>(Prefs.KEY_AI)!!
        awaitSummary(ai, ModelText.chains(context, mapOf(ChainKind.MANGA to listOf(upscale), ChainKind.OTHER to slowest)))
        assertTrue(ai.summary.toString().contains("Everything else: " + slowest.joinToString(" → ") { it.name }))
        assertTrue(ai.summary.toString().contains("Manga: ${upscale.name}"))
        assertTrue(ai.summary.toString().endsWith(context.getString(R.string.ai_chain_slow)))
        prefs.setAiModel(AiStep.ARTIFACTS, null)
        prefs.setAiModel(AiStep.DESCREEN, null)
        prefs.setAiModel(AiStep.UPSCALE, upscale.id)
        ai = settings().findPreference<Preference>(Prefs.KEY_AI)!!
        awaitSummary(ai, ModelText.chains(context, mapOf(ChainKind.MANGA to listOf(upscale), ChainKind.OTHER to listOf(upscale))))
        assertFalse(ai.summary.toString().contains(context.getString(R.string.ai_chain_slow)))
        // Either chain slow is warned of.
        for (m in slowest) prefs.setAiModel(m.step, m.id, ChainKind.MANGA)
        ai = settings().findPreference<Preference>(Prefs.KEY_AI)!!
        awaitSummary(ai, ModelText.chains(context, mapOf(ChainKind.MANGA to slowest, ChainKind.OTHER to listOf(upscale))))
        assertTrue(ai.summary.toString().endsWith(context.getString(R.string.ai_chain_slow)))
        ai.performClick()
        awaitSummary(ai, context.getString(R.string.pref_ai_desc))
    }

    @Test
    fun theAiSummaryShowsSizesKeepItSimpleHides() {
        val prefs = Prefs(context)
        prefs.aiEnabled = true
        prefs.aiThresholds = AiThresholds(60, AiThresholds.ALWAYS)
        assertTrue(prefs.keepSimple)
        val fragment = settings()
        val ai = fragment.findPreference<Preference>(Prefs.KEY_AI)!!
        val chain = ModelText.chains(context, ChainKind.entries.associateWith { AiChain.chosen(prefs, it) })
        awaitSummary(ai, chain + "\n" + AiSizeText.changed(context, prefs.aiThresholds))
        // With advanced settings shown, the slider says it.
        fragment.findPreference<TwoStatePreference>("pref_keep_simple")!!.performClick()
        assertFalse(prefs.keepSimple)
        awaitSummary(ai, chain)
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
        prefs.setAiModel(AiStep.ARTIFACTS, ModelCatalog.forStep(AiStep.ARTIFACTS)[0].id, ChainKind.MANGA)
        prefs.setAiModel(AiStep.UPSCALE, "opencomic-ai-upscale-2x-compact-v2", ChainKind.MANGA)
        prefs.saverAi = false
        val saved = prefs.exportAll()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Prefs(context).importAll(saved)
        val back = Prefs(context)
        assertTrue(back.aiEnabled)
        assertEquals(ModelCatalog.forStep(AiStep.DESCREEN)[0].id, back.aiModel(AiStep.DESCREEN))
        assertNull(back.aiModel(AiStep.UPSCALE))
        assertEquals(ModelCatalog.forStep(AiStep.ARTIFACTS)[0].id, back.aiModel(AiStep.ARTIFACTS, ChainKind.MANGA))
        assertNull(back.aiModel(AiStep.DESCREEN, ChainKind.MANGA))
        assertEquals("opencomic-ai-upscale-2x-compact-v2", back.aiModel(AiStep.UPSCALE, ChainKind.MANGA))
        assertFalse(back.saverAi)
    }

    /** Waits for [pref]'s bar to show [expected]: it is set once the store is read off the main thread. */
    private fun awaitLoad(pref: AiChainPreference, expected: Long) {
        val end = System.currentTimeMillis() + 5_000
        while (pref.pageMs != expected && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(10)
        }
        assertEquals(expected, pref.pageMs)
    }

    @Test
    fun eachChainsLoadFollowsItsModelsAndTheTimesLearnedHere() {
        val times = PageTimes.of(context)
        times.clear()
        try {
            val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
            val fragment = settings()
            val manga = fragment.findPreference<AiChainPreference>("ai_chain_manga")!!
            val other = fragment.findPreference<AiChainPreference>("ai_chain_other")!!
            awaitLoad(manga, upscale.pageMs)
            awaitLoad(other, upscale.pageMs)
            // A model picked for manga weighs on manga's bar alone.
            val descreen = ModelCatalog.forStep(AiStep.DESCREEN).first()
            val pref = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.DESCREEN, ChainKind.MANGA))!!
            if (pref.callChangeListener(descreen.id)) pref.value = descreen.id
            awaitLoad(manga, descreen.pageMs + upscale.pageMs)
            assertEquals(upscale.pageMs, other.pageMs)
            // A time learned here replaces the catalog's, and scales the times of models not learned yet alike.
            times.keep(upscale, 1_234)
            val again = settings()
            awaitLoad(again.findPreference("ai_chain_other")!!, 1_234)
            awaitLoad(again.findPreference("ai_chain_manga")!!, Math.round(descreen.pageMs * 1_234.0 / upscale.pageMs) + 1_234)
        } finally {
            times.clear()
        }
    }

    @Test
    fun theSlowWarningFollowsTheTimesLearnedHereAsTheLoadBarDoes() {
        val times = PageTimes.of(context)
        times.clear()
        try {
            Prefs(context).aiEnabled = true
            val upscale = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
            val chains = ChainKind.entries.associateWith { listOf<AiModel>(upscale) }
            val slow = context.getString(R.string.ai_chain_slow)
            // Fast by the catalog; slow as learned on this device, its bar red.
            assertFalse(ModelText.chains(context, chains).contains(slow))
            val learned = AiLoad.HEAVY_MS + 1
            times.keep(upscale, learned)
            val fragment = settings()
            awaitLoad(fragment.findPreference("ai_chain_other")!!, learned)
            val ai = fragment.findPreference<Preference>(Prefs.KEY_AI)!!
            awaitSummary(ai, ModelText.chains(context, chains, ChainKind.entries.associateWith { learned }))
            assertTrue(ai.summary.toString().endsWith(slow))
            assertEquals(AiLoad.Level.HEAVY, AiLoad.level(learned))
        } finally {
            times.clear()
        }
    }

    @Test
    fun bothDefaultChainsStartWithOnlyTheDefaultUpscaler() {
        val prefs = Prefs(context)
        for (kind in ChainKind.entries) {
            assertNull(prefs.aiModel(AiStep.ARTIFACTS, kind))
            assertNull(prefs.aiModel(AiStep.DESCREEN, kind))
            assertEquals(ModelCatalog.DEFAULT_UPSCALE, prefs.aiModel(AiStep.UPSCALE, kind))
        }
        // Everything else keeps the keys of the single default it replaces; manga's carry the kind.
        assertEquals("pref_ai_upscale", Prefs.aiKey(AiStep.UPSCALE))
        assertEquals("pref_ai_upscale", Prefs.aiKey(AiStep.UPSCALE, ChainKind.OTHER))
        assertEquals("pref_ai_manga_upscale", Prefs.aiKey(AiStep.UPSCALE, ChainKind.MANGA))
    }

    @Test
    fun eachDefaultChainHasAHeaderAndItsOwnPickers() {
        val fragment = settings()
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = (0 until fragment.preferenceScreen.preferenceCount).flatMap { i ->
            val g = fragment.preferenceScreen.getPreference(i) as? androidx.preference.PreferenceGroup ?: return@flatMap emptyList()
            (0 until g.preferenceCount).map { g.getPreference(it).key }
        }
        // Manga first, then everything else, each header above its steps.
        val order = listOf("ai_chain_manga") + AiStep.entries.map { Prefs.aiKey(it, ChainKind.MANGA) } +
            "ai_chain_other" + AiStep.entries.map { Prefs.aiKey(it, ChainKind.OTHER) }
        assertEquals(order, keys.filter { it in order })
        assertEquals(context.getString(R.string.ai_chain_manga), fragment.findPreference<Preference>("ai_chain_manga")!!.title.toString())
        assertEquals(context.getString(R.string.ai_chain_other), fragment.findPreference<Preference>("ai_chain_other")!!.title.toString())
        // A manga pick stores under manga's key only.
        val manga = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.UPSCALE, ChainKind.MANGA))!!
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, manga.value)
        assertFalse(stored.contains(Prefs.aiKey(AiStep.UPSCALE, ChainKind.MANGA)))
        if (manga.callChangeListener("opencomic-ai-upscale-2x-compact-v2")) manga.value = "opencomic-ai-upscale-2x-compact-v2"
        assertEquals("opencomic-ai-upscale-2x-compact-v2", Prefs(context).aiModel(AiStep.UPSCALE, ChainKind.MANGA))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, Prefs(context).aiModel(AiStep.UPSCALE))
        assertFalse(stored.contains(Prefs.aiKey(AiStep.UPSCALE)))
        // Picking manga's shown default stores it there.
        val descreen = fragment.findPreference<ListPreference>(Prefs.aiKey(AiStep.DESCREEN, ChainKind.MANGA))!!
        if (descreen.callChangeListener(Prefs.AI_NONE)) descreen.value = Prefs.AI_NONE
        assertEquals(Prefs.AI_NONE, stored.getString(Prefs.aiKey(AiStep.DESCREEN, ChainKind.MANGA), null))
        assertFalse(stored.contains(Prefs.aiKey(AiStep.DESCREEN)))
    }
}
