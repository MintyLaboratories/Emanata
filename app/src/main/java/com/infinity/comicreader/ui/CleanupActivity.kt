package com.infinity.comicreader.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.compact.CompactWorkers
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibrarySnapshot
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SimilarFinder
import com.infinity.comicreader.data.SimilarStore
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.data.Thrift
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.net.Metered
import com.infinity.comicreader.net.RemoteFile
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.BookPrints
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Duplicates
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import com.infinity.comicreader.util.Timing
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Housekeeping: finds duplicate files or lists the largest files below a folder,
 * and lets you delete them. Bit-identical copies (same SHA-256) show as one cover,
 * which a two-finger spread peeks into, with every place a copy is. Books probably
 * alike in other files ([SimilarFinder]) follow side by side; both are found with progress
 * and a way to stop, and none of them is ever selected for you. In a share folder
 * ([Prefs.similarShares]) everything is read only and nothing can be deleted.
 */
class CleanupActivity : AppCompatActivity() {

    private sealed class Row {
        /** A header; [similar] marks the similar-books one. */
        class Section(val title: String, val similar: Boolean = false) : Row()

        /** One file of the largest-files list. */
        class Single(val file: File, val size: Long) : Row()

        /** Copies of one content, [size] bytes each, shown as [entry]'s cover; [places] names each copy's folder. */
        class Group(val size: Long, val files: MutableList<File>, var entry: BookEntry, val places: Map<File, String>) : Row()

        /** Books probably alike, side by side. */
        class Similar(val books: MutableList<SimilarBook>) : Row()
    }

    /** One content of a [Row.Similar]: its bit-identical [files], [size] bytes each, shown as [entry]; [info] is what was read of it. */
    private class SimilarBook(var entry: BookEntry, val files: MutableList<File>, val size: Long, val info: SimilarStore.Entry, val places: Map<File, String>)

    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var selectButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
    private lateinit var similarBar: View
    private lateinit var similarText: TextView
    private lateinit var similarProgress: LinearProgressIndicator
    private lateinit var similarStop: MaterialButton
    private lateinit var similarCompare: MaterialButton
    private lateinit var pinch: PinchPeek

    private var mode = MODE_DUPES
    private val rows = ArrayList<Row>()
    private val selected = LinkedHashSet<String>()
    private lateinit var adapter: CleanAdapter

    /** True while files are read for either section: the bar shows progress. */
    private var comparing = false

    /** Set by Stop: the search starts no more reads. */
    @Volatile
    private var stopped = false

    /** The bar's progress as the search last said: its text, then done of all. */
    @Volatile
    private var compared = Triple(R.string.similar_progress, 0, 0)

    /** What the bar says of the exact-copy check once the search is over; null when it read everything. */
    private var dupesNote: String? = null

    /** Share files the exact-copy check left unread on a metered connection. */
    private val meteredFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True in a share folder: nothing is selected or deleted. */
    private var readOnly = false

    /** Every file deleted here, so a search under way when they went lists none of them. */
    private val deleted = HashSet<String>()

    /** True while a progress update waits on the main thread, so updates from many reads coalesce. */
    private val progressPosted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        prefs = Prefs(this)
        setContentView(R.layout.activity_cleanup)

        mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_LARGE) MODE_LARGE else MODE_DUPES
        val root = Remotes.file(intent.getStringExtra(EXTRA_ROOT) ?: android.os.Environment.getExternalStorageDirectory().absolutePath)
        readOnly = root is RemoteFile

        val toolbar = findViewById<MaterialToolbar>(R.id.cleanup_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(if (mode == MODE_DUPES) R.string.menu_dupes else R.string.menu_large)
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.cleanup_list)
        progress = findViewById(R.id.cleanup_progress)
        empty = findViewById(R.id.cleanup_empty)
        selectButton = findViewById(R.id.cleanup_select)
        deleteButton = findViewById(R.id.cleanup_delete)
        similarBar = findViewById(R.id.similar_bar)
        similarText = findViewById(R.id.similar_text)
        similarProgress = findViewById(R.id.similar_progress)
        findViewById<TextView>(R.id.cleanup_summary).text =
            if (readOnly) Crumbs.of(root.absolutePath, StorageRoots.list(this)).joinToString(" / ") { it.label } else root.absolutePath
        similarStop = findViewById(R.id.similar_stop)
        similarCompare = findViewById(R.id.similar_compare)
        similarStop.setOnClickListener {
            stopped = true
            it.isEnabled = false
        }

        adapter = CleanAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        pinch = PinchPeek(this, list, { prefs.pinchPeek }, ::peekTarget) { entry, page ->
            startActivity(
                Intent(this, ViewerActivity::class.java)
                    .putExtra(ViewerActivity.EXTRA_PATH, entry.file.absolutePath)
                    .putExtra(ViewerActivity.EXTRA_PAGE, page)
            )
        }

        selectButton.visibility = if (mode == MODE_DUPES && !readOnly) View.VISIBLE else View.GONE
        selectButton.setOnClickListener { selectExtras() }
        deleteButton.visibility = if (readOnly) View.GONE else View.VISIBLE
        deleteButton.setOnClickListener { confirmDelete() }
        updateDeleteButton()

        scan(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pinch.overlay.isShowing) {
            pinch.overlay.close()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopped = true
        pinch.overlay.hide()
        if (isFinishing) SimilarStore.of(applicationContext.cacheDir).release()
    }

    /** The book under list point ([x], [y]) and its cover's view; null off a book or for a file no reader opens. */
    private fun peekTarget(x: Float, y: Float): Pair<BookEntry, View>? {
        val child = list.findChildViewUnder(x, y) ?: return null
        val (entry, box) = when (val row = rows.getOrNull(list.getChildAdapterPosition(child))) {
            is Row.Group -> row.entry to child.findViewById<View>(R.id.box)
            is Row.Similar -> {
                val columns = child.findViewById<LinearLayout>(R.id.columns)
                val at = Rect()
                val i = (0 until columns.childCount).firstOrNull { i ->
                    val column = columns.getChildAt(i)
                    column.getDrawingRect(at)
                    list.offsetDescendantRectToMyCoords(column, at)
                    at.contains(x.toInt(), y.toInt())
                } ?: return null
                val book = row.books.getOrNull(i) ?: return null
                book.entry to columns.getChildAt(i).findViewById<View>(R.id.box)
            }
            else -> return null
        }
        if (!Formats.isOpenable(entry.name) || !entry.file.isFile) return null
        return entry to box
    }

    // ------------------------------------------------------------------ scanning

    private fun scan(root: File) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            if (mode == MODE_LARGE) {
                show(withContext(AppDispatchers.io) { findLargest(root) { isActive } })
                return@launch
            }
            val files = withContext(AppDispatchers.io) { walk(root) { isActive }.toList() }
            comparing = true
            showSimilarBar(getString(R.string.dupes_checking), running = true)
            val found = withContext(AppDispatchers.io) { findDuplicates(files) { isActive && !stopped } }
            dupesNote = when {
                meteredFiles.isNotEmpty() -> resources.getQuantityString(R.plurals.dupes_metered, meteredFiles.size, meteredFiles.size)
                stopped -> getString(R.string.dupes_stopped)
                else -> null
            }
            show(found)
            if (!findSimilar(files)) endBar(emptyList())
        }
    }

    /** Lists [found] in place of anything listed. */
    private fun show(found: List<Row>) {
        rows.clear()
        rows.addAll(found)
        progress.visibility = View.GONE
        empty.setText(R.string.cleanup_nothing)
        updateEmpty()
        adapter.notifyDataSetChanged()
    }

    /** Says nothing was found once the search is over and found nothing, or all of it was deleted; not while books wait to be compared. */
    private fun updateEmpty() {
        val busy = comparing || similarBar.visibility == View.VISIBLE || progress.visibility == View.VISIBLE
        empty.visibility = if (rows.isEmpty() && !busy) View.VISIBLE else View.GONE
    }

    /**
     * Comic files below [root], without hidden folders or `Android/`. Symbolic links are
     * skipped, so a link loop cannot hang the scan and a link is never offered for deletion.
     */
    private fun walk(root: File, alive: () -> Boolean): Sequence<File> =
        root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { !it.name.startsWith(".") && it.name != "Android" && !isLink(it) }
            .takeWhile { alive() }
            .filter { it.isFile && Formats.typeOf(it.name) != EntryType.OTHER && !isLink(it) }

    /** True for a symbolic link, on the device or on a share (as its listing says). */
    private fun isLink(f: File): Boolean = if (f is RemoteFile) f.stat()?.isLink == true else Files.isSymbolicLink(f.toPath())

    /**
     * [f]'s bytes for [Duplicates]: a share's file read in place, each piece asked for at once.
     * Fails for a share file that comes only whole ([Remotes.seekable]), and for one while the
     * connection is metered ([Metered], asked before each piece), which [meteredFiles] counts.
     */
    private fun bytesOf(f: File): Duplicates.Bytes {
        if (f !is RemoteFile) return Duplicates.local(f)
        val unmetered = {
            if (Metered.skipsOptional()) {
                meteredFiles += f.absolutePath
                throw IOException("Metered: ${f.name}")
            }
        }
        unmetered()
        if (!Remotes.seekable(f.path)) throw IOException("Comes only whole: ${f.name}")
        val input = Remotes.open(f.path)
        return object : Duplicates.Bytes {
            override val length: Long get() = input.length
            override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
                unmetered()
                return input.readFully(position, buffer, offset, count)
            }
            override fun close() = input.close()
        }
    }

    private fun findLargest(root: File, alive: () -> Boolean): List<Row> =
        walk(root, alive).map { it to it.length() }
            .sortedByDescending { it.second }
            .take(LARGEST_COUNT)
            .map { Row.Single(it.first, it.second) }
            .toList()

    /** Where [f] is, as the crumbs of its folder read. */
    private fun placeOf(f: File, roots: List<StorageRoot>): String =
        Crumbs.of(f.parent.orEmpty(), roots).joinToString(" / ") { it.label }.ifEmpty { f.parent.orEmpty() }

    /** The bit-identical groups among [files] under their header, found while [alive]; empty when there are none. */
    private fun findDuplicates(files: List<File>, alive: () -> Boolean): List<Row> {
        val groups = Duplicates.find(files.asSequence(), ::bytesOf, alive) { done, total -> onProgress(R.string.dupes_progress, done, total) }
        if (groups.isEmpty()) return emptyList()
        val snap = LibrarySnapshot.load(library, prefs)
        val roots = StorageRoots.list(this)
        val out = ArrayList<Row>()
        out.add(Row.Section(getString(R.string.dupes_identical)))
        for (g in groups) {
            val places = g.files.associateWith { placeOf(it, roots) }
            out.add(Row.Group(g.size, g.files.toMutableList(), snap.entry(g.files.first(), prefs.showHidden), places))
        }
        return out
    }

    /**
     * Compares the books among [files] whose pages it can read ([SimilarFinder]) and lists
     * the groups probably alike below anything listed, under their header. Books with nothing
     * kept of them are hashed first, unasked, unless battery saver pauses scans
     * ([Thrift.pausedScans]): then Compare reads them. False when there are not two books to compare.
     */
    private suspend fun findSimilar(files: List<File>): Boolean {
        val books = files.filter { Formats.typeOf(it.name).let { t -> t == EntryType.ARCHIVE || t == EntryType.PDF } }
            .sortedWith(compareBy(NaturalOrder) { it.absolutePath })
        if (books.size < 2) return false
        val paused = Thrift.of(Thrift.saving(this), prefs).pausedScans
        compareSimilar(books, readNew = !paused)
        return true
    }

    /**
     * Compares [books] ([SimilarFinder.find]), hashing those with nothing kept when [readNew]
     * (device books by up to [SimilarFinder.WORKERS] threads as heat and battery allow, share
     * books one at a time beside them), and lists what it found in place of the similar books
     * listed. Shows books hashed of those to hash meanwhile; Stop, or leaving, compares only
     * what was read by then. On a metered connection ([Metered]) share books are not read, not
     * even for their IDs, and the bar says so. Books left uncompared otherwise are counted on
     * the bar, with Compare to read them.
     */
    private suspend fun compareSimilar(books: List<File>, readNew: Boolean) {
        comparing = true
        showSimilarBar(getString(R.string.similar_listing), running = true)
        updateEmpty()
        val context = currentCoroutineContext()
        val cacheDir = applicationContext.cacheDir
        val charset = prefs.charset
        val workers = SimilarFinder.WORKERS
        val left = books.filter { it.absolutePath !in deleted }
        val metered = left.any { it is RemoteFile } && withContext(AppDispatchers.io) { Metered.skipsOptional() }
        // A share book's ID not known yet is read from it, unless metered or stopped: then it is counted as not compared.
        val unknown = AtomicInteger()
        val idOf = { f: File ->
            if (f is RemoteFile && (metered || stopped)) BookIds.known(f).also { if (it == null) unknown.incrementAndGet() } else BookIds.find(f)
        }
        val finder = SimilarFinder(
            SimilarStore.of(cacheDir), idOf, { f, canceled -> BookPrints.read(f, charset, cacheDir, canceled) },
            workers, CompactWorkers.limit(this, workers), remote = { it is RemoteFile }
        )
        val result = withContext(AppDispatchers.io) {
            // Stop holds back reads only: every book is listed, so what is left is counted.
            val contents = finder.contents(left) { context.isActive }
            Timing.span("similar books ${contents.byId.size}") {
                finder.find(contents, { context.isActive && !stopped }, { done, total -> onProgress(R.string.similar_progress, done, total) }, readNew, readShares = !metered)
            }
        }
        val found = withContext<List<Row>>(AppDispatchers.io) {
            if (result.groups.isEmpty()) return@withContext emptyList()
            val snap = LibrarySnapshot.load(library, prefs)
            val roots = StorageRoots.list(this@CleanupActivity)
            val title = if (result.compared < result.total + unknown.get()) {
                getString(R.string.dupes_similar_partial, result.compared, result.total + unknown.get())
            } else {
                getString(R.string.dupes_similar)
            }
            listOf(Row.Section(title, similar = true)) + result.groups.map { group ->
                Row.Similar(
                    group.mapTo(ArrayList()) { b ->
                        SimilarBook(
                            snap.entry(b.files.first(), prefs.showHidden), b.files.toMutableList(), b.files.first().length(), b.entry,
                            b.files.associateWith { placeOf(it, roots) }
                        )
                    }
                )
            }
        }
        rows.removeAll { it is Row.Similar || (it is Row.Section && it.similar) }
        rows.addAll(found)
        forget(deleted)
        val skipped = if (metered) result.unreadShares + unknown.get() else 0
        val waiting = result.unread + unknown.get() - skipped
        if (!context.isActive) {
            comparing = false
            similarBar.visibility = View.GONE
        } else {
            val notes = listOfNotNull(
                getString(R.string.similar_unread, result.unread + unknown.get(), result.total + unknown.get()).takeIf { waiting > 0 },
                getString(R.string.similar_metered, skipped).takeIf { skipped > 0 }
            )
            val compare = { ->
                stopped = false
                lifecycleScope.launch { compareSimilar(books, readNew = true) }
                Unit
            }
            endBar(notes, compare.takeIf { waiting > 0 })
        }
        adapter.notifyDataSetChanged()
        updateEmpty()
        updateDeleteButton()
    }

    /** Shows the similar-books bar saying [text]: with progress and Stop while [running], else with Compare. */
    private fun showSimilarBar(text: String, running: Boolean) {
        similarBar.visibility = View.VISIBLE
        similarText.text = text
        similarProgress.visibility = if (running) View.VISIBLE else View.GONE
        similarProgress.isIndeterminate = true
        similarStop.visibility = if (running) View.VISIBLE else View.GONE
        similarStop.isEnabled = !stopped
        similarCompare.visibility = if (running) View.GONE else View.VISIBLE
    }

    /**
     * Ends the search's bar: says [dupesNote] and [notes], one a line, with Compare doing
     * [compare] when given; hidden with nothing to say.
     */
    private fun endBar(notes: List<String>, compare: (() -> Unit)? = null) {
        comparing = false
        val lines = listOfNotNull(dupesNote) + notes
        if (lines.isEmpty()) {
            similarBar.visibility = View.GONE
            return
        }
        showSimilarBar(lines.joinToString("\n"), running = false)
        similarCompare.visibility = if (compare == null) View.GONE else View.VISIBLE
        similarCompare.setOnClickListener { compare?.invoke() }
    }

    /** Hears [done] of [total] read, said as [text] reads, from the reading threads. */
    private fun onProgress(text: Int, done: Int, total: Int) {
        compared = Triple(text, done, total)
        if (total > 0 && progressPosted.compareAndSet(false, true)) similarBar.post {
            progressPosted.set(false)
            if (!comparing) return@post
            val (res, d, t) = compared
            similarText.text = getString(res, d, t)
            similarProgress.isIndeterminate = false
            similarProgress.max = t.coerceAtLeast(1)
            similarProgress.setProgressCompat(d, false)
        }
    }

    // ------------------------------------------------------------------ selection / delete

    /** Ticks every copy except the first in each bit-identical group, so one copy of each is kept; similar books are left alone. */
    private fun selectExtras() {
        selected.clear()
        if (readOnly) return
        for (row in rows) if (row is Row.Group) row.files.drop(1).forEach { selected.add(it.absolutePath) }
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    /** Ticks [file] or clears it, and shows it so in every other row listing it. */
    private fun toggle(file: File, from: Row) {
        val path = file.absolutePath
        if (!selected.add(path)) selected.remove(path)
        for ((i, row) in rows.withIndex()) if (row !== from && path in sizesOf(row)) adapter.notifyItemChanged(i)
        updateDeleteButton()
    }

    /** The files [row] lists, with their sizes. */
    private fun sizesOf(row: Row): Map<String, Long> = when (row) {
        is Row.Single -> mapOf(row.file.absolutePath to row.size)
        is Row.Group -> row.files.associate { it.absolutePath to row.size }
        is Row.Similar -> row.books.flatMap { b -> b.files.map { it.absolutePath to b.size } }.toMap()
        is Row.Section -> emptyMap()
    }

    /** Every file listed, with its size. */
    private fun sizes(): Map<String, Long> = buildMap { for (row in rows) putAll(sizesOf(row)) }

    private fun updateDeleteButton() {
        val sizes = sizes()
        val bytes = selected.sumOf { sizes[it] ?: 0L }
        deleteButton.isEnabled = selected.isNotEmpty()
        deleteButton.text = if (selected.isEmpty()) {
            getString(R.string.delete_selected)
        } else {
            getString(R.string.delete_selected_n, selected.size, Formatter.formatShortFileSize(this, bytes))
        }
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) return
        val paths = selected.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(resources.getQuantityString(R.plurals.delete_selected_msg, paths.size, paths.size))
            .setPositiveButton(R.string.delete) { _, _ -> deletePaths(paths) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deletePaths(paths: List<String>) {
        lifecycleScope.launch {
            val app = applicationContext
            val deleted = withContext(AppDispatchers.io) {
                paths.filter { File(it).delete() }.toSet().onEach { BookCaches.deleted(app, library, it) }
            }
            if (deleted.size < paths.size) {
                Toast.makeText(this@CleanupActivity, R.string.op_failed, Toast.LENGTH_SHORT).show()
            }
            selected.removeAll(deleted)
            this@CleanupActivity.deleted.addAll(deleted)
            forget(deleted)
            updateEmpty()
            adapter.notifyDataSetChanged()
            updateDeleteButton()
        }
    }

    /**
     * Drops the [deleted] files, bit-identical groups left with fewer than two copies,
     * similar groups left with fewer than two books, and headers left with nothing below.
     */
    private fun forget(deleted: Set<String>) {
        rows.removeAll { it is Row.Single && it.file.absolutePath in deleted }
        for (row in rows) when (row) {
            is Row.Group -> {
                row.files.removeAll { it.absolutePath in deleted }
                // The cover and title move to a copy that is left.
                val kept = row.files.firstOrNull()
                if (kept != null && row.entry.path in deleted) row.entry = row.entry.copy(file = kept, name = kept.name)
            }
            is Row.Similar -> {
                for (book in row.books) {
                    book.files.removeAll { it.absolutePath in deleted }
                    val kept = book.files.firstOrNull()
                    if (kept != null && book.entry.path in deleted) book.entry = book.entry.copy(file = kept, name = kept.name)
                }
                row.books.removeAll { it.files.isEmpty() }
            }
            else -> Unit
        }
        val left = rows.filter {
            when (it) {
                is Row.Group -> it.files.size >= 2
                is Row.Similar -> it.books.size >= 2
                else -> true
            }
        }
        rows.clear()
        for ((i, row) in left.withIndex()) {
            val next = left.getOrNull(i + 1)
            if (row is Row.Section && (next == null || next is Row.Section)) continue
            rows.add(row)
        }
    }

    // ------------------------------------------------------------------ adapter

    private inner class CleanAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Section -> TYPE_HEADER
            is Row.Single -> TYPE_FILE
            is Row.Group -> TYPE_GROUP
            is Row.Similar -> TYPE_SIMILAR
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = when (viewType) {
                TYPE_HEADER -> R.layout.item_clean_header
                TYPE_GROUP -> R.layout.item_dupe_group
                TYPE_SIMILAR -> R.layout.item_similar_group
                else -> R.layout.item_clean
            }
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is Row.Section -> v.findViewById<TextView>(R.id.title).text = row.title
                is Row.Single -> bindFile(v, row.file, row.file.name, row.file.parent.orEmpty(), row.size, row)
                is Row.Group -> bindGroup(v, row)
                is Row.Similar -> bindSimilar(v, row)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder.itemView.getTag(R.id.thumb) as? Job)?.cancel()
            holder.itemView.findViewById<LinearLayout>(R.id.columns)?.let(::cancelCovers)
        }

        /** A selectable file row of [row]: [title] above [subtitle], with [size] unless null. */
        private fun bindFile(v: View, f: File, title: String, subtitle: String, size: Long?, row: Row) {
            v.findViewById<TextView>(R.id.title).text = title
            v.findViewById<TextView>(R.id.subtitle).text = subtitle
            v.findViewById<TextView>(R.id.size).apply {
                visibility = if (size == null) View.GONE else View.VISIBLE
                if (size != null) text = Formatter.formatShortFileSize(this@CleanupActivity, size)
            }
            val check = v.findViewById<CheckBox>(R.id.check)
            check.isChecked = f.absolutePath in selected
            check.visibility = if (readOnly) View.GONE else View.VISIBLE
            if (readOnly) {
                v.setOnClickListener(null)
                v.isClickable = false
                return
            }
            v.setOnClickListener {
                toggle(f, row)
                check.isChecked = f.absolutePath in selected
            }
        }

        /** A row per file of [files] in [places], titled by where it is ([placeOf]), named only where the names differ. */
        private fun bindPlaces(places: LinearLayout, files: List<File>, placeOf: Map<File, String>, row: Row) {
            places.removeAllViews()
            for (f in files) {
                val place = LayoutInflater.from(places.context).inflate(R.layout.item_clean, places, false)
                val name = if (files.all { it.name == f.name }) "" else f.name
                bindFile(place, f, placeOf[f].orEmpty(), name, null, row)
                place.findViewById<TextView>(R.id.subtitle).visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
                places.addView(place)
            }
        }

        private fun bindGroup(v: View, row: Row.Group) {
            v.findViewById<TextView>(R.id.title).text = row.entry.name
            v.findViewById<TextView>(R.id.subtitle).text =
                resources.getQuantityString(R.plurals.dupe_group, row.files.size, row.files.size, Formatter.formatShortFileSize(this@CleanupActivity, row.size))
            bindPlaces(v.findViewById(R.id.locations), row.files, row.places, row)
            bindCover(v, row.entry)
        }

        /** Each book of [row] in a column of its own: an equal share of the list's width, no narrower than [R.dimen.similar_column_min_w]. */
        private fun bindSimilar(v: View, row: Row.Similar) {
            val columns = v.findViewById<LinearLayout>(R.id.columns)
            cancelCovers(columns)
            columns.removeAllViews()
            val side = resources.getDimensionPixelSize(R.dimen.list_side_padding)
            val room = (list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - 2 * side
            val width = maxOf(resources.getDimensionPixelSize(R.dimen.similar_column_min_w), room / row.books.size)
            for (book in row.books) {
                val column = LayoutInflater.from(v.context).inflate(R.layout.item_similar_book, columns, false)
                column.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                column.findViewById<TextView>(R.id.title).text = book.entry.name
                val info = book.info
                column.findViewById<TextView>(R.id.format).text =
                    listOf(info.container, info.image).filter { it.isNotEmpty() }.joinToString(" · ") { it.uppercase(Locale.ROOT) }
                column.findViewById<TextView>(R.id.pages).text = resources.getQuantityString(
                    R.plurals.similar_pages, info.print.pages, info.print.pages, Formatter.formatShortFileSize(this@CleanupActivity, book.size)
                )
                column.findViewById<TextView>(R.id.dimensions).apply {
                    visibility = if (info.width > 0 && info.height > 0) View.VISIBLE else View.GONE
                    text = getString(if (info.print.vector) R.string.similar_size_pt else R.string.similar_size, info.width, info.height)
                }
                val places = column.findViewById<LinearLayout>(R.id.locations)
                bindPlaces(places, book.files, book.places, row)
                // A narrow column wraps a place rather than cut its middle (which only one line can).
                for (i in 0 until places.childCount) places.getChildAt(i).findViewById<TextView>(R.id.title).apply {
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 3
                }
                columns.addView(column)
                bindCover(column, book.entry)
            }
        }

        /** Stops loading the covers of [columns]. */
        private fun cancelCovers(columns: LinearLayout) {
            for (i in 0 until columns.childCount) (columns.getChildAt(i).getTag(R.id.thumb) as? Job)?.cancel()
        }

        /** Loads [entry]'s cover into the row at its view's size. */
        private fun bindCover(v: View, entry: BookEntry) {
            val thumb = v.findViewById<ImageView>(R.id.thumb)
            val placeholder = v.findViewById<View>(R.id.placeholder)
            (v.getTag(R.id.thumb) as? Job)?.cancel()
            thumb.setImageDrawable(null)
            placeholder.visibility = View.VISIBLE
            if (!prefs.thumbnails) return
            val w = resources.getDimensionPixelSize(R.dimen.dupe_cover_w)
            val h = resources.getDimensionPixelSize(R.dimen.dupe_cover_h)
            val charset = prefs.charset
            val style = prefs.coverStyle
            v.setTag(R.id.thumb, lifecycleScope.launch {
                val bitmap = withContext(AppDispatchers.io) {
                    runCatching { Thumbs.load(applicationContext, entry, charset, w, h, style) }.getOrNull()
                }
                if (bitmap != null) {
                    thumb.setImageBitmap(bitmap)
                    placeholder.visibility = View.GONE
                }
            })
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ROOT = "root"
        const val MODE_DUPES = "dupes"
        const val MODE_LARGE = "large"

        private const val TYPE_FILE = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_GROUP = 2
        private const val TYPE_SIMILAR = 3
        private const val MAX_DEPTH = 12
        private const val LARGEST_COUNT = 100
    }
}
