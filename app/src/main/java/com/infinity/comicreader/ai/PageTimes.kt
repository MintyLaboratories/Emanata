package com.infinity.comicreader.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

/**
 * Each model's page time as the reader learned it on this device ([ReaderAi.learn]): milliseconds
 * for a page of [ReaderAi.REFERENCE_MP], as [AiModel.pageMs] is, by model id, with the
 * [AiModel.fingerprint] of the files timed, so a model replaced starts from its catalog time again.
 * Kept in one small [file], written whole; read once, then from memory.
 */
class PageTimes(private val file: File) {
    private class Kept(val fingerprint: String, val ms: Long)

    private var kept: MutableMap<String, Kept>? = null

    /** [m]'s page time learned here; null before any, or after its files changed. */
    @Synchronized
    fun pageMs(m: AiModel): Long? = all()[m.id]?.takeIf { it.fingerprint == m.fingerprint }?.ms

    /** Keeps [ms] as [m]'s page time; written only when new, or moved by [CHANGE] or more, so a steady pace writes nothing. */
    @Synchronized
    fun keep(m: AiModel, ms: Long) {
        if (ms <= 0) return
        val all = all()
        val old = all[m.id]?.takeIf { it.fingerprint == m.fingerprint }
        if (old != null && abs(ms - old.ms) < old.ms * CHANGE) return
        all[m.id] = Kept(m.fingerprint, ms)
        write(all)
    }

    /** Forgets every time learned. */
    @Synchronized
    internal fun clear() {
        kept = HashMap()
        file.delete()
    }

    private fun all(): MutableMap<String, Kept> = kept ?: read().also { kept = it }

    private fun read(): MutableMap<String, Kept> {
        val out = HashMap<String, Kept>()
        runCatching {
            if (!file.isFile) return out
            for (line in file.readLines()) {
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val ms = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: continue
                out[parts[0]] = Kept(parts[1], ms)
            }
        }.onFailure { Log.w(TAG, "cannot read page times: ${it.message}") }
        return out
    }

    private fun write(all: Map<String, Kept>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(all.entries.joinToString("") { (id, k) -> "$id\t${k.fingerprint}\t${k.ms}\n" })
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.w(TAG, "cannot keep page times: ${it.message}") }
    }

    companion object {
        private const val TAG = "AiPageTimes"

        /** Share of a kept time a new one must differ by to be written. */
        const val CHANGE = 0.05

        @Volatile
        private var shared: PageTimes? = null

        /** The app's, one for all its screens; never backed up, as another device learns its own. */
        fun of(context: Context): PageTimes = shared ?: synchronized(this) {
            shared ?: PageTimes(File(context.applicationContext.noBackupFilesDir, "page-times")).also { shared = it }
        }
    }
}
