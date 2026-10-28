package com.infinity.comicreader

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import androidx.appcompat.app.AppCompatDelegate
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.RetiredFiles
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.files.FileWork
import com.infinity.comicreader.files.FileWorkService
import com.infinity.comicreader.net.Listings
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.RemoteChannel
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.NestedArchives
import com.infinity.comicreader.reader.PageSeams
import com.infinity.comicreader.reader.PageSizes
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Locales
import com.infinity.comicreader.util.Timing
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Timing.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val prefs = Prefs(this)
        prefs.upgrade()
        Library(this).apply {
            forgetPathKeyedBooks()
            migrateFolderLayouts(ReaderLayout.of(prefs))
            publishNameCharsets()
        }
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        Locales.apply(prefs.localeTag)
        Remotes.init(this)
        Remotes.answerMs = prefs.networkTimeoutMs
        FileWork.onStart = { FileWorkService.start(this) }
        RemoteCache.limitBytes = prefs.networkCacheBytes
        // Off the main thread: lookups by ID never wait on the database.
        thread(name = "book-index", isDaemon = true) { runCatching { BookIds.load() } }
        cleanCaches()
    }

    /** Share reads keep only their latest run once memory runs low, or the app is in the background list. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) RemoteChannel.trim()
    }

    /**
     * Drops temporary files left by earlier runs and files of earlier versions, and keeps
     * the cover, page-preview, page-size, seam, nested-archive and network caches bounded.
     */
    private fun cleanCaches() {
        thread(name = "cache-cleanup", isDaemon = true) {
            runCatching { RetiredFiles.drop(this@App) }
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
                PageSizes.trim(PageSizes.dirIn(cacheDir))
                PageSeams.trim(PageSeams.dirIn(cacheDir))
                NestedArchives.trim(NestedArchives.dirIn(cacheDir))
                RemoteCache.trim(RemoteCache.dirIn(cacheDir))
                Listings.trim(Listings.dirIn(cacheDir))
            }
        }
    }

    private companion object {
        /** Files shared out of the reader and documents copied in from other apps. */
        val TEMP_DIRS = listOf("shared", "incoming", "pictures", "cropped")
        const val STALE_MS = 10L * 60 * 1000
    }
}
