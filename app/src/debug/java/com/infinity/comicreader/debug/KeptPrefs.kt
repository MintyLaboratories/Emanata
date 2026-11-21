package com.infinity.comicreader.debug

import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.data.WidePages
import com.infinity.comicreader.reader.TapZones
import org.json.JSONObject
import java.io.File

/**
 * The reading settings a benchmark session changes ([apply]), kept in [file] first so they
 * are put back ([restore]) even when the process dies mid-run: the next benchmark start
 * restores what [file] still holds ([restoreLeft]).
 */
internal class KeptPrefs(private val prefs: Prefs, private val file: File) {
    /** Keeps the settings as they are, then sets the layout and input [plan] reads in, without a folder's own layout. */
    fun apply(plan: BenchPlan) {
        restoreLeft(prefs, file)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(snapshot(prefs).toString())
        check(tmp.renameTo(file)) { "Could not keep the settings" }
        val continuous = plan.layout == BenchPlan.CONTINUOUS
        prefs.pageMode = if (continuous) ReaderLayout.MODE_CONTINUOUS else ReaderLayout.MODE_PAGED
        prefs.readingDir = if (continuous) ReaderLayout.DIR_TTB else ReaderLayout.DIR_LTR
        prefs.fit = if (continuous) "width" else "screen"
        prefs.twoPage = if (plan.layout == BenchPlan.TWO) TwoPage.ON else TwoPage.OFF
        prefs.autoCrop = plan.crop
        prefs.perSeries = false
        prefs.volumeKeys = plan.input == BenchPlan.KEY
        prefs.tapZones = TapZones.LEFT_RIGHT.value
        plan.doubleTap?.let { prefs.doubleTapZoom = it }
        plan.preload?.let { prefs.preload = it }
        plan.seams?.let { prefs.joinSpreads = it }
        plan.wide?.let { prefs.widePages = it }
        // Both default chains run the plan's models, so a book read as manga runs them too.
        plan.ai?.let { ids ->
            val models = ids.mapNotNull { Models.byId(it) }
            for (kind in ChainKind.entries) {
                for (step in AiStep.entries) prefs.setAiModel(step, models.firstOrNull { it.step == step }?.id, kind)
            }
            prefs.aiEnabled = models.isNotEmpty()
        }
    }

    /** Puts the kept settings back. */
    fun restore() {
        restoreLeft(prefs, file)
    }

    companion object {
        /** The settings a session changes, as they are now. */
        fun snapshot(prefs: Prefs): JSONObject = JSONObject()
            .put("pageMode", prefs.pageMode)
            .put("readingDir", prefs.readingDir)
            .put("fit", prefs.fit)
            .put("twoPage", prefs.twoPage.key)
            .put("autoCrop", prefs.autoCrop)
            .put("perSeries", prefs.perSeries)
            .put("volumeKeys", prefs.volumeKeys)
            .put("doubleTap", prefs.doubleTapZoom)
            .put("tapZones", prefs.tapZones)
            .put("preload", prefs.preload)
            .put("joinSpreads", prefs.joinSpreads)
            .put("widePages", prefs.widePages.key)
            .put("ai", prefs.aiEnabled)
            .put("aiModels", JSONObject().apply {
                for (kind in ChainKind.entries) {
                    put(kind.key, JSONObject().apply { for (step in AiStep.entries) put(step.key, prefs.aiModel(step, kind) ?: Prefs.AI_NONE) })
                }
            })

        /** Puts back the settings [file] keeps, if any, and forgets them; true when it held some. */
        fun restoreLeft(prefs: Prefs, file: File): Boolean {
            if (!file.exists()) return false
            val kept = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (kept != null) {
                prefs.pageMode = kept.getString("pageMode")
                prefs.readingDir = kept.getString("readingDir")
                prefs.fit = kept.getString("fit")
                prefs.twoPage = TwoPage.of(kept.getString("twoPage"))
                prefs.autoCrop = kept.getBoolean("autoCrop")
                prefs.perSeries = kept.getBoolean("perSeries")
                prefs.volumeKeys = kept.getBoolean("volumeKeys")
                prefs.doubleTapZoom = kept.getBoolean("doubleTap")
                prefs.tapZones = kept.getString("tapZones")
                if (kept.has("preload")) prefs.preload = kept.getInt("preload")
                if (kept.has("joinSpreads")) prefs.joinSpreads = kept.getBoolean("joinSpreads")
                if (kept.has("widePages")) prefs.widePages = WidePages.of(kept.getString("widePages"))
                if (kept.has("ai")) prefs.aiEnabled = kept.getBoolean("ai")
                kept.optJSONObject("aiModels")?.let { all ->
                    for (kind in ChainKind.entries) {
                        val m = all.optJSONObject(kind.key) ?: continue
                        for (step in AiStep.entries) prefs.setAiModel(step, m.optString(step.key).takeIf { it.isNotEmpty() && it != Prefs.AI_NONE }, kind)
                    }
                }
            }
            file.delete()
            return kept != null
        }
    }
}
