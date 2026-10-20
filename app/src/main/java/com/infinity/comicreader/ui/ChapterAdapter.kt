package com.infinity.comicreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Chapter

/** Rows of the chapter drawer: [chapters], with the one at [current] highlighted; [onPick] gets a tapped one. */
class ChapterAdapter(
    private val chapters: List<Chapter>,
    private val current: Int,
    private val onPick: (Chapter) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.chapter_name)
        val pages: TextView = view.findViewById(R.id.chapter_pages)
    }

    override fun getItemCount() = chapters.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val chapter = chapters[position]
        val res = holder.itemView.resources
        holder.name.text = chapter.name ?: res.getString(R.string.chapter_loose)
        holder.pages.text = if (chapter.count == 1) {
            res.getString(R.string.chapter_page, chapter.first + 1)
        } else {
            res.getString(R.string.chapter_pages, chapter.first + 1, chapter.last + 1)
        }
        holder.itemView.isActivated = position == current
        holder.itemView.setOnClickListener { onPick(chapter) }
    }
}
