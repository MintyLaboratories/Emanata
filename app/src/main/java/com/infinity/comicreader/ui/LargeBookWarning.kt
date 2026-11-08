package com.infinity.comicreader.ui

import android.app.Dialog
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * "Warn before opening a large book on a metered connection": a book on a share larger than the
 * setting's size, opened on a metered connection, asks first (Open anyway / Cancel). Books on the
 * device, and any book while the connection is not metered, open at once.
 */
object LargeBookWarning {
    /** An activity that opens books, told which one to open once the check passes. */
    interface Host {
        /** Opens the book at [path] at [page]. */
        fun openConfirmed(path: String, page: Int)
    }

    /** Activities waiting for a book's size from its share: another tap meanwhile asks nothing. */
    private val looking: MutableSet<FragmentActivity> = Collections.newSetFromMap(WeakHashMap())

    /** True when a book of [bytes] ([remote]: on a share) asks before opening, [enabled] with [thresholdMb]. */
    fun needed(remote: Boolean, metered: Boolean, enabled: Boolean, thresholdMb: Int, bytes: Long): Boolean =
        remote && metered && enabled && bytes > thresholdMb.toLong() * 1_000_000

    /**
     * Opens [book] at [page] through [activity] ([Host.openConfirmed]) at once, or once the user
     * taps Open anyway. A size not known yet is asked of the share first, off the main thread,
     * also for a file made never to ask ([RemoteFile.asksNetwork]: history, bookmarks, library).
     * While a size is asked for or the warning is open, another check asks nothing.
     */
    fun <A> check(activity: A, book: File, page: Int) where A : FragmentActivity, A : Host {
        val prefs = Prefs(activity)
        if (book !is RemoteFile || !prefs.meteredWarn || Metered.now() != Metered.Verdict.METERED) {
            activity.openConfirmed(book.absolutePath, page)
            return
        }
        if (activity in looking || Ask.shown(activity)) return
        val threshold = prefs.meteredWarnMb
        // Remembered from the listing when there is one; never the network on the main thread.
        val known = book.length()
        if (known > 0) {
            decide(activity, book, page, known, threshold)
            return
        }
        looking += activity
        activity.lifecycleScope.launch {
            try {
                val bytes = withContext(AppDispatchers.io) { sizeOf(book) }
                decide(activity, book, page, bytes, threshold)
            } finally {
                looking -= activity
            }
        }
    }

    /** [book]'s size, asked of its share when not known; 0 when that fails. Off the main thread. */
    private fun sizeOf(book: RemoteFile): Long =
        book.length().takeIf { it > 0 } ?: runCatching { book.fresh()?.takeIf { !it.isDirectory }?.size ?: 0L }.getOrDefault(0L)

    /**
     * True when opening [book] now would ask first ([check]), so nothing opens it unasked (the
     * next chapter opened ahead waits to be reached). Off the main thread.
     */
    fun wouldAsk(prefs: Prefs, book: File): Boolean {
        if (book !is RemoteFile || !prefs.meteredWarn || Metered.now() != Metered.Verdict.METERED) return false
        return needed(remote = true, metered = true, enabled = true, thresholdMb = prefs.meteredWarnMb, bytes = sizeOf(book))
    }

    private fun <A> decide(activity: A, book: File, page: Int, bytes: Long, threshold: Int) where A : FragmentActivity, A : Host {
        if (needed(remote = true, metered = true, enabled = true, thresholdMb = threshold, bytes = bytes)) {
            Ask.show(activity, book.absolutePath, page, bytes)
        } else {
            activity.openConfirmed(book.absolutePath, page)
        }
    }

    /** Open anyway / Cancel for one book; kept across a rotation, Open anyway tells the [Host] of then. */
    class Ask : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val context = requireContext()
            val args = requireArguments()
            val path = args.getString(ARG_PATH).orEmpty()
            val page = args.getInt(ARG_PAGE)
            val size = Formatter.formatShortFileSize(context, args.getLong(ARG_BYTES))
            return Immersive.follow(
                AlertDialog.Builder(context)
                    .setTitle(R.string.big_book_title)
                    .setMessage(getString(R.string.big_book_msg, path.substringAfterLast('/'), size))
                    .setPositiveButton(R.string.big_book_open) { _, _ -> (activity as? Host)?.openConfirmed(path, page) }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
            )
        }

        companion object {
            private const val TAG = "large_book"
            private const val ARG_PATH = "path"
            private const val ARG_PAGE = "page"
            private const val ARG_BYTES = "bytes"

            /** True while the warning is open over [activity]. */
            fun shown(activity: FragmentActivity): Boolean = activity.supportFragmentManager.findFragmentByTag(TAG) != null

            /** Opens the warning for the book at [path] of [bytes] over [activity], unless one is open. */
            fun show(activity: FragmentActivity, path: String, page: Int, bytes: Long) {
                val fm = activity.supportFragmentManager
                if (fm.isStateSaved || activity.isFinishing || activity.isDestroyed || shown(activity)) return
                Ask().apply { arguments = bundleOf(ARG_PATH to path, ARG_PAGE to page, ARG_BYTES to bytes) }.showNow(fm, TAG)
            }
        }
    }
}
