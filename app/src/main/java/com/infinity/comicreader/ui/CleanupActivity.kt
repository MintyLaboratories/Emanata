package com.infinity.comicreader.ui

import android.content.Intent
import android.os.Bundle
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
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.BookEntry
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.EntryType
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibrarySnapshot
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Duplicates
import com.infinity.comicreader.util.Formats
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Housekeeping: finds duplicate files or lists the largest files below a folder,
 * and lets you delete them. Bit-identical copies (same SHA-256) show as one cover,
 * which a two-finger spread peeks into, with every place a copy is.
 */
class CleanupActivity : AppCompatActivity() {

    private sealed class Row {
        class Section(val title: String) : Row()

        /** One file of the largest-files list. */
        class Single(val file: File, val size: Long) : Row()

        /** Copies of one content, [size] bytes each, shown as [entry]'s cover; [places] names each copy's folder. */
        class Group(val size: Long, val files: MutableList<File>, var entry: BookEntry, val places: Map<File, String>) : Row()
    }

    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var list: RecyclerView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var selectButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
    private lateinit var pinch: PinchPeek

    private var mode = MODE_DUPES
    private val rows = ArrayList<Row>()
    private val selected = LinkedHashSet<String>()
    private lateinit var adapter: CleanAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        prefs = Prefs(this)
        setContentView(R.layout.activity_cleanup)

        mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_LARGE) MODE_LARGE else MODE_DUPES
        val root = File(
            intent.getStringExtra(EXTRA_ROOT) ?: android.os.Environment.getExternalStorageDirectory().absolutePath
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.cleanup_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(if (mode == MODE_DUPES) R.string.menu_dupes else R.string.menu_large)
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.cleanup_list)
        progress = findViewById(R.id.cleanup_progress)
        empty = findViewById(R.id.cleanup_empty)
        selectButton = findViewById(R.id.cleanup_select)
        deleteButton = findViewById(R.id.cleanup_delete)
        findViewById<TextView>(R.id.cleanup_summary).text = root.absolutePath

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

        selectButton.visibility = if (mode == MODE_DUPES) View.VISIBLE else View.GONE
        selectButton.setOnClickListener { selectExtras() }
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
        pinch.overlay.hide()
    }

    /** The copies under list point ([x], [y]) as a book, and its cover's view; null off a group or for a file no reader opens. */
    private fun peekTarget(x: Float, y: Float): Pair<BookEntry, View>? {
        val child = list.findChildViewUnder(x, y) ?: return null
        val group = rows.getOrNull(list.getChildAdapterPosition(child)) as? Row.Group ?: return null
        if (!Formats.isOpenable(group.entry.name) || !group.entry.file.isFile) return null
        return group.entry to child.findViewById(R.id.box)
    }

    // ------------------------------------------------------------------ scanning

    private fun scan(root: File) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(AppDispatchers.io) {
                if (mode == MODE_DUPES) findDuplicates(root) { isActive } else findLargest(root) { isActive }
            }
            rows.clear()
            rows.addAll(result)
            progress.visibility = View.GONE
            empty.setText(R.string.cleanup_nothing)
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    /**
     * Comic files below [root], without hidden folders or `Android/`. Symbolic links are
     * skipped, so a link loop cannot hang the scan and a link is never offered for deletion.
     */
    private fun walk(root: File, alive: () -> Boolean): Sequence<File> =
        root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { !it.name.startsWith(".") && it.name != "Android" && !Files.isSymbolicLink(it.toPath()) }
            .takeWhile { alive() }
            .filter { it.isFile && Formats.typeOf(it.name) != EntryType.OTHER && !Files.isSymbolicLink(it.toPath()) }

    private fun findLargest(root: File, alive: () -> Boolean): List<Row> =
        walk(root, alive).map { it to it.length() }
            .sortedByDescending { it.second }
            .take(LARGEST_COUNT)
            .map { Row.Single(it.first, it.second) }
            .toList()

    /** The bit-identical groups below [root] under their header; empty when there are none. */
    private fun findDuplicates(root: File, alive: () -> Boolean): List<Row> {
        val groups = Duplicates.find(walk(root, alive), alive)
        if (groups.isEmpty()) return emptyList()
        val snap = LibrarySnapshot.load(library, prefs)
        val roots = StorageRoots.list(this)
        val out = ArrayList<Row>()
        out.add(Row.Section(getString(R.string.dupes_identical)))
        for (g in groups) {
            val places = g.files.associateWith { f ->
                Crumbs.of(f.parent.orEmpty(), roots).joinToString(" / ") { it.label }.ifEmpty { f.parent.orEmpty() }
            }
            out.add(Row.Group(g.size, g.files.toMutableList(), snap.entry(g.files.first(), prefs.showHidden), places))
        }
        return out
    }

    // ------------------------------------------------------------------ selection / delete

    /** Ticks every copy except the first in each group, so one copy of each is kept. */
    private fun selectExtras() {
        selected.clear()
        for (row in rows) if (row is Row.Group) row.files.drop(1).forEach { selected.add(it.absolutePath) }
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun toggle(file: File) {
        val path = file.absolutePath
        if (!selected.add(path)) selected.remove(path)
        updateDeleteButton()
    }

    /** Every file listed, with its size. */
    private fun sizes(): Map<String, Long> = buildMap {
        for (row in rows) when (row) {
            is Row.Single -> put(row.file.absolutePath, row.size)
            is Row.Group -> row.files.forEach { put(it.absolutePath, row.size) }
            is Row.Section -> Unit
        }
    }

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
            forget(deleted)
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            updateDeleteButton()
        }
    }

    /** Drops the [deleted] files, groups left with fewer than two copies, and a header left with nothing below it. */
    private fun forget(deleted: Set<String>) {
        rows.removeAll { it is Row.Single && it.file.absolutePath in deleted }
        for (row in rows) if (row is Row.Group) {
            row.files.removeAll { it.absolutePath in deleted }
            // The cover and title move to a copy that is left.
            val kept = row.files.firstOrNull()
            if (kept != null && row.entry.path in deleted) row.entry = row.entry.copy(file = kept, name = kept.name)
        }
        val left = rows.filter { it !is Row.Group || it.files.size >= 2 }
        rows.clear()
        if (left.any { it !is Row.Section }) rows.addAll(left)
    }

    // ------------------------------------------------------------------ adapter

    private inner class CleanAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Section -> TYPE_HEADER
            is Row.Single -> TYPE_FILE
            is Row.Group -> TYPE_GROUP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = when (viewType) {
                TYPE_HEADER -> R.layout.item_clean_header
                TYPE_GROUP -> R.layout.item_dupe_group
                else -> R.layout.item_clean
            }
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            when (val row = rows[position]) {
                is Row.Section -> v.findViewById<TextView>(R.id.title).text = row.title
                is Row.Single -> bindFile(v, row.file, row.file.name, row.file.parent.orEmpty(), row.size)
                is Row.Group -> bindGroup(v, row)
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder.itemView.getTag(R.id.thumb) as? Job)?.cancel()
        }

        /** A selectable file row: [title] above [subtitle], with [size] unless null. */
        private fun bindFile(v: View, f: File, title: String, subtitle: String, size: Long?) {
            v.findViewById<TextView>(R.id.title).text = title
            v.findViewById<TextView>(R.id.subtitle).text = subtitle
            v.findViewById<TextView>(R.id.size).apply {
                visibility = if (size == null) View.GONE else View.VISIBLE
                if (size != null) text = Formatter.formatShortFileSize(this@CleanupActivity, size)
            }
            val check = v.findViewById<CheckBox>(R.id.check)
            check.isChecked = f.absolutePath in selected
            v.setOnClickListener {
                toggle(f)
                check.isChecked = f.absolutePath in selected
            }
        }

        private fun bindGroup(v: View, row: Row.Group) {
            v.findViewById<TextView>(R.id.title).text = row.entry.name
            v.findViewById<TextView>(R.id.subtitle).text =
                resources.getQuantityString(R.plurals.dupe_group, row.files.size, row.files.size, Formatter.formatShortFileSize(this@CleanupActivity, row.size))
            val places = v.findViewById<LinearLayout>(R.id.locations)
            places.removeAllViews()
            for (f in row.files) {
                val place = LayoutInflater.from(v.context).inflate(R.layout.item_clean, places, false)
                // The name only where copies differ in it.
                val name = if (row.files.all { it.name == f.name }) "" else f.name
                bindFile(place, f, row.places[f].orEmpty(), name, null)
                place.findViewById<TextView>(R.id.subtitle).visibility = if (name.isEmpty()) View.GONE else View.VISIBLE
                places.addView(place)
            }
            bindCover(v, row.entry)
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
        private const val MAX_DEPTH = 12
        private const val LARGEST_COUNT = 100
    }
}
