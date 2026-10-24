package com.infinity.comicreader.files

import java.io.IOException

/** Why a file operation on [name] stopped, for the user to be told plainly. */
class FileProblem(val kind: Kind, val name: String) : IOException("$kind: $name") {
    enum class Kind {
        /** What it would replace holds the item itself. */
        HOLDS_SOURCE
    }
}
