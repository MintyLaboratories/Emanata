package com.infinity.comicreader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import com.infinity.comicreader.data.BookId
import com.infinity.comicreader.data.Crumbs
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibraryData
import com.infinity.comicreader.data.MissingBooks
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Forget missing books" (Settings › Your data): scans every location for the books the
 * library keeps data for ([MissingBooks]), showing how far it got, with Cancel; then lists
 * those found nowhere for review. They are ticked only when every location was reached;
 * otherwise the locations not reached are named and each book says it may have moved
 * there. Forgetting asks first.
 */
class MissingBooksActivity : AppCompatActivity() {

    private lateinit var library: Library
    private lateinit var list: RecyclerView
    private lateinit var progress: View
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var leftButton: MaterialButton
    private lateinit var forgetButton: MaterialButton
    private lateinit var roots: List<StorageRoot>

    private var result: MissingBooks.Result? = null
    private val selected = LinkedHashSet<BookId>()
    private var scan: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        setContentView(R.layout.activity_cleanup)
        val toolbar = findViewById<MaterialToolbar>(R.id.cleanup_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.pref_forget_missing)
        toolbar.setNavigationOnClickListener { finish() }
        list = findViewById(R.id.cleanup_list)
        progress = findViewById(R.id.cleanup_progress)
        summary = findViewById(R.id.cleanup_summary)
        empty = findViewById(R.id.cleanup_empty)
        leftButton = findViewById(R.id.cleanup_select)
        forgetButton = findViewById(R.id.cleanup_delete)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()
        roots = StorageRoots.list(this)
        startScan()
    }

    /** Walks every location; Cancel stops it and leaves the screen, forgetting nothing. */
    private fun startScan() {
        progress.visibility = View.VISIBLE
        summary.ellipsize = null
        leftButton.setText(R.string.cancel)
        leftButton.setOnClickListener { finish() }
        forgetButton.isEnabled = false
        forgetButton.setText(R.string.missing_forget)
        val locations = MissingBooks.locations(roots)
        scan = lifecycleScope.launch {
            val found = withContext(AppDispatchers.io) {
                val job = coroutineContext
                MissingBooks(library, locations, alive = { job.isActive }, progress = { p ->
                    runOnUiThread { if (!isFinishing) summary.text = getString(R.string.missing_scanning, p.location, p.folders, p.books) }
                }).run()
            } ?: return@launch
            show(found)
        }
    }

    /** Lists [found]; ticks [ticked] when given (what stays ticked after a forget), else every book when the scan was complete. */
    private fun show(found: MissingBooks.Result, ticked: Set<BookId>? = null) {
        result = found
        progress.visibility = View.GONE
        selected.clear()
        if (ticked != null) found.missing.filter { it.id in ticked }.mapTo(selected) { it.id }
        else if (found.complete) found.missing.mapTo(selected) { it.id }
        val lines = ArrayList<String>()
        if (found.missing.isNotEmpty()) lines += resources.getQuantityString(R.plurals.missing_found, found.missing.size, found.missing.size)
        if (!found.complete) lines += getString(R.string.missing_skipped, found.skipped.joinToString(", "))
        summary.text = lines.joinToString("\n")
        summary.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        empty.setText(R.string.missing_none)
        empty.visibility = if (found.missing.isEmpty()) View.VISIBLE else View.GONE
        leftButton.setOnClickListener { selectAllOrNone() }
        forgetButton.setOnClickListener { confirmForget() }
        list.adapter?.notifyDataSetChanged()
        updateButtons()
    }

    private fun selectAllOrNone() {
        val missing = result?.missing ?: return
        if (selected.size == missing.size) selected.clear() else missing.mapTo(selected) { it.id }
        list.adapter?.notifyDataSetChanged()
        updateButtons()
    }

    private fun updateButtons() {
        val missing = result?.missing.orEmpty()
        leftButton.isEnabled = missing.isNotEmpty()
        leftButton.setText(if (missing.isNotEmpty() && selected.size == missing.size) R.string.missing_select_none else R.string.missing_select_all)
        forgetButton.isEnabled = selected.isNotEmpty()
        forgetButton.text = if (selected.isEmpty()) getString(R.string.missing_forget) else getString(R.string.missing_forget_n, selected.size)
    }

    private fun confirmForget() {
        val ids = selected.toSet()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.missing_confirm_title, ids.size, ids.size))
            .setMessage(resources.getQuantityString(R.plurals.missing_confirm_msg, ids.size))
            .setPositiveButton(R.string.missing_forget_action) { _, _ -> forget(ids) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun forget(ids: Set<BookId>) {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(AppDispatchers.io) { LibraryData.forget(app, library, ids) }
            Toast.makeText(this@MissingBooksActivity, resources.getQuantityString(R.plurals.missing_forgotten, ids.size, ids.size), Toast.LENGTH_SHORT).show()
            val now = result ?: return@launch
            show(now.copy(missing = now.missing.filterNot { it.id in ids }), ticked = selected - ids)
        }
    }

    /** What is kept for [book], as a short list. */
    private fun keptText(book: MissingBooks.Missing): String {
        val k = book.kept
        val parts = ArrayList<String>()
        if (k.favorite) parts += getString(R.string.kept_favorite)
        if (k.read) parts += getString(R.string.kept_read)
        if (k.history) parts += getString(R.string.kept_history)
        if (k.bookmarks > 0) parts += resources.getQuantityString(R.plurals.kept_bookmarks, k.bookmarks, k.bookmarks)
        if (k.hidden > 0) parts += resources.getQuantityString(R.plurals.kept_hidden, k.hidden, k.hidden)
        if (k.settings) parts += getString(R.string.kept_settings)
        return parts.joinToString(" · ")
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = result?.missing?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_clean, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val found = result ?: return
            val book = found.missing[position]
            val v = holder.itemView
            val path = book.seen.path
            v.findViewById<TextView>(R.id.title).text = File(path).name
            val folder = Crumbs.of(File(path).parent.orEmpty(), roots).joinToString(" / ") { it.label }
            val lines = listOf(folder, keptText(book)) + if (found.complete) emptyList() else listOf(getString(R.string.missing_maybe_moved))
            v.findViewById<TextView>(R.id.subtitle).apply {
                maxLines = lines.size
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = lines.joinToString("\n")
            }
            v.findViewById<TextView>(R.id.size).visibility = View.GONE
            val check = v.findViewById<CheckBox>(R.id.check)
            check.isChecked = book.id in selected
            v.setOnClickListener {
                if (!selected.add(book.id)) selected.remove(book.id)
                check.isChecked = book.id in selected
                updateButtons()
            }
        }
    }
}
