package com.infinity.comicreader.files

import android.content.Context
import android.net.Uri
import com.infinity.comicreader.util.Formats
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * The `content://` URIs [BookProvider] serves books by: `content://<package>.books/<key>/<name>`,
 * key = a random one issued for the book's path, so the URI tells nothing of where the book
 * is and only a URI this app issued opens anything; the name last, for apps that take a
 * file's name from its URI. Keys are kept on disk ([SharedKeys]), those of the
 * [SharedKeys.MAX_ACTIONS] latest share actions and those of files open now
 * ([BookProvider.openKeys]), so a URI still opens after the app was stopped, from any of
 * its processes.
 */
object BookUris {
    fun authority(packageName: String): String = "$packageName.books"

    /** The URI of the book at [path], shared alone ([all]). Blocking (disk). */
    fun of(context: Context, path: String): Uri = all(context, listOf(path)).single()

    /**
     * The URIs of the books at [paths], shared in one action: each with the key it was
     * issued before, else a new one, all kept together. Blocking (disk).
     */
    fun all(context: Context, paths: List<String>): List<Uri> =
        keysIn(context).issue(paths, BookProvider.openKeys()).zip(paths) { key, path ->
            Uri.Builder()
                .scheme("content")
                .authority(authority(context.packageName))
                .appendPath(key)
                .appendPath(File(path).name)
                .build()
        }

    /** The key of a URI [of] issued; null for any other. */
    fun keyOf(context: Context, uri: Uri): String? =
        if (uri.authority == authority(context.packageName)) uri.pathSegments.firstOrNull() else null

    /** The path [uri] stands for; null for one this app did not issue ([of]) or no longer keeps. Blocking (disk). */
    fun pathOf(context: Context, uri: Uri): String? {
        if (uri.authority != authority(context.packageName)) return null
        return uri.pathSegments.firstOrNull()?.let { keysIn(context).pathOf(it) }
    }

    internal fun keysIn(context: Context) = SharedKeys(File(context.filesDir, SharedKeys.FILE))

    /** Forgets every key issued, so their URIs open nothing, except those of files open now. Blocking (disk). */
    fun forgetKeys(context: Context) = keysIn(context).clear(BookProvider.openKeys())

    /** The MIME type a file named [name] is sent as: registered types where there are, else common ones. */
    fun mimeOf(name: String): String = MIME[Formats.ext(name)] ?: "application/octet-stream"

    private val MIME = mapOf(
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "cb7" to "application/x-cb7",
        "cbt" to "application/x-cbt",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "jxl" to "image/jxl",
        "tif" to "image/tiff",
        "tiff" to "image/tiff"
    )
}

/**
 * The keys [BookUris] issued, each with its book's path and the share action it was last
 * issued in, in [file], latest last: those of the [MAX_ACTIONS] latest actions, all of an
 * action together however many books it shared, and those [issue] is told are open. One
 * line per key, `key<TAB>path<TAB>action` with the path base64url-encoded (a line without
 * an action is one of its own). Written whole through a temporary file and a rename, so a
 * reader in another process never sees half of it.
 */
internal class SharedKeys(private val file: File) {

    /** A key issued for [path] in [action]. */
    private data class Entry(val key: String, val path: String, val action: String)

    /**
     * The keys for [paths], shared in one new action: each the one issued before, else a
     * new one. Keys of older actions go, but never those in [open].
     */
    fun issue(paths: List<String>, open: Set<String> = emptySet()): List<String> = synchronized(LOCK) {
        val entries = read()
        val action = newKey(ACTION_BYTES)
        val issued = paths.distinct().map { path -> Entry(entries.firstOrNull { it.path == path }?.key ?: newKey(KEY_BYTES), path, action) }
        val renewed = issued.map { it.key }.toSet()
        val all = entries.filter { it.key !in renewed } + issued
        val kept = all.map { it.action }.distinct().takeLast(MAX_ACTIONS).toSet()
        write(all.filter { it.action in kept || it.key in open })
        paths.map { path -> issued.first { it.path == path }.key }
    }

    /** The key for [path], shared alone ([issue]). */
    fun issue(path: String, open: Set<String> = emptySet()): String = issue(listOf(path), open).single()

    /** Forgets every key but those in [open]. */
    fun clear(open: Set<String> = emptySet()) = synchronized(LOCK) {
        write(read().filter { it.key in open })
    }

    /** The path issued [key]; null when none was or it is no longer kept. */
    fun pathOf(key: String): String? {
        if (!KEY.matches(key)) return null
        return synchronized(LOCK) { read() }.lastOrNull { it.key == key }?.path
    }

    private fun read(): List<Entry> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
            val path = runCatching { String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8) }.getOrNull()
            path?.takeIf { it.isNotEmpty() && '\u0000' !in it }?.let { Entry(parts[0], it, parts.getOrNull(2)?.ifEmpty { null } ?: parts[0]) }
        }.toList()
    }

    private fun write(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeText(entries.joinToString("") { "${it.key}\t${ENCODER.encodeToString(it.path.toByteArray(Charsets.UTF_8))}\t${it.action}\n" })
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun newKey(bytes: Int): String = ENCODER.encodeToString(ByteArray(bytes).also { RANDOM.nextBytes(it) })

    companion object {
        const val FILE = "shared-books"

        /** How many share actions keep their keys: the books shared lately, however many at a time. */
        const val MAX_ACTIONS = 20
        private const val KEY_BYTES = 16
        private const val ACTION_BYTES = 6
        private val KEY = Regex("[A-Za-z0-9_-]{22}")
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val RANDOM = SecureRandom()
        private val LOCK = Any()
    }
}
