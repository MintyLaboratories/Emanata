package com.infinity.comicreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import com.infinity.comicreader.ai.CoverTextDetector
import com.infinity.comicreader.ai.CoverTextDetectors
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.TextDetector
import com.infinity.comicreader.reader.Decoders
import com.infinity.comicreader.reader.Fit
import com.infinity.comicreader.reader.HiddenPages
import com.infinity.comicreader.reader.NameCharset
import com.infinity.comicreader.reader.ImageSource
import com.infinity.comicreader.reader.BookDirection
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.reader.PdfSource
import com.infinity.comicreader.reader.Target
import com.infinity.comicreader.reader.Wraparound
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.Hashes
import com.infinity.comicreader.util.NaturalOrder
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover thumbnails for library rows. Images decode directly; archives and PDFs
 * render the page they were given as cover, else their first page not hidden ([coverPage]); folders fall back to the first image/openable file
 * found a couple of levels deep. Covers are made at exactly the size of the view
 * showing them: decoded at full size (up to [THUMB_CAP_PIXELS]; a PDF rendered
 * just covering the box), then made into the box by [CoverArt] as the [CoverStyle]
 * says, so the view draws them 1:1. They
 * are cached per size and style in memory (bounded by heap) and losslessly on disk
 * under `cacheDir/covers`. A bookmark's cover has its page in front ([CoverArt.withPage]).
 *
 * A cover page that is a layered image (a layered AVIF) can first give a quick cover made
 * from its first layer alone ([loadFirst]), kept in memory only, until [load] makes the
 * full one.
 */
object Thumbs {
    private const val DISK_DIR = "covers"

    /** Covers decode at full size below this; larger sources take the decoder's coarse reduction first. */
    private const val THUMB_CAP_PIXELS = 16_000_000L
    private const val MAX_DISK_BYTES = 160L * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(maxBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (newValue == null) provisional.remove(key)
        }
    }

    /**
     * Covers in [memory] whose focus was found without the detector running ([CoverFocus.keep]),
     * by key: the [arrivals] they were made at. One made before the detector's model arrived is
     * made again ([fromMemory]).
     */
    private val provisional = ConcurrentHashMap<String, Int>()

    /** How often a detector's model arrived this session ([CoverTextDetectors.arrivals]). Tests stand in their own. */
    @Volatile
    internal var arrivals: (Context) -> Int = { CoverTextDetectors.shared(it).arrivals }

    /** The cover in [memory] under [k], unless it was made without the detector, which has since arrived. */
    private fun fromMemory(context: Context, k: String): Bitmap? {
        val made = provisional[k]
        if (made != null && made != arrivals(context)) {
            memory.remove(k)
            return null
        }
        return memory.get(k)
    }

    private fun maxBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8L).toInt().coerceIn(8 * 1024 * 1024, 96 * 1024 * 1024)
    }

    /** Each wraparound's front half by its barcode on its full image, by [imageKey]; [NO_FRONT] when that tells nothing. */
    private val barcodes = LruCache<String, Int>(1024)

    private const val NO_FRONT = -1

    /** What each book's cover told of its reading direction this session ([knownFront]), by [frontKey], for the library and the reader alike. */
    private val fronts = LruCache<String, CoverFront>(1024)

    /**
     * The front half [src], the wraparound cover of [entry], shows ([Wraparound.Fronts.shown]):
     * by its barcode, found once per cover image ([imageKey]), else by its tallest text
     * ([CoverArt.textFront]) from each half's focus ([focusOf], with [find]). Null when neither
     * tells; then [unsure] too when the detector did not run, told whether it only waited for its
     * model ([CoverFocus.WAITING]).
     *
     * A book's full cover image ([find]), its page at book index [page], also settles what it tells
     * of reading direction ([settle]): the half its barcode and text agree on, none without a
     * barcode; left open when the text was not looked at. A smaller first layer settles nothing,
     * not even what its barcode tells. [detector] finds both halves' text and labels what is
     * settled ([CoverFront.version]).
     */
    private fun frontOf(
        context: Context,
        entry: BookEntry,
        src: Bitmap,
        innerRatio: Double?,
        page: Int?,
        find: Boolean,
        detector: CoverTextDetector,
        unsure: (waiting: Boolean) -> Unit
    ): Wraparound.Half? {
        val k = imageKey(context, entry)
        val known = barcodes.get(k)
        val barcode = if (known != null) Wraparound.Half.entries.getOrNull(known)
        else CoverArt.frontOf(src).also { if (find) barcodes.put(k, it?.ordinal ?: NO_FRONT) }
        val version = SmartCrop.version(detector.id)
        val focus = { s: Bitmap, region: Rect -> focusOf(context, entry, s, region, find, detector) }
        val open = find && page != null && fronts.get(frontKey(key(entry), idOf(entry), page))?.version != version
        if (barcode == null) {
            if (open) settle(context, entry, CoverFront(page!!, null, version))
            var waiting = true
            val seen = { s: Bitmap, region: Rect -> focus(s, region).also { if (!it.keep && it !== CoverFocus.WAITING) waiting = false } }
            return CoverArt.textFront(src, innerRatio, seen) { unsure(waiting) }
        }
        if (open) {
            var read = true
            val text = CoverArt.textFront(src, innerRatio, focus) { read = false }
            if (read) settle(context, entry, CoverFront(page!!, Wraparound.Fronts(barcode, text).agreed, version))
        }
        return barcode
    }

    /** Keeps what book [entry]'s cover told of its reading direction: for this session ([knownFront]) and with its facts ([BookFactsCache.keepCover]). */
    private fun settle(context: Context, entry: BookEntry, front: CoverFront) {
        val id = idOf(entry)
        fronts.put(frontKey(key(entry), id, front.page), front)
        if (id != null) runCatching { BookFactsCache(context).keepCover(id, front) }
    }

    /** Where the Smart crop anchor looks on each cover, by [imageKey], the part shown and the cover-focus version; kept across sessions in [focusStore]. */
    private val focuses = LruCache<String, CoverFocus>(1024)
    private const val FOCUS_PREFIX = "cover-focus"

    /** The focus store's file for cover-focus version [version]. */
    internal fun focusFile(version: String): String = "$FOCUS_PREFIX-$version.txt"

    /** Focus stores opened, by file path: one per cover-focus version. */
    private val focusStores = ConcurrentHashMap<String, FocusStore>()

    /** The focus store of cover-focus [version]. Opening one deletes the stores of other versions not open. */
    private fun focusStore(context: Context, version: String): FocusStore {
        val file = File(context.cacheDir, focusFile(version))
        focusStores[file.path]?.let { return it }
        synchronized(this) {
            focusStores[file.path]?.let { return it }
            val kept = focusStores.keys.map { File(it).name } + file.name
            context.cacheDir.listFiles { f -> f.name.startsWith(FOCUS_PREFIX) && kept.none { f.name.startsWith(it) } }?.forEach { it.delete() }
            return FocusStore(file).also { focusStores[file.path] = it }
        }
    }

    /**
     * Finds text on covers for the Smart crop anchor, and on a book's cover for its direction
     * ([CoverTextDetectors.detector]). Tests stand in their own. Asked on every cover key, list
     * binds on the main thread included: once made (off the main thread at the app's start, as
     * making it reads storage) it answers at once, without I/O.
     */
    @Volatile
    internal var detector: (Context) -> CoverTextDetector = { CoverTextDetectors.shared(it).detector() }

    /** The cover-focus version ([SmartCrop.version]) of the [detector] in use. */
    fun coverVersion(context: Context): String = SmartCrop.version(detector(context).id)

    /** [style]'s part of the key ([CoverStyle.key]) of a cover, under the [detector]. */
    fun styleKey(context: Context, style: CoverStyle): String = style.key(detector(context).id)

    /**
     * Where the Smart crop anchor looks in [region] of [entry]'s cover image [src], found once per
     * cover by [detector] and kept unless it did not run ([CoverFocus.keep]); without [find],
     * only one found before ([CoverFocus.NONE] when none was).
     */
    private fun focusOf(context: Context, entry: BookEntry, src: Bitmap, region: Rect, find: Boolean, detector: CoverTextDetector): CoverFocus {
        val part = listOf(region.left.toDouble() / src.width, region.top.toDouble() / src.height, region.right.toDouble() / src.width, region.bottom.toDouble() / src.height)
            .joinToString(",") { "%.3f".format(Locale.ROOT, it) }
        val version = SmartCrop.version(detector.id)
        val k = "${imageKey(context, entry)}|$part|$version"
        focuses.get(k)?.let { return it }
        val store = focusStore(context, version)
        store.get(k)?.let { focuses.put(k, it); return it }
        if (!find) return CoverFocus.NONE
        val focus = CoverArt.focusOf(src, region, detector)
        if (!focus.keep) return focus
        store.put(k, focus)
        focuses.put(k, focus)
        return focus
    }

    /** What book [file]'s cover, its page at book index [page], told of its reading direction this session, by the library or the reader, under any [CoverFront.version]; null when not looked at. */
    fun knownFront(file: File, page: Int): CoverFront? = fronts.get(frontKey(key(file), BookIds.known(file), page))

    /** Remembers what book [file]'s cover told ([knownFront]), so it is looked at once. */
    fun rememberFront(file: File, front: CoverFront) {
        fronts.put(frontKey(key(file), BookIds.known(file), front.page), front)
    }

    /** What [knownFront] is kept by: the book's [key], the book index of its cover page and its own file name charset ([nameTag]). */
    private fun frontKey(bookKey: String, id: BookId?, page: Int): String = "$bookKey|c$page" + nameTag(id)

    /** Quick covers ([loadFirst]) by their cover's key; a few screens' worth. */
    private val quicks = object : LruCache<String, Bitmap>(QUICK_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private const val QUICK_BYTES = 24 * 1024 * 1024

    /** Each book's typical inner page shape ([Wraparound.innerRatio]) by [key]; NaN when it has none. */
    private val inners = LruCache<String, Double>(1024)

    /** What a cover is known by: a book's [BookId] when known (no read), so a moved or copied book keeps it; a folder's path, size and time. */
    private fun key(entry: BookEntry): String {
        if (entry.isDir) return key(entry.path, entry.size, entry.lastModified, true)
        val id = entry.id ?: BookIds.known(entry.path, entry.size, entry.lastModified)
        return id?.let { "id:${it.hex}" } ?: key(entry.path, entry.size, entry.lastModified, false)
    }

    private fun key(file: File): String =
        BookIds.known(file)?.let { "id:${it.hex}" } ?: key(file.absolutePath, file.length(), file.lastModified(), false)

    private fun key(path: String, size: Long, lastModified: Long, isDir: Boolean): String = "$path|$size|$lastModified|$isDir"

    /**
     * The cover of [entry], exactly [width] x [height]. Blocking — call from a background
     * dispatcher. Returns null when no cover exists, or once [canceled] turns true between
     * its steps (nothing is kept then).
     */
    fun load(
        context: Context,
        entry: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle = CoverStyle(),
        canceled: () -> Boolean = { false }
    ): Bitmap? = make(context, entry, charset, width, height, style, canceled, quickFirst = false)?.bitmap

    /** A cover, and whether it is the [full] one or a quick one standing in for it. */
    class Cover(val bitmap: Bitmap, val full: Boolean)

    /**
     * The cover of [entry] as [load] makes it, or when its cover page is a layered image
     * ([PageSource.loadFirstLayer]) and no full cover was made yet, a quick one made the same
     * way from its first layer alone ([Cover.full] false), for [load] to replace. Blocking.
     */
    fun loadFirst(
        context: Context,
        entry: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle = CoverStyle(),
        canceled: () -> Boolean = { false }
    ): Cover? {
        cached(context, entry, width, height, style)?.let { return Cover(it, full = true) }
        coverKeys(context, entry, width, height, style).let { quicks.get(it.shaped) ?: quicks.get(it.plain) }?.let { return Cover(it, full = false) }
        return make(context, entry, charset, width, height, style, canceled, quickFirst = entry.bookmarkPage < 0)
    }

    /** The cover of [entry] ([load]); with [quickFirst], a quick one when its cover page has a first layer ([loadFirst]). */
    private fun make(
        context: Context,
        listed: BookEntry,
        charset: String,
        width: Int,
        height: Int,
        style: CoverStyle,
        canceled: () -> Boolean,
        quickFirst: Boolean
    ): Cover? {
        BookIds.init(context)
        cached(context, listed, width, height, style)?.let { return Cover(it, full = true) }
        // A book not seen before is known by its ID from here on: one made under it at another path is found.
        // A loose image is not read for one: its cover is the image itself.
        val found = if (listed.isDir || listed.type == EntryType.IMAGE || listed.id != null || canceled()) null
        else BookIds.find(listed.file)?.let { listed.copy(id = it) }
        if (found != null) cached(context, found, width, height, style)?.let { return Cover(it, full = true) }
        val entry = found ?: listed
        val page = entry.bookmarkPage
        val detector = detector(context)
        // Arrivals before the detector looks: one during its look makes a cover without text again.
        val arrivedBefore = arrivals(context)
        val keys = coverKeys(context, entry, width, height, style, detector)

        val hidden = Library(context).hiddenAll()
        if (canceled()) return null
        // A book's cover page by book index, which the reader looks up what it told by; none for a folder or a loose image.
        val coverPage = if (entry.isDir || entry.type == EntryType.IMAGE) null else coverPage(hidden, idOf(entry), entry.file.absolutePath)
        val made = generate(entry, charset, context.cacheDir, width, height, hidden, canceled, quickFirst) ?: return null
        val full = made.bitmap
        if (canceled()) {
            full.recycle()
            return null
        }
        // A focus found without the detector holds for this cover in memory only; one found while
        // the detector waits for its model, on disk too, as a waiting cover ([waitingFile]).
        var lasting = true
        var waiting = true
        // Whether the detector's text may shape the cover, or settle a wraparound's direction: kept under its key then.
        var byText = false
        val cover = try {
            CoverArt.cover(
                full, width, height, style, made.innerRatio,
                front = { src ->
                    byText = true
                    frontOf(context, entry, src, made.innerRatio, coverPage, made.full, detector) { w ->
                        lasting = false
                        if (!w) waiting = false
                    }
                },
                // A quick cover from a first layer uses a focus already found; its full cover finds one.
                focus = { src, region ->
                    byText = true
                    focusOf(context, entry, src, region, made.full, detector).also {
                        if (!it.keep) {
                            lasting = false
                            if (it !== CoverFocus.WAITING) waiting = false
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            return null
        } finally {
            full.recycle()
        }
        val k = keys.of(byText)
        val disk = diskFile(context, k)
        if (!made.full) {
            quicks.put(k, cover)
            return Cover(cover, full = false)
        }
        quicks.remove(keys.shaped)
        quicks.remove(keys.plain)
        val bitmap = if (page < 0) cover else {
            val (w, h) = CoverArt.pageBox(width, height)
            val front = pageOf(entry.file, bookPage(context, entry), charset, context.cacheDir, Target(w, h, Fit.INSIDE, linear = style.linear, scaling = style.scaling))
            if (front == null) cover else CoverArt.withPage(cover, front).also { front.recycle() }
        }
        if (lasting) provisional.remove(k) else provisional[k] = arrivedBefore
        memory.put(k, bitmap)
        if (lasting || waiting) runCatching {
            val file = if (lasting) disk else waitingFile(disk)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(LOSSLESS, LOSSLESS_EFFORT, it) }
            if (lasting) waitingFile(disk).delete()
        }
        return Cover(bitmap, full = true)
    }

    /**
     * The cover page of book [entry] whole (of a folder, its row's cover), made to [target] as a page is, for sharing:
     * never cropped, framed or cached. Blocking; null when it has none or cannot be read.
     */
    fun shareCover(context: Context, entry: BookEntry, charset: String, target: Target): Bitmap? = try {
        // A folder's is the one its row shows: its first image, else its first book's.
        val book = if (entry.isDir) findCover(entry.file, 2) else entry.file
        when {
            book == null -> null
            entry.isDir && Formats.typeOf(book.name) == EntryType.IMAGE -> Decoders.decode(book, target)
            else -> {
                val page = coverPage(Library(context).hiddenAll(), if (book == entry.file) idOf(entry) else BookIds.known(book), book.absolutePath)
                coverOf(book, charset, context.cacheDir, target.width, target.height, page, full = target, inner = false)?.bitmap
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** The cover of [entry] as [load] makes it, when it is held in memory; null otherwise. Quick enough for the main thread. */
    fun inMemory(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle = CoverStyle()): Bitmap? =
        coverKeys(context, entry, width, height, style).let { fromMemory(context, it.shaped) ?: fromMemory(context, it.plain) }

    /**
     * The cover of [entry] as [load] makes it, when one was made before: from memory or
     * disk, under either of its keys ([coverKeys]), never generated. Blocking; null when there
     * is none yet. A waiting cover
     * ([waitingFile]) serves while its detector still waits for its model ([CoverTextDetector.ready],
     * which asks for it), in memory as provisional; once the model is there it is deleted.
     */
    fun cached(context: Context, entry: BookEntry, width: Int, height: Int, style: CoverStyle = CoverStyle()): Bitmap? {
        val detector = detector(context)
        val keys = coverKeys(context, entry, width, height, style, detector)
        val k = keys.shaped
        fromMemory(context, k)?.let { return it }
        fromMemory(context, keys.plain)?.let { return it }
        for (key in listOf(k, keys.plain)) {
            val disk = diskFile(context, key)
            // Stored at the box size: the decode is the file as it is.
            if (disk.isFile) return runCatching { Decoders.decode(disk, Target(width, height, Fit.STRETCH)) }.getOrNull()?.also { memory.put(key, it) }
        }
        // Only a cover the detector's text shaped waits for its model.
        val waiting = waitingFile(diskFile(context, k))
        if (!waiting.isFile) return null
        // Before the look: an arrival meanwhile makes it again.
        val arrived = arrivals(context)
        if (detector.ready()) {
            waiting.delete()
            return null
        }
        return runCatching { Decoders.decode(waiting, Target(width, height, Fit.STRETCH)) }.getOrNull()?.also {
            provisional[k] = arrived
            memory.put(k, it)
        }
    }

    /** The book index of [entry]'s bookmarked page, which counts pages as shown ([Library.hiddenPages]). */
    private fun bookPage(context: Context, entry: BookEntry): Int =
        HiddenPages.toBook(entry.bookmarkPage, Library(context).hiddenPages(idOf(entry)))

    /** The ID of book [entry] as listed, else as the index knows it now; null for a folder or while unknown. */
    private fun idOf(entry: BookEntry): BookId? =
        if (entry.isDir) null else entry.id ?: BookIds.known(entry.path, entry.size, entry.lastModified)

    /**
     * What a cover is cached by: its file, box size, style ([CoverStyle.key]), the hidden pages
     * that move it ([hiddenTag]), a bookmark's page in the book, and the book's own file name
     * charset, which may reorder its pages ([NameCharset.overrides]); with the detector of its
     * cover ([detector]) only when that detector looked for its text ([Keys]).
     */
    private fun coverKeys(
        context: Context,
        entry: BookEntry,
        width: Int,
        height: Int,
        style: CoverStyle,
        detector: CoverTextDetector = detector(context)
    ): Keys {
        val page = if (entry.bookmarkPage >= 0) bookPage(context, entry) else -1
        val head = "${key(entry)}|${width}x$height|"
        val tail = imageTag(context, entry) + if (page >= 0) "|p$page" else ""
        return Keys(head + style.key(detector.id) + tail, head + style.key(null) + tail)
    }

    /**
     * A cover's keys ([coverKeys]): [shaped], under its detector, for a cover whose text that
     * detector looked for (a Smart crop, a wraparound: the half one without a barcode shows, and
     * the direction a book's cover settles); [plain], under none, for any other, which a change
     * of detector leaves as it is.
     */
    private class Keys(val shaped: String, val plain: String) {
        fun of(byText: Boolean): String = if (byText) shaped else plain
    }

    /** What the cover image of [entry] is known by, whatever the box: its [key] and [imageTag]. */
    private fun imageKey(context: Context, entry: BookEntry): String = key(entry) + imageTag(context, entry)

    /**
     * What picks the cover image of [entry] besides its file: the hidden pages that move it
     * ([hiddenTag]) and the book's own file name charset, which may reorder its pages
     * ([NameCharset.overrides]). Empty when neither does.
     */
    private fun imageTag(context: Context, entry: BookEntry): String = hiddenTag(entry, Library(context).hiddenAll()) + nameTag(idOf(entry))

    /** The book [id]'s own file name charset, which may reorder its pages ([NameCharset.overrides]); empty without one. */
    private fun nameTag(id: BookId?): String = id?.let { NameCharset.overrides[it] }?.let { "|n$it" }.orEmpty()

    /**
     * The book index of the cover of a book hiding [hidden]: the page it was [given] as cover
     * unless that is hidden, else its first page not hidden.
     */
    fun coverPage(hidden: Set<Int>, given: Int? = null): Int {
        if (given != null && given >= 0 && given !in hidden) return given
        var page = 0
        while (page in hidden) page++
        return page
    }

    /** The book index of the cover of book [id], else of the book last seen at [path], as [hidden] says. */
    fun coverPage(hidden: Library.Hidden, id: BookId?, path: String?): Int =
        if (id != null && (id in hidden.byId || id in hidden.covers)) coverPage(hidden.byId[id].orEmpty(), hidden.covers[id])
        else coverPage(path?.let { hidden.byPath[it] }.orEmpty(), path?.let { hidden.coversByPath[it] })

    /**
     * What [hidden] adds to the key of [entry]'s cover: a book's cover page when it is not the
     * first, and for a folder, which books below it (where last seen) start later. Empty when
     * neither hidden pages nor a cover given move the cover, so such keys stay as they were.
     */
    internal fun hiddenTag(entry: BookEntry, hidden: Library.Hidden): String {
        if (hidden.isEmpty() || (!entry.isDir && entry.type == EntryType.IMAGE)) return ""
        if (!entry.isDir) return coverPage(hidden, idOf(entry), null).takeIf { it > 0 }?.let { "|c$it" }.orEmpty()
        val dir = entry.path
        val moved = (hidden.byPath.keys + hidden.coversByPath.keys)
            .filter { path -> path.length > dir.length && path.startsWith(dir) && isSeparator(path[dir.length]) }
            .map { path -> path to coverPage(hidden.byPath[path].orEmpty(), hidden.coversByPath[path]) }
            .filter { it.second > 0 }
            .sortedBy { it.first }
        if (moved.isEmpty()) return ""
        return "|h" + Hashes.sha256Hex(moved.joinToString("\u0000") { (path, page) -> "$path:$page" }).take(16)
    }

    /** WebP lossless where the platform writes it, PNG before that. */
    private val LOSSLESS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG

    /** For lossless formats the quality is encoder effort: low keeps writing fast. */
    private const val LOSSLESS_EFFORT = 25

    /**
     * Keeps the on-disk cover cache under [maxBytes] by deleting the least recently written
     * files first. Without this the cache only ever grows.
     */
    fun trim(context: Context, maxBytes: Long = MAX_DISK_BYTES) {
        var files = File(context.cacheDir, DISK_DIR).listFiles() ?: return
        // Waiting covers ([waitingFile]) are out of date once the detector's model is in place.
        if (files.any { isWaiting(it) } && runCatching { modelInPlace(context) }.getOrDefault(false)) {
            files = files.filter { !(isWaiting(it) && it.delete()) }.toTypedArray()
        }
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    /** Where covers are kept under [cacheDir]. */
    fun dirIn(cacheDir: File) = File(cacheDir, DISK_DIR)

    /** Drops the covers held in memory and keeps those on disk, as a restart does. For tests. */
    internal fun dropMemory() {
        memory.evictAll()
        provisional.clear()
    }

    fun clear(context: Context) {
        memory.evictAll()
        provisional.clear()
        quicks.evictAll()
        inners.evictAll()
        barcodes.evictAll()
        fronts.evictAll()
        focuses.evictAll()
        synchronized(this) { focusStores.values.forEach { it.clear() } }
        context.cacheDir.listFiles { f -> f.name.startsWith(FOCUS_PREFIX) }?.forEach { it.delete() }
        runCatching { File(context.cacheDir, DISK_DIR).deleteRecursively() }
    }

    // ------------------------------------------------------------------ internals

    /** A cover image as decoded, with its book's typical inner page shape; not [full] when it is a first layer. */
    private class Made(val bitmap: Bitmap, val innerRatio: Double?, val full: Boolean = true)

    /**
     * The cover image of [entry] at full size, up to [THUMB_CAP_PIXELS] (a PDF's
     * rendered just large enough for a [width] x [height] box), with its book's
     * typical inner page shape when the cover is wide enough to be a wraparound.
     * With [quickFirst], the first layer of a layered cover page instead.
     */
    private fun generate(
        entry: BookEntry,
        charset: String,
        cacheDir: File,
        width: Int,
        height: Int,
        hidden: Library.Hidden,
        canceled: () -> Boolean = { false },
        quickFirst: Boolean = false
    ): Made? = try {
        fun pageOf(book: File) = coverPage(hidden, if (book == entry.file) idOf(entry) else BookIds.known(book), book.absolutePath)
        when {
            entry.isDir -> findCover(entry.file, 2)?.takeIf { !canceled() }?.let {
                coverOf(it, charset, cacheDir, width, height, pageOf(it), canceled, quickFirst)
            }
            entry.type == EntryType.IMAGE -> imageCover(entry.file, quickFirst)
            else -> coverOf(entry.file, charset, cacheDir, width, height, pageOf(entry.file), canceled, quickFirst)
        }
    } catch (t: Throwable) {
        null
    }

    /** Image [file] as its own cover; with [quickFirst], the first layer of a layered one stands in. */
    private fun imageCover(file: File, quickFirst: Boolean): Made {
        if (quickFirst && file !is RemoteFile) ImageSource(file).loadFirstLayer(0)?.let { return Made(it, null, full = false) }
        return Made(Decoders.decode(file, Target(capPixels = THUMB_CAP_PIXELS)), null)
    }

    /**
     * The cover of book [file]: its page [page] (book index), or its first page when it has
     * fewer, made to [full]; with [inner], the book's inner page shape when it could be a
     * wraparound.
     */
    private fun coverOf(
        file: File,
        charset: String,
        cacheDir: File,
        width: Int,
        height: Int,
        page: Int = 0,
        canceled: () -> Boolean = { false },
        quickFirst: Boolean = false,
        full: Target = Target(capPixels = THUMB_CAP_PIXELS),
        inner: Boolean = true
    ): Made? {
        if (!file.isFile || !Formats.isOpenable(file.name)) return null
        if (Formats.typeOf(file.name) == EntryType.IMAGE) return if (full.width > 0) Made(Decoders.decode(file, full), null) else imageCover(file, quickFirst)
        val source = runCatching { PageSources.openCover(file, charset, cacheDir, page) }.getOrNull() ?: return null
        var layered = false
        val cover = try {
            if (canceled()) return null
            val index = if (page < source.pageCount) page else 0
            val first = if (quickFirst && source.pageCount > 0) runCatching { source.loadFirstLayer(index) }.getOrNull() else null
            layered = first != null
            val target = if (!source.isVector) full else {
                source.pageSize(index)?.let { PdfSource.coverTarget(it[0], it[1], width, height, THUMB_CAP_PIXELS) } ?: full
            }
            first ?: if (source.pageCount <= 0) null else source.loadPage(index, target)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { source.close() }
        } ?: return null
        if (canceled()) {
            cover.recycle()
            return null
        }
        val ratio = if (inner && Wraparound.isCandidate(cover.width, cover.height)) innerRatioOf(file, charset, cacheDir) else null
        return Made(cover, ratio, full = !layered)
    }

    /**
     * The typical inner page shape of the book [file] ([Wraparound.innerRatio]), found once a
     * session; null when it has none or cannot be read.
     */
    private fun innerRatioOf(file: File, charset: String, cacheDir: File): Double? {
        val k = key(file)
        inners.get(k)?.let { return it.takeIf { !it.isNaN() } }
        val source = runCatching { PageSources.open(file, charset, cacheDir, copyNested = false) }.getOrNull() ?: return null
        val found = try {
            BookDirection.innerRatio(source)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { source.close() }
        }
        inners.put(k, found ?: Double.NaN)
        return found
    }

    /** Page [index] of the book [file] (its last page if it has fewer) made to [target]; null when it cannot be read. */
    private fun pageOf(file: File, index: Int, charset: String, cacheDir: File, target: Target): Bitmap? {
        if (!file.isFile) return null
        val source = runCatching { PageSources.open(file, charset, cacheDir) }.getOrNull() ?: return null
        return try {
            if (source.pageCount <= 0) null else source.loadPage(index.coerceIn(0, source.pageCount - 1), target)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { source.close() }
        }
    }

    private fun findCover(dir: File, depth: Int): File? {
        val files = dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: return null
        files.firstOrNull { it.isFile && Formats.typeOf(it.name) == EntryType.IMAGE }?.let { return it }
        files.filter { it.isFile && Formats.isOpenable(it.name) }
            .minWithOrNull(compareBy(NaturalOrder) { it.name })
            ?.let { return it }
        if (depth <= 0) return null
        val subs = files.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .take(8)
        for (sub in subs) findCover(sub, depth - 1)?.let { return it }
        return null
    }

    private fun diskFile(context: Context, key: String): File =
        File(File(context.cacheDir, DISK_DIR), Hashes.sha256Hex(key) + if (LOSSLESS == Bitmap.CompressFormat.PNG) ".png" else ".webp")

    private const val WAITING = "-waiting"

    /**
     * Where the cover kept in [disk] is kept while made as its detector waited for its model
     * ([CoverFocus.WAITING]): cropped as Top does, made again once the model is there.
     */
    private fun waitingFile(disk: File): File = File(disk.parentFile, disk.nameWithoutExtension + WAITING + "." + disk.extension)

    private fun isWaiting(f: File): Boolean = f.nameWithoutExtension.endsWith(WAITING)

    /** True once the cover text detector's model is in place, so waiting covers are out of date ([trim]). Tests stand in their own. */
    @Volatile
    internal var modelInPlace: (Context) -> Boolean = { TextDetector.downloaded(ModelStore.of(it)) != null }
}
