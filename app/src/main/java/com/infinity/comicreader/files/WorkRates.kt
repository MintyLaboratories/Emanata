package com.infinity.comicreader.files

import com.infinity.comicreader.reader.ImageKind
import com.infinity.comicreader.util.Formats
import java.util.Locale

/** A page's size class, by its encoded bytes: rates are kept per class, as a page's time does not grow with its bytes alone. */
enum class SizeClass(val key: String, val scale: Double) {
    SMALL("s", 0.3),
    MEDIUM("m", 1.0),
    LARGE("l", 2.5);

    companion object {
        const val MEDIUM_FROM = 1L shl 20
        const val LARGE_FROM = 4L shl 20

        fun of(bytes: Long): SizeClass = when {
            bytes < MEDIUM_FROM -> SMALL
            bytes < LARGE_FROM -> MEDIUM
            else -> LARGE
        }
    }
}

/**
 * What a Convert action does to each page, named by [key] in the rate record: [COMPACT]
 * (lossless JPEG XL at the efforts [com.infinity.comicreader.compact.PageWork] uses),
 * [COMPATIBLE], and later [upscale] by model. Actions that copy pages as they are have none.
 */
data class Step(val key: String) {
    companion object {
        val COMPACT = Step("compact")
        val COMPATIBLE = Step("compatible")

        /** Upscaling with [model], its name made safe for the record. */
        fun upscale(model: String) = Step("upscale:" + model.lowercase(Locale.ROOT).replace(UNSAFE, "_"))

        private val UNSAFE = Regex("[^a-z0-9._-]")
    }
}

/** How a book's files are held, which sets how fast they read: an archive's kind, or a folder. */
enum class Container(val key: String) {
    ZIP("zip"),
    RAR("rar"),
    SEVEN_ZIP("7z"),
    TAR("tar"),
    FOLDER("folder"),
    OTHER("other");

    companion object {
        /** The container of the book named [name], a folder when [isDir]. */
        fun of(name: String, isDir: Boolean): Container {
            if (isDir) return FOLDER
            val ext = Formats.ext(name)
            return when (ext) {
                in Formats.ZIP -> ZIP
                in Formats.RAR -> RAR
                in Formats.SEVEN_ZIP -> SEVEN_ZIP
                in Formats.TAR -> TAR
                else -> OTHER
            }
        }
    }
}

/**
 * This device's speeds at Convert work, learned from real jobs with the app in front: seconds
 * of wall time per page by [Step], format and [SizeClass] (pages convert side by side, each
 * charged its share); seconds per byte read by location and [Container], and written by
 * location and action; the bytes each action writes per byte of book; and each action's time
 * per book beyond those (opening, checking, putting the new file in place). Work done with
 * the app away counts only as how much slower it ran ([slowdown]).
 * Each figure blends what was measured with its seed, the seed counting as a few units
 * measured, so a first job leans on the seed and later ones on what they measured; only the
 * most recent work counts ([CAP_PAGES], [CAP_BYTES], [CAP_BOOKS]). Locations are [DEVICE] or
 * "share:" and a share's id. Thread-safe.
 */
class WorkRates(private val seeds: Seeds = Seeds()) {
    /**
     * Where figures not measured yet come from: [speed], how much slower than the reference
     * device this one decodes (calibration's timings, [speedOf]), and [shareRead], a share's
     * read speed as its reads measured (bytes a second), if known.
     */
    class Seeds(val speed: () -> Double = { 1.0 }, val shareRead: (String) -> Double? = { null })

    /** [amount] units done in [seconds] (for the bytes written per byte of book, [amount] bytes of book and [seconds] bytes written). */
    private class Tally(var amount: Double, var seconds: Double)

    private val tallies = HashMap<String, Tally>()

    /**
     * Seconds of wall time one page of [format] in [size] takes for [step]; [lossy] for a lossy
     * WebP or a JPEG XL holding a JPEG ([ConvertEstimate.lossy]), kept apart from the others.
     */
    @Synchronized
    fun secondsPerPage(step: Step, format: ImageKind?, size: SizeClass, lossy: Boolean = false): Double =
        blended(pageKey(step, format, size, lossy), PRIOR_PAGES, pageSeed(step, format, size, lossy))

    /** Bytes a second read from a book in [container] at [location]. */
    @Synchronized
    fun readRate(location: String, container: Container): Double = 1 / blended(readKey(location, container), PRIOR_BYTES, 1 / readSeed(location, container))

    /** Bytes a second action [op] writes (and reads back) at [location]: Pack's copies read their loose images as they write. */
    @Synchronized
    fun writeRate(location: String, op: String): Double = 1 / blended(writeKey(location, op), PRIOR_BYTES, 1 / writeSeed(location))

    /** Bytes action [op] writes per byte of book. */
    @Synchronized
    fun outputShare(op: String): Double = blended(outputKey(op), PRIOR_BYTES, OUTPUT_SHARES[op] ?: 1.0)

    /** Seconds per book of action [op] beyond reading, converting and writing. */
    @Synchronized
    fun bookSeconds(op: String): Double = blended(bookKey(op), PRIOR_BOOKS, BOOK_SECONDS)

    /** How many times longer work takes with the app away than these figures say. */
    @Synchronized
    fun slowdown(): Double = blended(BACK_KEY, PRIOR_BACK, BACK_SLOWDOWN)

    /** One page of [format], [bytes] long ([lossy] as [secondsPerPage] says), took [seconds] for [step]. */
    fun page(step: Step, format: ImageKind?, bytes: Long, seconds: Double, lossy: Boolean = false) =
        add(pageKey(step, format, SizeClass.of(bytes), lossy), 1.0, seconds, CAP_PAGES)

    /** [bytes] of a book in [container] at [location] read in [seconds]. */
    fun read(location: String, container: Container, bytes: Long, seconds: Double) = add(readKey(location, container), bytes.toDouble(), seconds, CAP_BYTES)

    /** [bytes] written and read back by action [op] at [location] in [seconds]. */
    fun written(location: String, op: String, bytes: Long, seconds: Double) = add(writeKey(location, op), bytes.toDouble(), seconds, CAP_BYTES)

    /** Action [op] wrote [written] bytes for a book of [bytes]. */
    fun output(op: String, bytes: Long, written: Long) {
        if (written >= 0) add(outputKey(op), bytes.toDouble(), written.toDouble(), CAP_BYTES)
    }

    /** A book of action [op] took [seconds] beyond reading, converting and writing. */
    fun book(op: String, seconds: Double) = add(bookKey(op), 1.0, seconds, CAP_BOOKS)

    /** Work these figures put at [expected] seconds took [seconds] with the app away. */
    fun away(expected: Double, seconds: Double) = add(BACK_KEY, expected, seconds, CAP_BACK)

    @Synchronized
    private fun add(key: String, amount: Double, seconds: Double, cap: Double) {
        if (amount <= 0 || !(seconds >= 0) || seconds.isInfinite()) return
        val t = tallies.getOrPut(key) { Tally(0.0, 0.0) }
        t.amount += amount
        t.seconds += seconds
        // Older work counts less as more comes.
        if (t.amount > cap) {
            t.seconds *= cap / t.amount
            t.amount = cap
        }
    }

    /** Seconds per unit at [key]: what was measured, with [seed] counting as [prior] units. */
    private fun blended(key: String, prior: Double, seed: Double): Double {
        val t = tallies[key] ?: return seed
        return (t.seconds + prior * seed) / (t.amount + prior)
    }

    /** The seed of a page figure: from a measured size class of the same step and format, else the default scaled by the device's speed; scaled by size class for JPEG only ([scale]). */
    private fun pageSeed(step: Step, format: ImageKind?, size: SizeClass, lossy: Boolean): Double {
        val near = SizeClass.entries.filter { it != size }.mapNotNull { other ->
            tallies[pageKey(step, format, other, lossy)]?.takeIf { it.amount >= PRIOR_PAGES }?.let { other to it }
        }.maxByOrNull { it.second.amount }
        if (near != null) return near.second.seconds / near.second.amount * scale(format, size) / scale(format, near.first)
        return defaultSeconds(step, format, lossy) * scale(format, size) * seeds.speed().coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** How a page's time scales with its [size] class: a JPEG's bytes follow its pixels; a lossless page's, how well it compresses. */
    private fun scale(format: ImageKind?, size: SizeClass) = if (format == ImageKind.JPEG) size.scale else 1.0

    private fun readSeed(location: String, container: Container): Double {
        val local = when (container) {
            Container.RAR -> RAR_READ
            Container.SEVEN_ZIP -> SEVEN_ZIP_READ
            else -> DEVICE_READ
        } / seeds.speed().coerceIn(MIN_SPEED, MAX_SPEED)
        if (location == DEVICE) return local
        val share = shareId(location)?.let(seeds.shareRead)?.takeIf { it > 0 } ?: SHARE_READ
        // Unpacking a solid archive may be slower than the share.
        return minOf(local, share)
    }

    private fun writeSeed(location: String): Double {
        if (location == DEVICE) return DEVICE_WRITE
        val read = shareId(location)?.let(seeds.shareRead)?.takeIf { it > 0 } ?: SHARE_READ
        return read * SHARE_WRITE_SHARE
    }

    /** What was measured, one `key=amount,seconds` line each. */
    @Synchronized
    fun encode(): String = tallies.entries.sortedBy { it.key }.joinToString("\n") { (k, t) -> "$k=${t.amount},${t.seconds}" }

    companion object {
        const val DEVICE = "device"

        /** The location of share [id]. */
        fun share(id: String) = "share:$id"

        /** The share id of [location]; null on the device. */
        fun shareId(location: String): String? = location.removePrefix("share:").takeIf { location.startsWith("share:") }

        /** The figures [text] holds ([encode]), lines it cannot read left out. */
        fun decode(text: String, seeds: Seeds = Seeds()): WorkRates {
            val rates = WorkRates(seeds)
            for (line in text.lineSequence()) {
                val eq = line.lastIndexOf('=')
                if (eq <= 0) continue
                val parts = line.substring(eq + 1).split(',')
                val amount = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
                val seconds = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
                if (amount > 0 && seconds >= 0 && seconds.isFinite()) rates.tallies[line.substring(0, eq)] = Tally(amount, seconds)
            }
            return rates
        }

        /**
         * How much slower than the reference device this one decodes, from calibration's
         * platform JPEG decode ([jpegMs]) and the CPU time of its JPEG XL decode ([jxlCpuMs]) of its
         * synthetic 4000x6000 pages; 1 when neither is known.
         */
        fun speedOf(jpegMs: Double?, jxlCpuMs: Double?): Double {
            val ratios = listOfNotNull(jpegMs?.takeIf { it > 0 }?.div(REF_JPEG_MS), jxlCpuMs?.takeIf { it > 0 }?.div(REF_JXL_CPU_MS))
            return if (ratios.isEmpty()) 1.0 else ratios.average().coerceIn(MIN_SPEED, MAX_SPEED)
        }

        /**
         * Seconds of wall time a medium page of [format] ([lossy] as [secondsPerPage] says)
         * takes for [step] on the reference device with the app in front, as measured on
         * synthetic 2160x3840 pages: for Compact a JPEG 0.25 s (four side by side), a PNG of
         * flat colors or a lossless WebP 2.9 s (alone, as its pixels take the memory of four), a
         * lossless JPEG XL 13 s and one holding a JPEG 2.3 s; for Make compatible a lossless JPEG
         * XL 8.3 s and one holding a JPEG 0.1 s. AVIF, HEIF and TIFF are not measured; formats a
         * step leaves as they are take a glance.
         */
        fun defaultSeconds(step: Step, format: ImageKind?, lossy: Boolean = false): Double = when (step) {
            Step.COMPACT -> when (format) {
                ImageKind.JPEG -> 0.25
                ImageKind.PNG, ImageKind.BMP, ImageKind.TIFF -> 3.0
                ImageKind.WEBP -> if (lossy) GLANCE else 2.9
                ImageKind.JXL -> if (lossy) 2.3 else 13.0
                else -> GLANCE
            }
            Step.COMPATIBLE -> when (format) {
                ImageKind.JXL -> if (lossy) 0.1 else 8.3
                ImageKind.AVIF, ImageKind.HEIF -> 0.6
                ImageKind.TIFF -> 1.0
                else -> GLANCE
            }
            else -> UNKNOWN_STEP
        }

        private fun pageKey(step: Step, format: ImageKind?, size: SizeClass, lossy: Boolean): String {
            val name = format?.name?.lowercase(Locale.ROOT) ?: "other"
            return "page/${step.key}/$name${if (lossy && format in ConvertEstimate.SPLIT) "-lossy" else ""}/${size.key}"
        }
        private fun readKey(location: String, container: Container) = "read/$location/${container.key}"
        private fun writeKey(location: String, op: String) = "write/$location/$op"
        private fun bookKey(op: String) = "book/$op"
        private fun outputKey(op: String) = "out/$op"
        private const val BACK_KEY = "away"

        /** Calibration's 4000x6000 JPEG decode, and the CPU time of its 4000x6000 JPEG XL decode, on the reference device (Snapdragon 8 Gen 1 class, Android 16). */
        const val REF_JPEG_MS = 93.0
        const val REF_JXL_CPU_MS = 790.0
        private const val MIN_SPEED = 0.25
        private const val MAX_SPEED = 8.0

        /** A page a step leaves as it is: read and sniffed. */
        private const val GLANCE = 0.01

        /** A page of a step with no default (a model never run here). */
        private const val UNKNOWN_STEP = 5.0

        /**
         * Bytes a second on the device: reading a ZIP, folder or TAR, a RAR (a guess: no
         * compressed RAR was measured), a solid 7z (LZMA2 of JPEG pages measured 11 MB/s); writing.
         */
        const val DEVICE_READ = 120e6
        const val RAR_READ = 40e6
        const val SEVEN_ZIP_READ = 12e6
        const val DEVICE_WRITE = 150e6

        /** Bytes a second reading a share whose reads measured nothing yet, and the share of that writing reaches. */
        const val SHARE_READ = 4e6
        const val SHARE_WRITE_SHARE = 0.6

        /** Seconds per book beyond reading, converting and writing, before any is measured. */
        const val BOOK_SECONDS = 0.3

        /** Bytes each action writes per byte of book before any is measured; 1 for others. */
        val OUTPUT_SHARES = mapOf("compact" to 0.8, "pack-compacted" to 0.8, "compatible" to 1.3)

        /**
         * How many times longer work takes with the app away, before any is measured: the system
         * clocks its cores down for an app not in front (one job measured 2.9 to 3.4 times).
         */
        const val BACK_SLOWDOWN = 3.5

        /** How many units a seed counts as. */
        private const val PRIOR_PAGES = 3.0
        private const val PRIOR_BYTES = 32.0 * 1024 * 1024
        private const val PRIOR_BOOKS = 2.0
        private const val PRIOR_BACK = 60.0

        /** The most recent work kept: pages, bytes, books per figure. */
        const val CAP_PAGES = 300.0
        const val CAP_BYTES = 2.0 * 1024 * 1024 * 1024
        const val CAP_BOOKS = 50.0
        const val CAP_BACK = 3600.0
    }
}
