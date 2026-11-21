package com.infinity.comicreader.ai

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The text detector ([CoverTextDetector]) wraparound covers find their text with, PP-OCR
 * ([ppocr]), and the arrivals of its model ([modelArrived]), after which covers made without its
 * text are made again.
 */
class CoverTextDetectors(private val ppocr: () -> CoverTextDetector) {
    /** Times PP-OCR's model arrived after covers went without it: they may now find their text. */
    @Volatile
    var arrivals = 0
        private set

    private val changed = MutableStateFlow(0)

    /** Counts [arrivals] as a flow: covers on screen are out of date at each. */
    val changes: StateFlow<Int> = changed.asStateFlow()

    /** The detector covers find their text with. */
    fun detector(): CoverTextDetector = ppocr()

    /** PP-OCR's model arrived ([DetectorModel]) after covers went without it: they find their text now. */
    @Synchronized
    fun modelArrived() {
        arrivals++
        changed.update { it + 1 }
    }

    companion object {
        @Volatile
        private var shared: CoverTextDetectors? = null

        /** The app's: PP-OCR as [TextDetector.shared]; holds only the application context. */
        fun shared(context: Context): CoverTextDetectors = shared ?: synchronized(this) {
            val app = context.applicationContext
            shared ?: CoverTextDetectors { TextDetector.shared(app) }.also { shared = it }
        }

        /** Stands [detectors] in as the app's ([shared]); null makes it anew. */
        @VisibleForTesting
        fun standIn(detectors: CoverTextDetectors?) {
            shared = detectors
        }
    }
}
