package com.infinity.comicreader.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** The intent that shares a book: its title as text, and its cover and file as streams. */
object ShareIntents {
    /**
     * One ACTION_SEND for the [title] alone or with one stream, ACTION_SEND_MULTIPLE for
     * both the [cover] (a PNG) and the [file] (of type [fileType]); null when there is
     * nothing to send. The streams go in its clip too, readable by the app picked.
     */
    fun of(title: String?, cover: Uri?, file: Uri?, fileType: String): Intent? =
        of(title, listOfNotNull(cover?.let { it to COVER_TYPE }, file?.let { it to fileType }))

    /**
     * One ACTION_SEND for the [title] alone or with one stream, ACTION_SEND_MULTIPLE for
     * several [streams] (each a URI with its type); null when there is nothing to send.
     */
    fun of(title: String?, streams: List<Pair<Uri, String>>): Intent? {
        if (title == null && streams.isEmpty()) return null
        val intent = when (streams.size) {
            0 -> Intent(Intent.ACTION_SEND).setType("text/plain")
            1 -> Intent(Intent.ACTION_SEND).setType(streams[0].second).putExtra(Intent.EXTRA_STREAM, streams[0].first)
            else -> Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(commonType(streams.map { it.second }))
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams.map { it.first }))
        }
        if (title != null) intent.putExtra(Intent.EXTRA_TEXT, title).putExtra(Intent.EXTRA_SUBJECT, title)
        if (streams.isNotEmpty()) {
            val clip = ClipData.newRawUri(null, streams[0].first)
            for ((uri, _) in streams.drop(1)) clip.addItem(ClipData.Item(uri))
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    /** The narrowest type covering all [types]: the one type, any of one kind (images), or any type. */
    internal fun commonType(types: List<String>): String {
        val distinct = types.distinct()
        if (distinct.size == 1) return distinct[0]
        val top = distinct.map { it.substringBefore('/') }.distinct()
        return if (top.size == 1) "${top[0]}/*" else "*/*"
    }

    const val COVER_TYPE = "image/png"
}
