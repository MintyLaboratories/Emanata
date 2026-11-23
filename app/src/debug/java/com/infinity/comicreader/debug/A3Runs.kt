package com.infinity.comicreader.debug

import org.json.JSONArray
import org.json.JSONObject

/** Pure parts of AiCheckService's `a3` bench: stop times and memory peaks. */
internal object A3Runs {
    /** One page canceled [atMs] after it started: it stopped [stopMs] after that, with [tilesRun] tiles done. */
    class Stop(val atMs: Long, val stopMs: Long, val tilesRun: Int)

    /**
     * [stops] of pages canceled part way (null: a page that ran to the end): how many were
     * tried and ran to the end, the median stop time (nearest rank, the lower middle for an even
     * count; null when none stopped) and every stop.
     */
    fun stopsJson(stops: List<Stop?>): JSONObject {
        val stopped = stops.filterNotNull()
        return JSONObject()
            .put("n", stops.size)
            .put("ranToEnd", stops.size - stopped.size)
            .put("stopMs", if (stopped.isEmpty()) JSONObject.NULL else Stats.percentile(stopped.map { it.stopMs }.sorted(), 50))
            .put("stopsMs", JSONArray(stopped.map { it.stopMs }))
            .put("atMs", JSONArray(stopped.map { it.atMs }))
            .put("tilesRun", JSONArray(stopped.map { it.tilesRun }))
    }

    /** Memory in KB: resident, proportional (PSS) and graphics. */
    class Mem(val rssKb: Long, val pssKb: Long, val graphicsKb: Long)

    /** The most memory seen of each kind, from [base] on. */
    class Peaks(val base: Mem) {
        var rssKb = base.rssKb
            private set
        var pssKb = base.pssKb
            private set
        var graphicsKb = base.graphicsKb
            private set

        fun seeRss(kb: Long) {
            rssKb = maxOf(rssKb, kb)
        }

        fun see(m: Mem) {
            seeRss(m.rssKb)
            pssKb = maxOf(pssKb, m.pssKb)
            graphicsKb = maxOf(graphicsKb, m.graphicsKb)
        }

        /** Base and peak of each kind, in MB. */
        fun toJson(): JSONObject = JSONObject()
            .put("rssBase", base.rssKb / 1024).put("rssPeak", rssKb / 1024)
            .put("pssBase", base.pssKb / 1024).put("pssPeak", pssKb / 1024)
            .put("gfxBase", base.graphicsKb / 1024).put("gfxPeak", graphicsKb / 1024)
    }
}
