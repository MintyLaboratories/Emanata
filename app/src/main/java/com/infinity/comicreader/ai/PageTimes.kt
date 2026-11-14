package com.infinity.comicreader.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Each model's page time as the reader learned it on this device ([ReaderAi.learn]): milliseconds
 * for a page of [ReaderAi.REFERENCE_MP], as [AiModel.pageMs] is, by model id, with the
 * [AiModel.fingerprint] of the files timed, so a model replaced starts from its catalog time again,
 * and the catalog time it had then. A model not learned yet is estimated ([estimate]) from its
 * catalog time scaled as the learned ones ran against theirs, so every model is summed on one basis.
 * Kept in one small [file], written whole; read once, then from memory.
 */
class PageTimes(private val file: File) {
    private class Kept(val fingerprint: String, val ms: Double, val catalogMs: Long, val writtenMs: Double)

    private var kept: MutableMap<String, Kept>? = null

    /** [m]'s page time learned here; null before any, or after its files changed. */
    @Synchronized
    fun pageMs(m: AiModel): Long? = all()[m.id]?.takeIf { it.fingerprint == m.fingerprint }?.ms?.roundToLong()

    /** [m]'s page time on this device: as learned ([pageMs]), else its catalog time times [ratio]. */
    @Synchronized
    fun estimate(m: AiModel): Long = pageMs(m) ?: (m.pageMs * ratio()).roundToLong()

    /** How much longer than their catalog times the models learned here take: the median of their ratios; 1 before any. */
    @Synchronized
    fun ratio(): Double {
        val ratios = all().values.filter { it.catalogMs > 0 }.map { it.ms / it.catalogMs }.sorted()
        if (ratios.isEmpty()) return 1.0
        val mid = ratios.size / 2
        return if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
    }

    /**
     * Takes [ms], one page's time, into [m]'s: the first one as it is, later ones moving it
     * [WEIGHT] of the way, so one page (a reader's first, say) moves it only a little.
     * Written only when it moved by [CHANGE] or more since last written, so a steady pace writes nothing.
     */
    @Synchronized
    fun keep(m: AiModel, ms: Long) {
        if (ms <= 0) return
        val all = all()
        val old = all[m.id]?.takeIf { it.fingerprint == m.fingerprint }
        val now = if (old == null) ms.toDouble() else old.ms + (ms - old.ms) * WEIGHT
        if (old != null && abs(now - old.writtenMs) < old.writtenMs * CHANGE) {
            all[m.id] = Kept(m.fingerprint, now, m.pageMs, old.writtenMs)
            return
        }
        all[m.id] = Kept(m.fingerprint, now, m.pageMs, now)
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
                if (parts.size != 4) continue
                val ms = parts[2].toLongOrNull()?.takeIf { it > 0 } ?: continue
                val catalogMs = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: continue
                out[parts[0]] = Kept(parts[1], ms.toDouble(), catalogMs, ms.toDouble())
            }
        }.onFailure { Log.w(TAG, "cannot read page times: ${it.message}") }
        return out
    }

    private fun write(all: Map<String, Kept>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(all.entries.joinToString("") { (id, k) -> "$id\t${k.fingerprint}\t${k.ms.roundToLong()}\t${k.catalogMs}\n" })
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.w(TAG, "cannot keep page times: ${it.message}") }
    }

    companion object {
        private const val TAG = "AiPageTimes"

        /** Share of the way a kept time moves toward each new page's. */
        const val WEIGHT = 0.25

        /** Share of the time last written a kept time must move by to be written again. */
        const val CHANGE = 0.05

        @Volatile
        private var shared: PageTimes? = null

        /** The app's, one for all its screens; never backed up, as another device learns its own. */
        fun of(context: Context): PageTimes = shared ?: synchronized(this) {
            shared ?: PageTimes(File(context.applicationContext.noBackupFilesDir, "page-times")).also { shared = it }
        }
    }
}
