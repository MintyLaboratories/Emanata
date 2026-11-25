package minty.emanata.ui

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import minty.emanata.R
import minty.emanata.data.BookId
import minty.emanata.data.Library
import minty.emanata.data.LibraryData
import minty.emanata.data.MissingBooks
import minty.emanata.util.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Forget missing books" scan and its review ([MissingBooksActivity]), kept across a
 * recreate (rotation, dark mode, a resize in split screen), so the scan goes on instead of
 * starting over: how far it got, what it found, and the books ticked. Leaving the screen
 * for good cancels it.
 */
class MissingScan : ViewModel() {
    private val _progress = MutableStateFlow<MissingBooks.Progress?>(null)
    private val _result = MutableStateFlow<MissingBooks.Result?>(null)

    /** How far the scan is; null before its first report. */
    val progress: StateFlow<MissingBooks.Progress?> = _progress

    /** What the scan found, less the books forgotten since; null until it is done. */
    val result: StateFlow<MissingBooks.Result?> = _result

    /** The books ticked for forgetting: at first those [MissingBooks.Result.ticked]. */
    val selected = LinkedHashSet<BookId>()

    private var started = false

    /** Scans [locations] for the books the library keeps data for; once only. */
    fun start(context: Context, locations: List<MissingBooks.Location>) {
        if (started) return
        started = true
        val library = Library(context.applicationContext)
        viewModelScope.launch {
            val found = withContext(AppDispatchers.io) {
                val job = coroutineContext
                MissingBooks(library, locations, alive = { job.isActive }, progress = { _progress.value = it }).run()
            } ?: return@launch
            found.missing.filter { found.ticked(it) }.mapTo(selected) { it.id }
            _result.value = found
        }
    }

    /** Forgets the books [ids], says so, and lists the rest, ticked as they were. */
    fun forget(context: Context, ids: Set<BookId>) {
        val app = context.applicationContext
        viewModelScope.launch {
            withContext(AppDispatchers.io) { LibraryData.forget(app, Library(app), ids) }
            Toast.makeText(app, app.resources.getQuantityString(R.plurals.missing_forgotten, ids.size, ids.size), Toast.LENGTH_SHORT).show()
            selected -= ids
            _result.value = _result.value?.let { r -> r.copy(missing = r.missing.filterNot { it.id in ids }) }
        }
    }
}
