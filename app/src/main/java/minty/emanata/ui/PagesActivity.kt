package minty.emanata.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import minty.emanata.R
import minty.emanata.net.Remotes
import minty.emanata.data.BookIds
import minty.emanata.data.Library
import minty.emanata.data.Prefs
import minty.emanata.reader.HiddenPages
import minty.emanata.reader.PageSource
import minty.emanata.reader.PageSources
import minty.emanata.util.AppDispatchers
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows a grid of page thumbnails, hidden pages left out, and returns the chosen page to the viewer. */
class PagesActivity : AppCompatActivity() {

    private var adapter: ThumbAdapter? = null
    private var source: PageSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pages)

        val toolbar = findViewById<MaterialToolbar>(R.id.pages_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val path = intent.getStringExtra(EXTRA_PATH)
        val current = intent.getIntExtra(EXTRA_PAGE, 0)
        if (path == null) {
            finish()
            return
        }

        val progress = findViewById<ProgressBar>(R.id.pages_progress)
        val recycler = findViewById<RecyclerView>(R.id.pages_recycler)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        recycler.layoutManager = GridLayoutManager(this, (widthDp / 110f).toInt().coerceIn(3, 6))
        progress.visibility = View.VISIBLE

        val prefs = Prefs(this)
        lifecycleScope.launch {
            val result = withContext(AppDispatchers.io) {
                // Backing out stops fetching a book from a share.
                val fetch = PageSources.Fetch(canceled = { !isActive })
                val hidden = Library(this@PagesActivity).hiddenPages(BookIds.find(Remotes.file(path)))
                val opened = runCatching { HiddenPages.wrap(PageSources.open(Remotes.file(path), prefs.charset, cacheDir, fetch = fetch), hidden) }
                try {
                    ensureActive()
                } catch (e: CancellationException) {
                    // Closed while loading: do not leak the archive handle.
                    opened.getOrNull()?.let { runCatching { it.close() } }
                    throw e
                }
                opened
            }
            progress.visibility = View.GONE
            result.onSuccess { opened ->
                source = opened
                val f = File(path)
                val dot = f.name.lastIndexOf('.')
                toolbar.subtitle = if (dot > 0) f.name.substring(0, dot) else f.name
                val a = ThumbAdapter(opened, current, prefs.linearLight, prefs.thumbScaling) { page ->
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PAGE, page))
                    finish()
                }
                adapter = a
                recycler.adapter = a
                if (current > 0) recycler.scrollToPosition(current)
            }.onFailure {
                Toast.makeText(this@PagesActivity, R.string.error_open, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter?.release()
        runCatching { source?.close() }
        source = null
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_PAGE = "extra_page"
        const val EXTRA_RESULT_PAGE = "result_page"
    }
}
