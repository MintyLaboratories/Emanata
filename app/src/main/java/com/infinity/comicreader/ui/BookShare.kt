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
 * share sheet. The cover goes as a PNG through the app's FileProvider; the file in place,
 * through [com.infinity.comicreader.files.BookProvider], never copied first.
 */
internal class BookShare(private val activity: AppCompatActivity, private val prefs: Prefs) {

    fun show(entry: BookEntry) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_share_book, null)
        val title = titleOf(entry)
        val last = prefs.shareChoice
        val titleCheck = view.findViewById<CompoundButton>(R.id.share_title_check)
        val coverCheck = view.findViewById<CompoundButton>(R.id.share_cover_check)
        val fileCheck = view.findViewById<CompoundButton>(R.id.share_file_check)
        val coverImage = view.findViewById<ImageView>(R.id.share_cover_image)
        val coverProgress = view.findViewById<View>(R.id.share_cover_progress)
        val coverNone = view.findViewById<View>(R.id.share_cover_none)
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

        val app = activity.applicationContext
        val charset = prefs.charset
        val style = prefs.coverStyle
        val target = Target(COVER_WIDTH, COVER_HEIGHT, Fit.SHRINK, linear = style.linear, scaling = style.scaling)
        val cover: Deferred<Bitmap?> = activity.lifecycleScope.async(AppDispatchers.io) {
            runCatching { Thumbs.shareCover(app, entry, charset, target) }.getOrNull()
        }
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
        for (check in listOf(titleCheck, coverCheck, fileCheck)) check.setOnCheckedChangeListener { _, _ -> update() }
        update()
        dialog.setOnDismissListener { if (!sent) cover.cancel() }

        activity.lifecycleScope.launch {
            val bitmap = runCatching { cover.await() }.getOrNull()
            coverProgress.visibility = View.GONE
            if (bitmap != null) {
                coverImage.setImageBitmap(bitmap)
            } else {
                coverFound = false
                coverNone.visibility = View.VISIBLE
                coverCheck.isEnabled = false
                coverCheck.isChecked = false
            }
            update()
        }

        send.setOnClickListener {
            val choice = picked()
            if (!choice.any) return@setOnClickListener
            // A missing cover leaves the choice as it was for books that have one.
            prefs.shareChoice = choice.copy(cover = if (coverFound) choice.cover else last.cover)
            sent = true
            send.isEnabled = false
            activity.lifecycleScope.launch {
                val coverUri = if (choice.cover) {
                    withContext(AppDispatchers.io) { runCatching { cover.await()?.let { writeCover(it, title) } }.getOrNull() }
                } else {
                    null
                }
                dialog.dismiss()
                if (choice.cover && coverUri == null && !choice.title && !choice.file) {
                    Toast.makeText(activity, R.string.op_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val fileUri = if (choice.file) withContext(AppDispatchers.io) { runCatching { BookUris.of(app, entry.path) }.getOrNull() } else null
                if (choice.file && fileUri == null) {
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

        /** What a book is shared as: its file name without the extension. */
        fun titleOf(entry: BookEntry): String = if (entry.isDir) entry.name else entry.name.substringBeforeLast('.').ifEmpty { entry.name }
    }
}
