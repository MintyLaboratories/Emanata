package com.infinity.comicreader.net

/** Turns what the "network share" form holds into a [Share]. */
object ShareForm {
    sealed class Result {
        data class Ok(val share: Share) : Result()
        data class Invalid(val problem: SharePath.Problem) : Result()
    }

    /**
     * The share the form describes, keeping [previous]'s id when editing one. A blank
     * [name] takes the path's last folder (or the share's name); a blank [password]
     * while editing keeps [previous]'s.
     */
    fun build(name: String, path: String, user: String, password: String, previous: Share?): Result {
        val parsed = when (val p = SharePath.parse(path)) {
            is SharePath.Parsed.Invalid -> return Result.Invalid(p.problem)
            is SharePath.Parsed.Valid -> p.path
        }
        val label = name.trim().ifEmpty { parsed.dir.substringAfterLast('/').ifEmpty { parsed.share } }
        val secret = if (password.isEmpty() && previous != null) previous.password else password
        return Result.Ok(Share(previous?.id ?: ShareStore.newId(), label, parsed, user.trim(), secret))
    }
}
