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
 * catalog time scaled as the learned ones of its engine ran against theirs ([ratio]), so every
 * model is summed on one basis; an imported model's own time was measured on this device, so it
 * stands unscaled and scales no other. Kept in one small [file], written whole; read once, then
 * from memory.
 */
class PageTimes(private val file: File) {
    private class Kept(val fingerprint: String, val ms: Double, val catalogMs: Long, val writtenMs: Double)

    /** What runs a model, as its catalog times were measured: ncnn ([CatalogModel]) or GLES shaders ([ShaderModel]). */
    enum class Engine { NCNN, SHADER }

    private var kept: MutableMap<String, Kept>? = null

    /** [m]'s page time learned here; null before any, or after its files changed. */
    @Synchronized
    fun pageMs(m: AiModel): Long? = all()[m.id]?.takeIf { it.fingerprint == m.fingerprint }?.ms?.roundToLong()

    /**
     * [m]'s page time on this device: as learned ([pageMs]), else for an imported model its own
     * (measured here), else its catalog time times its engine's [ratio].
     */
    @Synchronized
    fun estimate(m: AiModel): Long = pageMs(m) ?: engineOf(m)?.let { (m.pageMs * ratio(it)).roundToLong() } ?: m.pageMs

    /**
     * How much longer than their catalog times the catalog models of [engine] learned here take:
     * the median of their ratios; the other engine's when none of [engine] is learned (a device's
     * speed counts for both); 1 before any.
     */
    @Synchronized
    fun ratio(engine: Engine = Engine.NCNN): Double =
        median(ratios(engine)) ?: median(ratios(Engine.entries.first { it != engine })) ?: 1.0

    private fun ratios(engine: Engine): List<Double> =
        all().filter { (id, k) -> k.catalogMs > 0 && engineOf(id) == engine }.values.map { it.ms / it.catalogMs }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** The engine of catalog model or shader chain [m]; null for an imported model, timed on this device. */
    private fun engineOf(m: AiModel): Engine? = when (m) {
        is CatalogModel -> Engine.NCNN
        is ShaderModel -> Engine.SHADER
        is UserModel -> null
    }

    /** The engine of the catalog model or shader chain [id] names; null for any other (an imported model's, or one gone from the catalog). */
    private fun engineOf(id: String): Engine? = when {
        ModelCatalog.byId(id) != null -> Engine.NCNN
        ShaderCatalog.byId(id) != null -> Engine.SHADER
        else -> null
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
