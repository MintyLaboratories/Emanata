package com.infinity.comicreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.RetiredFiles
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.reader.NestedArchives
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Locales
import java.io.File
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        prefs.upgrade()
        Library(this).migrateFolderLayouts(ReaderLayout.of(prefs))
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        Locales.apply(prefs.localeTag)
        cleanCaches()
    }

    /**
     * Drops temporary files left by earlier runs and files of earlier versions, and keeps
     * the cover, page-preview and nested-archive caches bounded.
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
                NestedArchives.trim(NestedArchives.dirIn(cacheDir))
            }
        }
    }

    private companion object {
        /** Files shared out of the reader and documents copied in from other apps. */
        val TEMP_DIRS = listOf("shared", "incoming", "pictures", "cropped")
        const val STALE_MS = 10L * 60 * 1000
    }
}
