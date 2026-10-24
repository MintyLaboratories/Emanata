package com.infinity.comicreader.net

/** Turns what the "network share" form holds into a [Share]. */
object ShareForm {
    sealed class Result {
        data class Ok(val share: Share) : Result()
        data class Invalid(val problem: SharePath.Problem) : Result()
    }

    /**
     * The share the form describes, reached as [kind] says, keeping [previous]'s id
     * when editing one. A blank [name] takes the path's last folder (else the share's
     * name on SMB, the host on WebDAV); a blank [password] while editing keeps
     * [previous]'s, lost or not, only for the same server reached the same way: else
     * the password is asked for again ([SharePath.Problem.PASSWORD_AGAIN]), so it never
     * goes to a mistyped host or over another protocol. With [clearPassword], a blank
     * [password] stores none. [thumbnails] says whether covers of its books are made.
     */
    fun build(
        name: String,
        path: String,
        user: String,
        password: String,
        previous: Share?,
        kind: ShareKind = ShareKind.SMB,
        thumbnails: Boolean = previous?.thumbnails ?: true,
        clearPassword: Boolean = false
    ): Result {
        val parsed: ShareAddress = when (kind) {
            ShareKind.SMB -> when (val p = SharePath.parse(path)) {
                is SharePath.Parsed.Invalid -> return Result.Invalid(p.problem)
                is SharePath.Parsed.Valid -> p.path
            }
            ShareKind.WEBDAV -> when (val p = DavUrl.parse(path)) {
                is DavUrl.Parsed.Invalid -> return Result.Invalid(p.problem)
                is DavUrl.Parsed.Valid -> p.url
            }
        }
        val top = when (parsed) {
            is SharePath -> parsed.share
            is DavUrl -> parsed.host
        }
        val label = name.trim().ifEmpty { parsed.dir.substringAfterLast('/').ifEmpty { top } }
        val keep = password.isEmpty() && previous != null && !clearPassword
        if (keep && (previous!!.password.isNotEmpty() || previous.passwordLost) && serverOf(previous.path) != serverOf(parsed)) {
            return Result.Invalid(SharePath.Problem.PASSWORD_AGAIN)
        }
        val secret = if (keep) previous!!.password else password
        val lost = keep && previous!!.passwordLost
        return Result.Ok(Share(previous?.id ?: ShareStore.newId(), label, parsed, user.trim(), secret, passwordLost = lost, thumbnails = thumbnails))
    }

    /** What a password is sent to: the protocol, host and port. */
    internal fun serverOf(address: ShareAddress): List<Any> =
        listOf(address::class, address.host.lowercase(), address.port, (address as? DavUrl)?.secure ?: false)
}
