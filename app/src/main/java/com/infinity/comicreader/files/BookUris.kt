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
 * file's name from its URI. Keys are kept on disk ([SharedKeys]), the [SharedKeys.MAX] latest,
 * so a URI still opens after the app was stopped, from any of its processes.
 */
object BookUris {
    fun authority(packageName: String): String = "$packageName.books"

    /** The URI of the book at [path]: the key it was issued before, else a new one. Blocking (disk). */
    fun of(context: Context, path: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority(context.packageName))
        .appendPath(keysIn(context).issue(path))
        .appendPath(File(path).name)
        .build()

    /** The path [uri] stands for; null for one this app did not issue ([of]) or no longer keeps. Blocking (disk). */
    fun pathOf(context: Context, uri: Uri): String? {
        if (uri.authority != authority(context.packageName)) return null
        return uri.pathSegments.firstOrNull()?.let { keysIn(context).pathOf(it) }
    }

    internal fun keysIn(context: Context) = SharedKeys(File(context.filesDir, SharedKeys.FILE))

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
 * The keys [BookUris] issued, each with its book's path, in [file], latest last, up to [MAX];
 * one line per key, `key<TAB>path` with the path base64url-encoded. Written whole through a
 * temporary file and a rename, so a reader in another process never sees half of it.
 */
internal class SharedKeys(private val file: File) {

    /** The key for [path]: the one issued before, kept as the latest, else a new one. */
    fun issue(path: String): String = synchronized(LOCK) {
        val entries = read()
        val key = entries.firstOrNull { it.second == path }?.first ?: newKey()
        val kept = (entries.filter { it.first != key } + (key to path)).takeLast(MAX)
        write(kept)
        key
    }

    /** The path issued [key]; null when none was or it is no longer kept. */
    fun pathOf(key: String): String? {
        if (!KEY.matches(key)) return null
        return synchronized(LOCK) { read() }.lastOrNull { it.first == key }?.second
    }

    private fun read(): List<Pair<String, String>> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            val path = runCatching { String(Base64.getUrlDecoder().decode(line.substring(tab + 1)), Charsets.UTF_8) }.getOrNull()
            path?.takeIf { it.isNotEmpty() && '\u0000' !in it }?.let { line.substring(0, tab) to it }
        }.toList()
    }

    private fun write(entries: List<Pair<String, String>>) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        part.writeText(entries.joinToString("") { (key, path) -> "$key\t${ENCODER.encodeToString(path.toByteArray(Charsets.UTF_8))}\n" })
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun newKey(): String = ENCODER.encodeToString(ByteArray(KEY_BYTES).also { RANDOM.nextBytes(it) })

    companion object {
        const val FILE = "shared-books"

        /** How many keys are kept: the books shared lately. */
        const val MAX = 200
        private const val KEY_BYTES = 16
        private val KEY = Regex("[A-Za-z0-9_-]{22}")
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val RANDOM = SecureRandom()
        private val LOCK = Any()
    }
}
