package com.infinity.comicreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Chapter
import java.io.File

/**
 * Rows of the chapter drawer ([ChapterDrawer.rows]): section headings, the book's chapters
 * and the files in its folder, the current ones highlighted. [onChapter] gets a tapped
 * chapter, [onFile] a tapped file.
 */
class ChapterAdapter(
    private val rows: List<ChapterDrawer.Row>,
    private val onChapter: (Chapter) -> Unit,
    private val onFile: (File) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.chapter_name)
        val pages: TextView = view.findViewById(R.id.chapter_pages)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.chapters_section)
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is ChapterDrawer.Row.Header) HEADER else ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_chapter_header, parent, false))
        } else {
            Holder(inflater.inflate(R.layout.item_chapter, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val res = holder.itemView.resources
        when (val row = rows[position]) {
            is ChapterDrawer.Row.Header -> (holder as HeaderHolder).title.setText(
                if (row.inBook) R.string.chapters_in_book else R.string.chapters_in_folder
            )
            is ChapterDrawer.Row.Part -> {
                val h = holder as Holder
                val chapter = row.chapter
                h.name.text = chapter.name ?: res.getString(R.string.chapter_loose)
                h.pages.visibility = View.VISIBLE
                h.pages.text = if (chapter.count == 1) {
                    res.getString(R.string.chapter_page, chapter.first + 1)
                } else {
                    res.getString(R.string.chapter_pages, chapter.first + 1, chapter.last + 1)
                }
                h.itemView.isActivated = row.current
                h.itemView.setOnClickListener { onChapter(chapter) }
            }
            is ChapterDrawer.Row.Sibling -> {
                val h = holder as Holder
                h.name.text = row.file.name.substringBeforeLast('.').ifEmpty { row.file.name }
                h.pages.visibility = if (row.read) View.VISIBLE else View.GONE
                h.pages.setText(R.string.chapter_read)
                h.itemView.isActivated = row.current
                h.itemView.setOnClickListener { onFile(row.file) }
            }
        }
    }

    private companion object {
        const val HEADER = 0
        const val ENTRY = 1
    }
}
