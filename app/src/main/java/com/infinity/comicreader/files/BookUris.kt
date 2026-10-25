package com.infinity.comicreader.files

import android.net.Uri
import com.infinity.comicreader.util.Formats
import java.io.File
import java.util.Base64

/**
 * The `content://` URIs [BookProvider] serves books by: `content://<package>.books/<key>/<name>`,
 * key = the book's path in UTF-8, base64url without padding; the name last, for apps that
 * take a file's name from its URI.
 */
object BookUris {
    fun authority(packageName: String): String = "$packageName.books"

    /** The URI of the book at [path], for the app [packageName]. */
    fun of(packageName: String, path: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority(packageName))
        .appendPath(keyOf(path))
        .appendPath(File(path).name)
        .build()

    /** The path [uri] stands for; null for one not made by [of]. */
    fun pathOf(uri: Uri): String? = uri.pathSegments.firstOrNull()?.let(::pathOfKey)

    internal fun keyOf(path: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))

    internal fun pathOfKey(key: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(key), Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotEmpty() && '\u0000' !in it }

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
