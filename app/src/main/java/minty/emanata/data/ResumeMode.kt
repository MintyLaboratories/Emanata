package minty.emanata.data

/** Whether a book with a saved position opens there: ask each time, always, or never. */
enum class ResumeMode(val value: String) {
    ASK("ask"),
    YES("yes"),
    NO("no");

    /**
     * The mode after the Ask dialog was answered with "Resume page" ([resume]) or
     * "First page": with [dontAsk] ticked the answer becomes the mode, else nothing changes.
     */
    fun afterAnswer(resume: Boolean, dontAsk: Boolean): ResumeMode =
        if (!dontAsk) this else if (resume) YES else NO

    /**
     * Where a book of [pageCount] pages opens: at [requested] when the caller asked for a
     * page (0 or more), else from its [saved] position, unless it is [finished], which
     * opens at the first page. [Start.ask] is set when the reader should ask first.
     */
    fun start(requested: Int, saved: Int, pageCount: Int, finished: Boolean): Start {
        val last = (pageCount - 1).coerceAtLeast(0)
        if (requested >= 0) return Start(requested.coerceAtMost(last), ask = false)
        if (finished || saved !in 1..last) return Start(0, ask = false)
        return when (this) {
            NO -> Start(0, ask = false)
            YES -> Start(saved, ask = false)
            ASK -> Start(saved, ask = true)
        }
    }

    /** The page a book opens at, and whether to offer the first page instead. */
    data class Start(val page: Int, val ask: Boolean)

    companion object {
        val DEFAULT = ASK

        fun of(value: String?): ResumeMode = entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
