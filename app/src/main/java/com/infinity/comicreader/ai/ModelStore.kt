package com.infinity.comicreader.ai

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Where models live: one folder per downloaded [FileSet] (catalog models and the app's own
 * models) under [root], named by its id, holding its files once downloaded and verified, each
 * with a stamp of the SHA-256 it was verified against (a catalog that changes a file's checksum makes it download again). A file
 * still downloading is a `.part` beside them, kept to resume from. Imported models
 * ([UserModel]) live under [userRoot], one folder each holding [USER_PARAM], [USER_BIN] and
 * what the user named it and the check measured ([USER_INFO]); one being imported waits in
 * [staging] until taken in (what the app's end leaves there goes at the next start, with the
 * folders of models the catalog dropped).
 */
class ModelStore(val root: File) {
    fun dir(set: FileSet): File = File(root, set.id)

    internal fun target(set: FileSet, file: ModelFile) = File(dir(set), file.name)

    internal fun part(set: FileSet, file: ModelFile) = File(dir(set), file.name + PART)

    private fun stamp(set: FileSet, file: ModelFile) = File(dir(set), file.name + STAMP)

    /** True once [file] is in place at its size, verified against the catalog's SHA-256. */
    fun has(set: FileSet, file: ModelFile): Boolean =
        target(set, file).length() == file.bytes && runCatching { stamp(set, file).readText() }.getOrNull() == file.sha256

    /** Marks [file], just verified, as in place. */
    internal fun stamped(set: FileSet, file: ModelFile) = stamp(set, file).writeText(file.sha256)

    /** True once all of [set]'s files are in place ([has]). */
    fun installed(set: FileSet): Boolean = set.files.all { has(set, it) }

    /** True once both of [model]'s files are in place ([has]). */
    fun installed(model: CatalogModel): Boolean = installed(model as FileSet)

    /** [model]'s files for [Sessions.open]; null until [installed]. */
    fun files(model: AiModel): ModelFiles? = when (model) {
        is CatalogModel -> if (installed(model)) ModelFiles(target(model, model.param), target(model, model.bin)) else null
        is UserModel -> userFiles(userDir(model.id)).takeIf { it.param.isFile && it.bin.isFile }
    }

    /** True once [model]'s files are in place: downloaded and verified, or imported. */
    fun installed(model: AiModel): Boolean = when (model) {
        is CatalogModel -> installed(model as FileSet)
        is UserModel -> files(model) != null
    }

    /** Bytes of [set] on disk: files in place and parts. */
    fun bytesHeld(set: FileSet): Long =
        set.files.sumOf { (if (has(set, it)) it.bytes else 0L) + part(set, it).length() }

    /** Bytes held by every folder under [root], catalog models or not. */
    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes [set]'s folder, parts included; true when nothing of it is left. */
    fun delete(set: FileSet): Boolean {
        val d = dir(set)
        return !d.exists() || d.deleteRecursively()
    }

    /**
     * Deletes folders of file sets no longer in [catalog] (an update dropped or replaced them),
     * what an import left, and imported models' folders that do not read; imported models and
     * the marker of a check ([ModelImports.CHECKING]) stay. Blocking.
     */
    fun prune(catalog: Collection<FileSet> = ModelCatalog.all) {
        val ids = catalog.map { it.id }.toSet() + USER + ModelImports.CHECKING
        root.listFiles()?.filter { it.name !in ids }?.forEach { it.deleteRecursively() }
        userRoot.listFiles()?.filter { it.isDirectory && userModel(it) == null }?.forEach { it.deleteRecursively() }
    }

    /** Where imported models live. */
    val userRoot: File get() = File(root, USER)

    /** Imported model [id]'s folder. */
    fun userDir(id: String): File = File(userRoot, id)

    /** The model files in imported model folder [dir]. */
    fun userFiles(dir: File): ModelFiles = ModelFiles(File(dir, USER_PARAM), File(dir, USER_BIN))

    /** Where model [id] waits while it is imported. */
    fun staging(id: String): File = File(File(root, STAGING), id)

    /** The imported models, by name; folders whose [USER_INFO] is missing or does not read are left out ([prune] deletes them). */
    fun userModels(): List<UserModel> =
        userRoot.listFiles()?.filter { it.isDirectory }.orEmpty().mapNotNull(::userModel).sortedBy { it.name.lowercase() }

    /** The imported model in folder [dir]; null when its [USER_INFO] is missing or does not read. */
    private fun userModel(dir: File): UserModel? {
        val p = runCatching { Properties().apply { File(dir, USER_INFO).inputStream().use { load(it) } } }.getOrNull() ?: return null
        val files = userFiles(dir)
        return UserModel.from(dir, p, files.param, files.bin)
    }

    /** Takes imported model [m] in: what it says goes beside its files in [staging], and the folder into [userRoot]. */
    fun saveUser(m: UserModel) {
        val from = staging(m.id)
        from.resolve(USER_INFO).outputStream().use { m.toProperties().store(it, null) }
        userRoot.mkdirs()
        if (!from.renameTo(userDir(m.id))) throw IOException("Cannot move ${from.name} into ${userRoot.name}")
    }

    /** Deletes imported model [m]; true when nothing of it is left. */
    fun delete(m: UserModel): Boolean {
        val d = userDir(m.id)
        return !d.exists() || d.deleteRecursively()
    }

    companion object {
        const val PART = ".part"
        const val STAMP = ".sha256"

        /** The folder of imported models, the one of models being imported, and the names of an imported model's files. */
        const val USER = "imported"
        const val STAGING = "importing"
        const val USER_PARAM = "model.param"
        const val USER_BIN = "model.bin"
        const val USER_INFO = "model.properties"

        /** The app's store: not backed up (the files download again) and not cleared with the cache. */
        fun of(context: Context) = ModelStore(File(context.noBackupFilesDir, "ai-models"))
    }
}

/** Why a download did not finish. */
enum class DownloadFailure {
    /** No connection, or it broke: what arrived is kept to resume from. */
    NETWORK,

    /** The server answered with an error. */
    SERVER,

    /** The file arrived whole but not as the catalog says: it was thrown away. */
    MISMATCH,

    /** Not enough free space for what is left. */
    SPACE,

    /** Not started: nobody asked for it, and downloads are skipped on this metered connection ([com.infinity.comicreader.net.NetPolicy]). */
    METERED
}

/** Thrown by [ModelDownloader.download] when a download cannot finish. */
class DownloadException(val failure: DownloadFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Downloads file sets (catalog models and the app's own) into [store], one file after the other: each into its `.part`,
 * resumed where it stopped (HTTP Range; a server that sends the whole file again starts it
 * over), then checked against the catalog's size and SHA-256 and renamed into place. A part
 * that does not match is deleted; one cut short is kept.
 */
class ModelDownloader(private val store: ModelStore, private val client: OkHttpClient = defaultClient) {

    /**
     * Downloads what [set] still lacks. [progress] gets bytes held and the total after each
     * chunk; [canceled] is checked between chunks, and throws [CancellationException] (parts
     * kept). Throws [DownloadException] when it cannot finish. Blocking.
     */
    fun download(
        set: FileSet,
        canceled: () -> Boolean = { false },
        progress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val dir = store.dir(set)
        if (!dir.isDirectory && !dir.mkdirs()) throw DownloadException(DownloadFailure.SPACE, "Cannot create $dir")
        val left = set.bytes - store.bytesHeld(set)
        if (left > 0 && dir.usableSpace < left + SPARE_BYTES) {
            throw DownloadException(DownloadFailure.SPACE, "${left shr 20} MB needed, ${dir.usableSpace shr 20} MB free")
        }
        var before = 0L
        for (file in set.files) {
            if (!store.has(set, file)) {
                store.target(set, file).delete()
                fetch(set, file, canceled) { held -> progress(before + held, set.bytes) }
            }
            before += file.bytes
            progress(before, set.bytes)
        }
    }

    private fun fetch(set: FileSet, file: ModelFile, canceled: () -> Boolean, progress: (Long) -> Unit) {
        val part = store.part(set, file)
        if (part.length() > file.bytes) part.delete()
        var restarted = false
        while (part.length() < file.bytes) {
            if (canceled()) throw CancellationException("Download canceled")
            val from = part.length()
            val request = Request.Builder().url(file.url).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
            try {
                client.newCall(request).execute().use { response ->
                    val append = when {
                        response.code == 206 && from > 0 && contentRangeStart(response.header("Content-Range")) == from -> true
                        response.code == 200 -> false
                        // Asked past the end, or a range not as asked: start over, once.
                        (response.code == 416 || response.code == 206) && !restarted -> {
                            restarted = true
                            part.delete()
                            return@use
                        }
                        else -> throw DownloadException(DownloadFailure.SERVER, "HTTP ${response.code} for ${file.url}")
                    }
                    val body = response.body ?: throw DownloadException(DownloadFailure.SERVER, "No body for ${file.url}")
                    FileOutputStream(part, append).use { out ->
                        var held = if (append) from else 0L
                        val buffer = ByteArray(CHUNK)
                        body.byteStream().use { input ->
                            while (true) {
                                if (canceled()) throw CancellationException("Download canceled")
                                val n = input.read(buffer)
                                if (n < 0) break
                                held += n
                                if (held > file.bytes) {
                                    throw DownloadException(DownloadFailure.MISMATCH, "${file.name} is larger than ${file.bytes} bytes")
                                }
                                out.write(buffer, 0, n)
                                progress(held)
                            }
                        }
                    }
                }
            } catch (e: DownloadException) {
                // Deleted once closed: an open file cannot be deleted everywhere.
                if (e.failure == DownloadFailure.MISMATCH) part.delete()
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException(DownloadFailure.NETWORK, "${file.name}: ${e.message}", e)
            }
            if (part.length() < file.bytes && part.length() == from) {
                throw DownloadException(DownloadFailure.NETWORK, "${file.name}: no data after $from bytes")
            }
        }
        val hash = sha256(part)
        if (!hash.equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw DownloadException(DownloadFailure.MISMATCH, "${file.name}: SHA-256 $hash, expected ${file.sha256}")
        }
        if (!part.renameTo(store.target(set, file))) throw DownloadException(DownloadFailure.SPACE, "Cannot rename ${part.name}")
        store.stamped(set, file)
    }

    companion object {
        private const val CHUNK = 64 * 1024

        /** Room kept free beyond what a download needs. */
        const val SPARE_BYTES = 64L shl 20

        /** HTTPS without redirects to plain HTTP; a stalled connection fails after 30 s. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** The first byte of a `Content-Range: bytes first-last/total` header; null when unreadable. */
        internal fun contentRangeStart(header: String?): Long? =
            header?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()

        /** [file]'s SHA-256 in lowercase hex. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
