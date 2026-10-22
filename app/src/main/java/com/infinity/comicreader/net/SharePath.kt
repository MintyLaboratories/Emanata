package com.infinity.comicreader.net

/**
 * Where a network share lives: [host] and [port], the [share] on it and the folder
 * [dir] inside it (`/`-separated, empty for the share's top). Written as the form
 * takes it: `host/share/dir`, `host:port/share`, with `\` or a leading `smb://` or
 * `\\` accepted too.
 */
data class SharePath(override val host: String, override val port: Int, val share: String, override val dir: String) : ShareAddress {
    /** As the form shows it: `host/share/dir`, the port only when it is not the default. */
    override fun toString(): String = buildString {
        append(if (':' in host) "[$host]" else host)
        if (port != DEFAULT_PORT) append(':').append(port)
        append('/').append(share)
        if (dir.isNotEmpty()) append('/').append(dir)
    }

    /** Why a path or address cannot be used. */
    enum class Problem {
        EMPTY, NO_SHARE, BAD_HOST, BAD_PORT, BAD_NAME,

        /** A web address given for an SMB share. */
        WEB_ADDRESS,

        /** Plain `http://` to another device, where the password would cross the network readable. */
        INSECURE,

        /** A blank password while editing a share moved to another server or protocol: the saved one stays with the old. */
        PASSWORD_AGAIN
    }

    /** What [parse] makes of a path: a [SharePath], or the [Problem] with it. */
    sealed class Parsed {
        data class Valid(val path: SharePath) : Parsed()
        data class Invalid(val problem: Problem) : Parsed()
    }

    companion object {
        const val DEFAULT_PORT = 445

        /** Characters no SMB file, folder or share name may hold. */
        private const val FORBIDDEN = "\\/:*?\"<>|"

        private val HOST_NAME = Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\.?""")
        private val IPV6 = Regex("""[0-9A-Fa-f:.]+(%\w+)?""")

        fun parse(input: String): Parsed {
            var text = input.trim()
            if (text.isEmpty()) return Parsed.Invalid(Problem.EMPTY)
            if (DavUrl.hasWebScheme(text)) return Parsed.Invalid(Problem.WEB_ADDRESS)
            if (text.startsWith("smb://", ignoreCase = true)) text = text.substring(6)
            text = text.replace('\\', '/').trimStart('/')
            val (authority, rest) = splitAuthority(text) ?: return Parsed.Invalid(Problem.BAD_HOST)
            val (host, port) = hostAndPort(authority, DEFAULT_PORT) ?: return Parsed.Invalid(Problem.BAD_HOST)
            if (port == null) return Parsed.Invalid(Problem.BAD_PORT)
            val parts = rest.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return Parsed.Invalid(Problem.NO_SHARE)
            if (parts.any { !validName(it) }) return Parsed.Invalid(Problem.BAD_NAME)
            return Parsed.Valid(SharePath(host, port, parts.first(), parts.drop(1).joinToString("/")))
        }

        /** The host part (up to the first `/`, a bracketed IPv6 address whole) and the rest. */
        internal fun splitAuthority(text: String): Pair<String, String>? {
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                val slash = text.indexOf('/', close)
                return if (slash < 0) text to "" else text.substring(0, slash) to text.substring(slash + 1)
            }
            val slash = text.indexOf('/')
            return if (slash < 0) text to "" else text.substring(0, slash) to text.substring(slash + 1)
        }

        /**
         * The host and port of [authority], [defaultPort] when it names none; a null port
         * when one is given but out of range, null when the host is bad.
         */
        internal fun hostAndPort(authority: String, defaultPort: Int): Pair<String, Int?>? {
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                portText = if (after.isEmpty()) null else after.removePrefix(":").takeIf { after.startsWith(":") } ?: return null
                if (!IPV6.matches(host) || ':' !in host) return null
            } else {
                if (authority.count { it == ':' } > 1) return null
                host = authority.substringBefore(':')
                portText = if (':' in authority) authority.substringAfter(':') else null
                if (!HOST_NAME.matches(host)) return null
            }
            if (portText == null) return host to defaultPort
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
            return host to port
        }

        /** True when [name] can name a share, file or folder. */
        private fun validName(name: String): Boolean =
            name != "." && name != ".." && name.none { it in FORBIDDEN || it < ' ' }
    }
}
