package com.infinity.comicreader.ui

import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ShareChoice
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.files.BookUris
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.FileUtils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares a book: a dialog of check boxes, each followed by what it sends (the title, the
 * cover, the file), starting from the last choice ([Prefs.shareChoice]), then the system's
 * share sheet. The cover shows as its row in the list has it ([shownCover]); the whole cover
 * sent is made only once its box is ticked, then goes as a PNG through the app's
 * FileProvider; the file goes in place, through [com.infinity.comicreader.files.BookProvider],
 * never copied first.
 */
internal class BookShare(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val shownCover: (BookEntry) -> Bitmap? = { null }
) {

    fun show(entry: BookEntry) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_share_book, null)
        val title = titleOf(entry)
        val last = prefs.shareChoice
        val titleCheck = view.findViewById<CompoundButton>(R.id.share_title_check)
        val coverCheck = view.findViewById<CompoundButton>(R.id.share_cover_check)
        val fileCheck = view.findViewById<CompoundButton>(R.id.share_file_check)
        val coverImage = view.findViewById<ImageView>(R.id.share_cover_image)
        val coverProgress = view.findViewById<View>(R.id.share_cover_progress)
        val coverNote = view.findViewById<TextView>(R.id.share_cover_none)
        view.findViewById<TextView>(R.id.share_title_text).text = title
        view.findViewById<ImageView>(R.id.share_file_icon).apply {
            setImageResource(EntryIcons.iconFor(entry))
            imageTintList = ColorStateList.valueOf(EntryIcons.tintFor(activity, entry))
        }
        view.findViewById<TextView>(R.id.share_file_text).text =
            if (entry.size > 0) "${entry.name}\n${FileUtils.formatSize(activity, entry.size)}" else entry.name
        titleCheck.isChecked = last.title
        coverCheck.isChecked = last.cover
        fileCheck.isChecked = last.file
        for ((row, check) in listOf(R.id.share_title_row to titleCheck, R.id.share_cover_row to coverCheck, R.id.share_file_row to fileCheck)) {
            view.findViewById<View>(row).setOnClickListener { if (check.isEnabled) check.toggle() }
        }

        // The row's cover stands in until the one sent is made; without one, a note says what the box sends.
        val preview = runCatching { shownCover(entry) }.getOrNull()
        coverProgress.visibility = View.GONE
        if (preview != null) {
            coverImage.setImageBitmap(preview)
        } else {
            coverNote.setText(R.string.share_cover)
            coverNote.visibility = View.VISIBLE
        }

        val app = activity.applicationContext
        val charset = prefs.charset
        val style = prefs.coverStyle
        val target = Target(COVER_WIDTH, COVER_HEIGHT, Fit.SHRINK, linear = style.linear, scaling = style.scaling)
        var making: Deferred<Bitmap?>? = null
        var coverFound = true
        var sent = false

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.file_op_share)
            .setView(view)
            .setPositiveButton(R.string.file_op_share, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        val send = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        fun picked() = ShareChoice(titleCheck.isChecked, coverCheck.isChecked && coverFound, fileCheck.isChecked)
        fun update() {
            send.isEnabled = picked().any
        }

        // The cover sent: made once, from the first time it is wanted, and shown when made.
        fun cover(): Deferred<Bitmap?> = making ?: activity.lifecycleScope.async(AppDispatchers.io) {
            runCatching { Thumbs.shareCover(app, entry, charset, target) }.getOrNull()
        }.also { made ->
            making = made
            coversMade++
            if (preview == null) {
                coverNote.visibility = View.GONE
                coverProgress.visibility = View.VISIBLE
            }
            activity.lifecycleScope.launch {
                val bitmap = runCatching { made.await() }.getOrNull()
                if (made.isCancelled) return@launch
                coverProgress.visibility = View.GONE
                if (bitmap != null) {
                    coverImage.setImageBitmap(bitmap)
                    coverNote.visibility = View.GONE
                } else {
                    coverFound = false
                    coverImage.setImageDrawable(null)
                    coverNote.setText(R.string.share_no_cover)
                    coverNote.visibility = View.VISIBLE
                    coverCheck.isEnabled = false
                    coverCheck.isChecked = false
                }
                update()
            }
        }

        for (check in listOf(titleCheck, coverCheck, fileCheck)) {
            check.setOnCheckedChangeListener { _, checked ->
                if (check === coverCheck && checked) cover()
                update()
            }
        }
        if (coverCheck.isChecked) cover()
        update()
        dialog.setOnDismissListener { if (!sent) making?.cancel() }

        send.setOnClickListener {
            val choice = picked()
            if (!choice.any) return@setOnClickListener
            // A missing cover leaves the choice as it was for books that have one.
            prefs.shareChoice = choice.copy(cover = if (coverFound) choice.cover else last.cover)
            sent = true
            send.isEnabled = false
            val coverMade = if (choice.cover) cover() else null
            activity.lifecycleScope.launch {
                val coverUri = coverMade?.let { made ->
                    withContext(AppDispatchers.io) { runCatching { made.await()?.let { writeCover(it, title) } }.getOrNull() }
                }
                dialog.dismiss()
                val fileUri = if (choice.file) withContext(AppDispatchers.io) { runCatching { BookUris.of(app, entry.path) }.getOrNull() } else null
                if (choice.file && fileUri == null) {
                    Toast.makeText(activity, R.string.op_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (choice.cover && coverUri == null && !choice.title && !choice.file) {
                    Toast.makeText(activity, R.string.op_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val intent = ShareIntents.of(title.takeIf { choice.title }, coverUri, fileUri, BookUris.mimeOf(entry.name)) ?: return@launch
                runCatching { activity.startActivity(Intent.createChooser(intent, null)) }
            }
        }
    }

    /** Writes [bitmap] as the one shared cover under cache/shared, which the FileProvider serves, named after [title]. */
    private fun writeCover(bitmap: Bitmap, title: String): Uri {
        val dir = File(activity.cacheDir, COVER_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val name = title.takeIf { FileUtils.isSafeName(it) && it.length <= MAX_NAME } ?: "cover"
        val out = File(dir, "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(activity, "${activity.packageName}.files", out)
    }

    companion object {
        /** The box the shared cover is shrunk into, never enlarged. */
        const val COVER_WIDTH = 1200
        const val COVER_HEIGHT = 1800

        private const val COVER_DIR = "shared/cover"
        private const val MAX_NAME = 200

        /** Covers begun for sending, ever; for tests. */
        @Volatile
        internal var coversMade = 0

        /** What a book is shared as: its file name without the extension. */
        fun titleOf(entry: BookEntry): String = if (entry.isDir) entry.name else entry.name.substringBeforeLast('.').ifEmpty { entry.name }
    }
}
