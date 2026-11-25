package minty.emanata.ui

/**
 * What a background search for sort values covers: the [listing] it was started
 * for, and whether it reads [books] (page counts and years) and folder [sizes].
 */
data class FactsWork(val listing: Int, val books: Boolean, val sizes: Boolean) {
    /** True when this work already finds everything [other] asks for. */
    fun covers(other: FactsWork): Boolean =
        listing == other.listing && (books || !other.books) && (sizes || !other.sizes)
}
