package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import com.infinity.comicreader.reader.NativeResampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Why picked files were not taken in before any check ran. */
enum class ImportFailure {
    /** Not one `.param` and one `.bin`. */
    NOT_A_PAIR,

    /** A file could not be read. */
    UNREADABLE,

    /** Larger than [ModelImports.MAX_PARAM_BYTES] or [ModelImports.MAX_BIN_BYTES]. */
    TOO_LARGE,

    /** Not enough free space to copy them. */
    SPACE,

    /** The app's storage would not take them, though space is free. */
    STORAGE
}

/** What a fit model's check found that the user weighs before taking it in ([ModelCheck]). */
enum class ImportWarning {
    /** A page takes longer than [ModelCheck.WARN_PAGE_MS] here. */
    SLOW
}

/** The test pictures an import shows a fit model's before and after on, in order; each an [asset], one AI tile or so square. */
enum class PreviewPicture(val asset: String) {
    /** Clean painted art with complex gradients. */
    GRADIENTS("import-preview/gradients.png"),

    /** Painted art JPEG-compressed until blocks and ringing show. */
    JPEG("import-preview/jpeg.jpg"),

    /** Black-and-white comic art with a dot screen, as manga screentone has. */
    DOTS("import-preview/dots.png");

    companion object {
        private const val TAG = "PreviewPicture"

        /** Each picture that reads, opaque ARGB_8888 at its own size; each other one logged and left out. Blocking. */
        fun load(context: Context): List<Pair<PreviewPicture, Bitmap>> {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            return entries.mapNotNull { p ->
                val decoded = runCatching { context.assets.open(p.asset).use { BitmapFactory.decodeStream(it, null, options) } }
                    .onFailure { Log.w(TAG, "${p.asset}: ${it.message}") }
                    .getOrNull()
                usable(p, decoded)?.let { p to it }
            }
        }

        /** [b] when it is ARGB_8888; else null, [p] logged as left out and [b] recycled. */
        internal fun usable(p: PreviewPicture, b: Bitmap?): Bitmap? {
            if (b?.config == Bitmap.Config.ARGB_8888) return b
            Log.w(TAG, "${p.asset}: left out, decoded as ${b?.config}")
            b?.recycle()
            return null
        }
    }
}

/** Test [picture] before a fit model ran on it ([before], one tile's worth) and after ([after]). */
data class ImportPreview(val picture: PreviewPicture, val before: Bitmap, val after: Bitmap)

/** Where an import is. */
sealed interface ImportState {
    /** Nothing under way. */
    data object Idle : ImportState

    /** One file of the pair picked ([uri]): a `.param` when [isParam], else a `.bin`; the other is still to pick. */
    data class Half(val uri: Uri, val isParam: Boolean) : ImportState

    /** Copying the files, then checking the model ([checking]) named [name] for now. */
    data class Working(val name: String, val checking: Boolean) : ImportState

    /**
     * Checked and fit: with [warnings] not yet [accepted] ([ModelImports.accept]), waits for the
     * user to import it anyway or cancel; then, with [previews] not yet [seen]
     * ([ModelImports.previewed]), for the user to look at them and go on or cancel; then for a
     * name and a step ([ModelImports.save]). [suggested] is the file's name; [pageMs] timed at
     * thermal [headroom].
     */
    data class Fit(
        val id: String,
        val suggested: String,
        val scale: Int,
        val pageMs: Long,
        val headroom: Float = Float.NaN,
        val warnings: Set<ImportWarning> = emptySet(),
        val accepted: Boolean = false,
        val previews: List<ImportPreview> = emptyList(),
        val seen: Boolean = false
    ) : ImportState {
        /** Waits for the user to weigh its [warnings]. */
        val warned: Boolean get() = warnings.isNotEmpty() && !accepted

        /** Waits, its warnings weighed, for the user to look at its [previews]. */
        val previewing: Boolean get() = !warned && previews.isNotEmpty() && !seen
    }

    /** The check refused it: [why]; for memory, the [bytes] it would need and the [limit] it would pass; for time, the [pageMs] a page would take (0: unknown). */
    data class Refused(val name: String, val why: ModelRefusal, val bytes: Long, val limit: Long, val pageMs: Long) : ImportState

    /** The files were not taken in: [why]. */
    data class Failed(val why: ImportFailure) : ImportState

    /** Taken in as [model]. */
    data class Imported(val model: UserModel) : ImportState
}

/**
 * Imports the user's own models, one at a time, in the app's process: an ncnn `.param` and
 * `.bin` picked through the system's file picker, copied into the store's staging folder
 * (no conversion), then checked ([ModelCheck]) on this device's engine within its model
 * memory ([ModelMemory]). A fit model warned of as slow waits for the user to import it
 * anyway ([accept]) or cancel ([dismiss]); then each waits while the user looks at its before
 * and after on the test pictures ([PreviewPicture], [previewed]), then to be named and given
 * its step, then
 * moves among the imported models ([UserModels]). While a check runs, a marker names the
 * model, so a check that ends the app is told of next time ([crashed]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ModelImports {
    private const val TAG = "ModelImports"

    /** The largest files taken: a `.param` is text of a few layers per line; a `.bin` holds the weights. */
    const val MAX_PARAM_BYTES = 4L shl 20
    const val MAX_BIN_BYTES = 512L shl 20

    /** The first line of every ncnn `.param`. */
    private const val MAGIC = "7767517"

    /** The marker of a check under way, under the store's root. */
    const val CHECKING = "checking"

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    /** Where imports, saves, deletes and [tidyAtStart] run, one at a time. */
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    @Volatile
    private var job: Job? = null

    /** What checks run on; JVM tests stand in their own. */
    @Volatile
    internal var runtime: InferenceRuntime = LibNcnn

    /** The model memory of the device; tests stand in their own. */
    @Volatile
    internal var memory: (Context) -> Long = ModelMemory::of

    /** The clock checks are timed by; tests stand in their own. */
    @Volatile
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /**
     * Takes in the picked [uris]: with a [ImportState.Half] under way, its file and this one;
     * one file alone waits for the other ([ImportState.Half]).
     */
    fun start(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        val store = ModelStore.of(app)
        val half = _state.value as? ImportState.Half
        val all = if (half != null) listOf(half.uri) + uris else uris
        // A fit model not saved, replaced: its files go.
        (_state.value as? ImportState.Fit)?.let { fit -> scope.launch { store.staging(fit.id).deleteRecursively() } }
        _state.value = ImportState.Working("", checking = false)
        // Started once it is the job: a job canceled since, still ending, no longer reports.
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            val report = { s: ImportState -> if (job === me) _state.value = s }
            val end = try {
                importNow(app, all, { me?.isActive == false }, report)
            } catch (e: CancellationException) {
                ImportState.Idle
            }
            if (job === me && _state.value is ImportState.Working) {
                _state.value = end
            } else if (end is ImportState.Fit) {
                // Canceled after the check's last look: nobody will save or drop it.
                store.staging(end.id).deleteRecursively()
            }
        }
        job = launched
        launched.start()
    }

    /** Stops an import under way, keeping nothing of it. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = ImportState.Idle
    }

    /** Back to nothing under way, dropping a fit model not saved. */
    fun dismiss(context: Context) {
        val fit = _state.value as? ImportState.Fit
        _state.value = ImportState.Idle
        if (fit != null) {
            val store = ModelStore.of(context)
            scope.launch { store.staging(fit.id).deleteRecursively() }
        }
    }

    /** Takes the fit model's warnings as weighed: it goes on to its before and after, then to be named. */
    fun accept() {
        _state.update { s -> (s as? ImportState.Fit)?.copy(accepted = true) ?: s }
    }

    /** Takes the fit model's before and after as looked at, once its warnings are accepted: it goes on to be named. */
    fun previewed() {
        _state.update { s -> (s as? ImportState.Fit)?.takeIf { !it.warned }?.copy(seen = true) ?: s }
    }

    /** Takes the fit model in as [name] at [step], once its warnings are accepted and its before and after looked at; it must suit its scale ([steps]). */
    fun save(context: Context, name: String, step: AiStep) {
        val fit = _state.value as? ImportState.Fit ?: return
        if (fit.warned || fit.previewing) return
        require(step in steps(fit.scale)) { "$step does not suit ${fit.scale}x" }
        val store = ModelStore.of(context)
        _state.value = ImportState.Working(name, checking = false)
        scope.launch {
            _state.value = try {
                ImportState.Imported(saveNow(store, fit, name, step))
            } catch (e: IOException) {
                Log.w(TAG, "${fit.id}: ${e.message}")
                store.staging(fit.id).deleteRecursively()
                ImportState.Failed(storing(store.root))
            }
        }
    }

    /** The steps a model of [scale] may run at: those that keep the size for 1x, Upscale for larger. */
    fun steps(scale: Int): List<AiStep> = if (scale == 1) listOf(AiStep.ARTIFACTS, AiStep.DESCREEN) else listOf(AiStep.UPSCALE)

    /** Deletes imported model [m]. */
    fun delete(context: Context, m: UserModel) {
        val store = ModelStore.of(context)
        scope.launch {
            store.delete(m)
            UserModels.load(store)
            ModelDownloads.changed()
        }
    }

    /** The name of the model whose check ended the app, as [recover] read it; null for none. */
    private var ended: String? = null

    /**
     * Reads and deletes the marker of a check that ended the app, keeping the model's name for
     * [crashed]. At start, before anything cleans the store; a check under way keeps its marker.
     * Blocking.
     */
    @Synchronized
    fun recover(store: ModelStore) {
        if (job?.isActive == true) return
        val marker = File(store.root, CHECKING)
        if (!marker.exists()) return
        ended = runCatching { marker.readText() }.getOrNull().orEmpty()
        marker.delete()
    }

    /**
     * At start: reads the marker of a check that ended the app ([recover]), then deletes
     * models the catalog no longer has and what imports left ([ModelStore.prune]). Blocking.
     */
    fun tidy(store: ModelStore) {
        recover(store)
        store.prune()
    }

    /** [tidy], queued with the imports' work: it never deletes the files of an import under way. */
    fun tidyAtStart(store: ModelStore) {
        scope.launch { runCatching { tidy(store) } }
    }

    /** Waits for the work queued so far: [tidyAtStart], imports, saves and deletes. Blocking; for tests. */
    internal fun settle() = runBlocking(serial) {}

    /** The name of a model whose check ended the app, once; null for none. Blocking. */
    @Synchronized
    fun crashed(context: Context): String? {
        recover(ModelStore.of(context))
        return ended.also { ended = null }
    }

    /**
     * Copies and checks [uris]: the state it ends in; [report] gets the states on the way.
     * Blocking; throws [CancellationException] once [canceled].
     */
    internal fun importNow(
        context: Context,
        uris: List<Uri>,
        canceled: () -> Boolean = { false },
        report: (ImportState) -> Unit = {}
    ): ImportState {
        val picked = uris.mapNotNull { uri -> peek(context, uri)?.let { uri to it } }
        if (picked.size != uris.size) return ImportState.Failed(ImportFailure.UNREADABLE)
        if (picked.size == 1) return ImportState.Half(picked[0].first, picked[0].second.isParam)
        val params = picked.filter { it.second.isParam }
        val bins = picked.filter { !it.second.isParam }
        if (picked.size != 2 || params.size != 1 || bins.size != 1) return ImportState.Failed(ImportFailure.NOT_A_PAIR)
        val (paramUri, param) = params[0]
        val (binUri, bin) = bins[0]
        if (param.bytes > MAX_PARAM_BYTES || bin.bytes > MAX_BIN_BYTES) return ImportState.Failed(ImportFailure.TOO_LARGE)
        val name = param.name.substringBeforeLast('.').trim().ifEmpty { param.name }.take(UserModel.MAX_NAME)
        val store = ModelStore.of(context)
        val id = newId()
        val dir = store.staging(id)
        report(ImportState.Working(name, checking = false))
        var kept = false
        try {
            if (!dir.mkdirs()) return ImportState.Failed(storing(context.noBackupFilesDir))
            if (dir.usableSpace < param.bytes + bin.bytes + ModelDownloader.SPARE_BYTES) return ImportState.Failed(ImportFailure.SPACE)
            val files = store.userFiles(dir)
            try {
                copy(context, paramUri, files.param, MAX_PARAM_BYTES, canceled)
                copy(context, binUri, files.bin, MAX_BIN_BYTES, canceled)
            } catch (e: TooLarge) {
                return ImportState.Failed(ImportFailure.TOO_LARGE)
            } catch (e: IOException) {
                Log.w(TAG, "$name: ${e.message}")
                return ImportState.Failed(if (dir.usableSpace < ModelDownloader.SPARE_BYTES) ImportFailure.SPACE else ImportFailure.UNREADABLE)
            }
            val budget = memory(context)
            // A binary or broken .param: ncnn reads text ones only.
            if (!files.param.readText().trimStart().startsWith(MAGIC)) return ImportState.Refused(name, ModelRefusal.NOT_LOADED, 0, budget, 0)
            report(ImportState.Working(name, checking = true))
            val pictures = PreviewPicture.load(context)
            val marker = File(store.root, CHECKING)
            marker.writeText(name)
            val result = try {
                ModelCheck.check(
                    runtime, files, NativeResampler.threads, budget, canceled, clock, headroom = { headroom(context) },
                    pictures = pictures.map { it.second }
                )
            } finally {
                marker.delete()
            }
            return when (result) {
                is ModelCheck.Result.Fit -> {
                    val previews = pictures.zip(result.previews) { (picture, _), p -> ImportPreview(picture, p.before, p.after) }
                    ImportState.Fit(id, name, result.scale, result.pageMs, result.headroom, result.warnings, previews = previews).also { kept = true }
                }
                is ModelCheck.Result.Refused -> ImportState.Refused(name, result.why, result.bytes, result.limit, result.pageMs)
            }
        } finally {
            if (!kept) dir.deleteRecursively()
        }
    }

    /** Takes [fit] in as [name] at [step]: the model as saved. Blocking. */
    internal fun saveNow(store: ModelStore, fit: ImportState.Fit, name: String, step: AiStep): UserModel {
        val files = store.userFiles(store.staging(fit.id))
        val m = UserModel(
            fit.id, name.trim().take(UserModel.MAX_NAME).ifEmpty { fit.suggested }, step, fit.scale, fit.pageMs,
            files.param.length(), files.bin.length(), ModelDownloader.sha256(files.param), ModelDownloader.sha256(files.bin), fit.headroom
        )
        store.saveUser(m)
        UserModels.load(store)
        ModelDownloads.changed()
        return m
    }

    /** What a picked file is: its [name], [bytes] (or -1 when unknown) and whether it is the `.param`. */
    private class Picked(val name: String, val bytes: Long, val isParam: Boolean)

    /**
     * [uri] as picked; null when it does not read. The `.param` is told by its name, or, for a
     * name ending in neither `.param` nor `.bin`, by its first line.
     */
    private fun peek(context: Context, uri: Uri): Picked? {
        val resolver = context.contentResolver
        var name: String? = null
        var bytes = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) bytes = c.getLong(1)
                }
            }
        }
        val head = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val b = ByteArray(MAGIC.length + 3)
                b.copyOf(readUpTo(input, b))
            }
        }.getOrNull() ?: return null
        val text = String(head, Charsets.ISO_8859_1).trimStart().startsWith(MAGIC)
        val called = name ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(File.separatorChar)
        val isParam = when {
            called == null -> text
            called.endsWith(".param", ignoreCase = true) -> true
            called.endsWith(".bin", ignoreCase = true) -> false
            else -> text
        }
        return Picked(called ?: (if (isParam) "model.param" else "model.bin"), bytes, isParam)
    }

    private fun readUpTo(input: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private class TooLarge : IOException()

    /** Copies [uri] to [to], at most [limit] bytes, else throws [TooLarge]; throws [CancellationException] once [canceled]. */
    private fun copy(context: Context, uri: Uri, to: File, limit: Long, canceled: () -> Boolean) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { i ->
            to.outputStream().use { o ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (canceled()) throw CancellationException("Import canceled")
                    val n = i.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw TooLarge()
                    o.write(buffer, 0, n)
                }
            }
        }
    }

    /** Why storing under [dir] failed: no space, or the storage itself. */
    private fun storing(dir: File): ImportFailure =
        if (dir.usableSpace < ModelDownloader.SPARE_BYTES) ImportFailure.SPACE else ImportFailure.STORAGE

    /** The device's thermal headroom now (1: throttling); NaN where unknown. */
    private fun headroom(context: Context): Float =
        if (Build.VERSION.SDK_INT >= 30) context.getSystemService(PowerManager::class.java)?.getThermalHeadroom(0) ?: Float.NaN else Float.NaN

    /** A new imported model's id: never a catalog id. */
    private fun newId(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${System.nanoTime()}:${Math.random()}".toByteArray())
        return "user-" + bytes.take(6).joinToString("") { "%02x".format(it) }
    }
}
