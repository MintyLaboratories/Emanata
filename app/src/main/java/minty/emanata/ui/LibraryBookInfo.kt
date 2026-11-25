package minty.emanata.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import minty.emanata.R
import minty.emanata.data.BookEntry
import minty.emanata.data.BookFactsCache
import minty.emanata.data.BookIds
import minty.emanata.data.Prefs
import minty.emanata.reader.ArchiveKind
import minty.emanata.reader.BookFactsReader
import minty.emanata.util.AppDispatchers
import minty.emanata.util.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Book info from the library, without opening the book in the reader: the file, the folder
 * it is in ([where]), its format from its leading bytes, size, pages as known, and every
 * ComicInfo field; Filename charset… from there once Keep it simple is off.
 */
internal object LibraryBookInfo {

    fun show(
        activity: AppCompatActivity,
        entry: BookEntry,
        prefs: Prefs,
        facts: BookFactsCache,
        where: String,
        onCharset: () -> Unit
    ) {
        val file = entry.file
        activity.lifecycleScope.launch {
            val (kind, info, pages) = withContext(AppDispatchers.io) {
                val kind = runCatching { ArchiveKind.detect(file) }.getOrNull()
                val info = BookFactsReader.comicInfo(file, prefs.charset, activity.cacheDir)
                val known = (entry.id ?: BookIds.known(entry.path, entry.size, entry.lastModified))?.let { facts.get(it)?.pages }
                Triple(kind, info, entry.pages.takeIf { it > 0 } ?: known?.takeIf { it > 0 })
            }
            if (activity.isFinishing) return@launch
            val text = SpannableStringBuilder()
            fun row(label: Int, value: String) {
                if (text.isNotEmpty()) text.append("\n\n")
                val start = text.length
                text.append(activity.getString(label))
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.append('\n').append(value)
            }
            row(R.string.info_file, file.name)
            if (where.isNotEmpty()) row(R.string.info_folder, where)
            row(R.string.info_format, BookInfo.format(file.name, kind, activity.getString(R.string.info_format_mismatch)))
            if (entry.size > 0) row(R.string.info_size, FileUtils.formatSize(activity, entry.size))
            pages?.let { row(R.string.info_pages, it.toString()) }
            val fields = info?.fields.orEmpty()
            if (fields.isNotEmpty()) row(R.string.info_comic_info, fields.joinToString("\n") { (k, v) -> "$k: $v" })
            val dialog = AlertDialog.Builder(activity)
                .setTitle(entry.name)
                .setMessage(text)
                .setPositiveButton(R.string.close, null)
            // An advanced setting: offered once Keep it simple is off.
            if (!prefs.keepSimple && NameCharsetDialog.applies(entry.name)) dialog.setNeutralButton(R.string.menu_name_charset) { _, _ -> onCharset() }
            dialog.show()
        }
    }
}
