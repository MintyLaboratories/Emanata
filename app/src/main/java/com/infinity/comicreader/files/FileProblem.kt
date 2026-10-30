package com.infinity.comicreader.files

import java.io.IOException

/** Why a file operation on [name] stopped, for the user to be told plainly. */
class FileProblem(val kind: Kind, val name: String) : IOException("$kind: $name") {
    enum class Kind {
        /** What it would replace holds the item itself. */
        HOLDS_SOURCE,

        /** A link: links are not copied. */
        LINK,

        /** Its source changed while it was copied. */
        CHANGED,

        /** Its copy read back differs from the source. */
        DIFFERS,

        /** Its copy is missing or shorter than the source. */
        INCOMPLETE,

        /** Gone by the time its operation's turn came. */
        GONE,

        /** Changed, or another item in its place, by the time its operation's turn came. */
        ALTERED
    }

    companion object {
        /** A path in a failure's message: from a `/` up to `: ` or the end. */
        private val PATH = Regex("""/[^\n]*?(?=: |$)""")

        /** [text] with each path in it cut to its last name, for the user. */
        fun namesOnly(text: String): String = PATH.replace(text) { m -> m.value.trimEnd('/').substringAfterLast('/') }
    }
}
