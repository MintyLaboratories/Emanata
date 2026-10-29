package com.infinity.comicreader.data

/**
 * Re-encoded copies: each book opened keeps its [Likeness] ([note]); a book opened with
 * nothing kept for it yet that is like one with data kept may take that data over
 * ([carryOver]), when the reader says so. Never automatic. Blocking.
 */
object CopyMatch {
    /**
     * True when book [id], opened as [fileName], needs its likeness taken: [fresh] (nothing
     * kept for it, so it may match another), never taken, or weak and taken under another
     * file name.
     */
    fun needed(store: LikenessStore, id: BookId, fileName: String, fresh: Boolean): Boolean {
        if (fresh) return true
        val row = store.get(id) ?: return true
        return !row.likeness.strong && row.fileName != fileName
    }

    /**
     * Keeps [likeness] of book [id] opened as [fileName]; when [fresh], returns the book
     * like it with data kept in [library] (the latest seen), whose data it may take over.
     */
    fun note(store: LikenessStore, library: Library, id: BookId, likeness: Likeness, fileName: String, fresh: Boolean): LikenessStore.Row? {
        val match = if (fresh) {
            val withData by lazy { library.kept().keys }
            store.alike(likeness, id).firstOrNull { it.id in withData && library.pathOf(it.id) != null }
        } else null
        store.put(id, likeness, fileName)
        return match
    }

    /**
     * Hands what [library] keeps for [from] to [to], whose likeness is [likeness], of
     * [pageCount] pages: what is kept by page follows the page of the same name, else of
     * the same place ([Likeness.moves]). [from]'s likeness goes with its data.
     */
    fun carryOver(store: LikenessStore, library: Library, from: LikenessStore.Row, to: BookRef, likeness: Likeness, pageCount: Int) {
        library.carryOver(from.id, to, Likeness.moves(from.likeness.stems, likeness.stems), pageCount)
        store.remove(listOf(from.id))
    }
}
