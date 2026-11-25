package minty.emanata

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.runBlocking
import minty.emanata.ai.ModelImports
import minty.emanata.ai.ModelStore
import minty.emanata.ai.ProcessedStore
import minty.emanata.ai.UserModels
import minty.emanata.data.BookFactsCache
import minty.emanata.data.BookIds
import minty.emanata.data.Library
import minty.emanata.data.LikenessStore
import minty.emanata.data.Prefs
import minty.emanata.data.Thumbs
import minty.emanata.files.ConvertRates
import minty.emanata.files.FileWork
import minty.emanata.files.FileWorkService
import minty.emanata.net.Listings
import minty.emanata.net.Metered
import minty.emanata.net.NetPolicy
import minty.emanata.net.RemoteCache
import minty.emanata.net.RemoteChannel
import minty.emanata.net.Remotes
import minty.emanata.net.ShareSpeeds
import minty.emanata.reader.Calibrator
import minty.emanata.reader.NativeResampler
import minty.emanata.reader.NestedArchives
import minty.emanata.reader.PageSeams
import minty.emanata.reader.PageSizes
import minty.emanata.reader.PreviewStore
import minty.emanata.ui.FileJobs
import minty.emanata.util.FileUtils
import minty.emanata.util.Locales
import minty.emanata.util.Timing
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timing.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val prefs = Prefs(this)
        Library(this).publishNameCharsets()
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        Locales.apply(prefs.localeTag)
        ShareSpeeds.init(this)
        Remotes.init(this)
        Metered.init(this)
        NetPolicy.rules = prefs.netRules
        Remotes.answerMs = prefs.networkTimeoutMs
        FileWork.onStart = { FileWorkService.start(this) }
        FileWork.inFront = { Calibrator.inFront }
        FileWork.slowdown = ConvertRates::slowdown
        RemoteCache.limitBytes = prefs.networkCacheBytes
        Calibrator.start(this)
        background("calibration-load") { Calibrator.load() }
        // Before any chain is read: a pick of an imported model needs it known.
        runCatching { UserModels.load(ModelStore.of(this)) }
        // Off the main thread: lookups by ID never wait on the database.
        background("book-index") {
            runCatching { BookIds.load() }
            // A rebuild the app stopped midway hands its book's data over now.
            runCatching { FileJobs(this, Library(this), prefs).recover() }
        }
        // Off the main thread: making the cover detectors reads settings and storage, once, before list binds ask them.
        background("cover-detector") { runCatching { Thumbs.detector(this).id } }
        cleanCaches()
    }

    /** The threads of the work [onCreate] started in the background. */
    private val startWork = ArrayList<Thread>()

    /** Runs [block] on a daemon thread named [name], as start work, after [startGate]. */
    private fun background(name: String, block: () -> Unit) {
        startWork += thread(name = name, isDaemon = true) {
            startGate(name)
            block()
        }
    }

    /**
     * Called only where the process is emulated, as in tests, never on a device: waits for
     * the start work, then closes the app's databases, so none of this app's work or open
     * connections outlives it into the next.
     */
    override fun onTerminate() {
        startWork.forEach { it.join() }
        BookIds.close()
        BookFactsCache.reset()
        LikenessStore.reset()
        super.onTerminate()
    }

    /**
     * Share reads keep only their latest run once memory runs low, or the app is in the
     * background list; the GPU resampler frees its buffers then and once the UI hides (from
     * API 34 on, only UI_HIDDEN and BACKGROUND arrive).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) RemoteChannel.trim()
        NativeResampler.onTrimMemory(level)
    }

    /**
     * Drops temporary files left by earlier runs and models no longer offered, and keeps
     * the cover, page-preview, page-size, seam, nested-archive and network caches bounded.
     */
    private fun cleanCaches() {
        runCatching { ModelImports.tidyAtStart(ModelStore.of(this)) }.onSuccess { job -> background("model-tidy") { runBlocking { job.join() } } }
        background("cache-cleanup") {
            runCatching {
                // Only files that are clearly stale: a document another app just handed us
                // may be copied into the cache while this thread runs.
                val cutoff = System.currentTimeMillis() - STALE_MS
                for (dir in TEMP_DIRS) {
                    File(cacheDir, dir).listFiles()?.filter { it.lastModified() < cutoff }
                        ?.forEach { FileUtils.deleteTree(it) }
                }
                File(cacheDir, "crop_src.png").takeIf { it.lastModified() < cutoff }?.delete()
                Thumbs.trim(this)
                PreviewStore.trim(PreviewStore.dirIn(cacheDir))
                ProcessedStore.trim(ProcessedStore.dirIn(cacheDir))
                PageSizes.trim(PageSizes.dirIn(cacheDir))
                PageSeams.trim(PageSeams.dirIn(cacheDir))
                NestedArchives.trim(NestedArchives.dirIn(cacheDir))
                RemoteCache.trim(RemoteCache.dirIn(cacheDir))
                Listings.trim(Listings.dirIn(cacheDir))
            }
        }
    }

    internal companion object {
        /** Runs first on each start work's thread, given its name; tests hold start work there. */
        @VisibleForTesting
        @Volatile
        internal var startGate: (String) -> Unit = {}

        /** Files shared out of the reader and documents copied in from other apps. */
        private val TEMP_DIRS = listOf("shared", "incoming", "pictures", "cropped")
        private const val STALE_MS = 10L * 60 * 1000
    }
}
