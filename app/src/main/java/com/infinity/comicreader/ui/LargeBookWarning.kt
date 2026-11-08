package com.infinity.comicreader.ui

import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Warn before opening a large book on a metered connection": a book on a share larger than the
 * setting's size, opened on a metered connection, asks first (Open anyway / Cancel). Books on the
 * device, and any book while the connection is not metered, open at once.
 */
object LargeBookWarning {
    /** True when a book of [bytes] ([remote]: on a share) asks before opening, [enabled] with [thresholdMb]. */
    fun needed(remote: Boolean, metered: Boolean, enabled: Boolean, thresholdMb: Int, bytes: Long): Boolean =
        remote && metered && enabled && bytes > thresholdMb.toLong() * 1_000_000

    /**
     * Calls [open] for [book] at once, or once the user taps Open anyway. A size not known yet
     * is asked of the share first, off the main thread.
     */
    fun check(activity: AppCompatActivity, book: File, open: () -> Unit) {
        val prefs = Prefs(activity)
        if (book !is RemoteFile || !prefs.meteredWarn || Metered.now() != Metered.Verdict.METERED) {
            open()
            return
        }
        val threshold = prefs.meteredWarnMb
        // Remembered from the listing when there is one; never the network on the main thread.
        val known = book.length()
        if (known > 0) {
            decide(activity, book, known, threshold, open)
            return
        }
        activity.lifecycleScope.launch {
            val bytes = withContext(AppDispatchers.io) { runCatching { book.length() }.getOrDefault(0L) }
            decide(activity, book, bytes, threshold, open)
        }
    }

    private fun decide(activity: AppCompatActivity, book: File, bytes: Long, threshold: Int, open: () -> Unit) {
        if (!needed(remote = true, metered = true, enabled = true, thresholdMb = threshold, bytes = bytes)) {
            open()
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.big_book_title)
            .setMessage(activity.getString(R.string.big_book_msg, book.name, Formatter.formatShortFileSize(activity, bytes)))
            .setPositiveButton(R.string.big_book_open) { _, _ -> open() }
            .setNegativeButton(R.string.cancel, null)
            .showKeepingFullscreen()
    }
}
