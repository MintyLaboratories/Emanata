package com.infinity.comicreader.data

import android.content.Context
import android.os.PowerManager

/**
 * What the app holds back while Android's battery saver is on, as the Battery settings let it:
 * covers of layered images stay at their first layer ([firstLayerCovers]), one screen at most is
 * decoded ahead ([readAhead]) and nothing is fetched ahead from shares, background scans of
 * page sizes, seams and folder facts wait ([pausedScans]), and AI processing pauses ([pausedAi]).
 */
data class Thrift(
    val firstLayerCovers: Boolean = false,
    val lessReadAhead: Boolean = false,
    val pausedScans: Boolean = false,
    val pausedAi: Boolean = false
) {
    /** How many pages to decode ahead where [pages] are asked for, with [perScreen] pages a screen. */
    fun readAhead(pages: Int, perScreen: Int = 1): Int =
        if (lessReadAhead) minOf(pages, SAVER_SCREENS_AHEAD * perScreen.coerceAtLeast(1)) else pages

    companion object {
        val NONE = Thrift()

        /** Screens decoded ahead at most while [lessReadAhead]. */
        const val SAVER_SCREENS_AHEAD = 1

        /** What [p] holds back while battery saver is [saving]. */
        fun of(saving: Boolean, p: Prefs): Thrift =
            if (!saving) NONE else Thrift(p.saverCovers, p.saverReadAhead, p.saverScans, p.saverAi)

        /** True while Android's battery saver is on. */
        fun saving(context: Context): Boolean =
            runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull() == true
    }
}
