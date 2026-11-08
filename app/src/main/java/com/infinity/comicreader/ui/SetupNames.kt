package com.infinity.comicreader.ui

import android.content.res.Resources
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Candidate

/** What a calibration setup ([Candidate]) is called on the calibration screen: "Resizing on 4 big cores", "JPEG XL on 8 cores". */
object SetupNames {
    fun of(res: Resources, c: Candidate): String = when (c.kind) {
        Candidate.Kind.PIXELS -> when {
            c.gpu -> res.getString(R.string.setup_resize_gpu)
            c.pinned -> res.getQuantityString(R.plurals.setup_resize_big, c.threads, c.threads)
            else -> res.getQuantityString(R.plurals.setup_resize_any, c.threads, c.threads)
        }
        Candidate.Kind.JXL ->
            if (c.pinned) res.getQuantityString(R.plurals.setup_jxl_big, c.threads, c.threads)
            else res.getQuantityString(R.plurals.setup_jxl_any, c.threads, c.threads)
        Candidate.Kind.JPEG -> res.getString(if (c.pinned) R.string.setup_jpeg_big else R.string.setup_jpeg_any)
    }
}
