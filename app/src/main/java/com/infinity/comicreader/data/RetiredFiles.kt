package com.infinity.comicreader.data

import android.content.Context

/** Storage that earlier versions wrote and nothing reads any more. */
object RetiredFiles {
    /** Preferences files replaced by other storage: `book_facts` moved to [BookFactsCache]'s database. */
    internal val PREFS = listOf("book_facts")

    /** Deletes every retired file still present; does nothing once they are gone. Blocking. */
    fun drop(context: Context) {
        for (name in PREFS) context.deleteSharedPreferences(name)
    }
}
