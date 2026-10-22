package com.infinity.comicreader.net

import com.infinity.comicreader.net.SharePath.Problem
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Where a WebDAV share lives: [host] and [port] over HTTPS ([secure]), and the folder
 * [dir] on the server (`/`-separated, decoded, empty for the server's top). Written
 * as the form takes it: `https://host/folder`; `davs://` and a bare `host/folder` mean
 * the same. Plain `http://` (or `dav://`) is taken only for this device (loopback),
 * where the password never crosses a network.
 */
data class DavUrl(val secure: Boolean, override val host: String, override val port: Int, override val dir: String) : ShareAddress {
    /** As the form shows it: the port only when it is not the scheme's default; `%`, `?` and `#` escaped. */
    override fun toString(): String = buildString {
        append(if (secure) "https://" else "http://")
        append(if (':' in host) "[$host]" else host)
        if (port != defaultPort(secure)) append(':').append(port)
        append('/')
        append(dir.replace("%", "%25").replace("?", "%3F").replace("#", "%23"))
    }

    /** What [parse] makes of an address: a [DavUrl], or the [Problem] with it. */
    sealed class Parsed {
        data class Valid(val url: DavUrl) : Parsed()
        data class Invalid(val problem: Problem) : Parsed()
    }

    companion object {
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80

        private val SECURE_SCHEMES = listOf("https://", "davs://")
        private val PLAIN_SCHEMES = listOf("http://", "dav://")

        fun defaultPort(secure: Boolean) = if (secure) HTTPS_PORT else HTTP_PORT

        /** True when [text] starts with a web or WebDAV scheme. */
        fun hasWebScheme(text: String): Boolean =
            (SECURE_SCHEMES + PLAIN_SCHEMES).any { text.trimStart().startsWith(it, ignoreCase = true) }

        /** True for a host that is this device, as the network security config names it (`res/xml`). */
        fun isLoopback(host: String): Boolean = host.equals("localhost", ignoreCase = true) || host == "127.0.0.1"

        fun parse(input: String): Parsed {
            var text = input.trim()
            if (text.isEmpty()) return Parsed.Invalid(Problem.EMPTY)
            var secure = true
            SECURE_SCHEMES.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let { text = text.substring(it.length) }
            PLAIN_SCHEMES.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let {
                text = text.substring(it.length)
                secure = false
            }
            text = text.substringBefore('#').substringBefore('?')
            if (text.startsWith("/") || "://" in text || '\\' in text || '@' in text) return Parsed.Invalid(Problem.BAD_HOST)
            val (authority, rest) = SharePath.splitAuthority(text) ?: return Parsed.Invalid(Problem.BAD_HOST)
            val (host, port) = SharePath.hostAndPort(authority, defaultPort(secure)) ?: return Parsed.Invalid(Problem.BAD_HOST)
            if (port == null) return Parsed.Invalid(Problem.BAD_PORT)
            if (!secure && !isLoopback(host)) return Parsed.Invalid(Problem.INSECURE)
            val names = rest.split('/').filter { it.isNotEmpty() }.map { decode(it) ?: return Parsed.Invalid(Problem.BAD_NAME) }
            if (names.any { !validName(it) }) return Parsed.Invalid(Problem.BAD_NAME)
            return Parsed.Valid(DavUrl(secure, host, port, names.joinToString("/")))
        }

        /** [segment] with its `%XX` escapes decoded as UTF-8; a `%` not followed by two hex digits stays. Null when it decodes to bad UTF-8. */
        internal fun decode(segment: String): String? {
            if ('%' !in segment) return segment
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c == '%' && i + 2 < segment.length && isHex(segment[i + 1]) && isHex(segment[i + 2])) {
                    out.write(segment.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    val cp = segment.codePointAt(i)
                    out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(cp)
                }
            }
            return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString() }.getOrNull()
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** True when [name] can name a folder: not `.` or `..`, and no `/`, `\` or control characters once decoded. */
        private fun validName(name: String): Boolean =
            name != "." && name != ".." && name.none { it == '/' || it == '\\' || it < ' ' }
    }
}
