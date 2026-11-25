package minty.emanata.debug

import minty.emanata.reader.ArchiveEntry
import minty.emanata.util.Formats
import minty.emanata.util.NaturalOrder

/** The archive check's plan and comparisons, apart from the device ([ArchiveCheckService]). */
internal object ArchiveRuns {
    /** How the archive is read: its file, or a channel over it, as books on a share are. */
    enum class Via(val key: String) {
        FILE("file"),
        CHANNEL("channel");

        companion object {
            /** By [key]; "both" names both. */
            fun list(key: String?): List<Via>? = when (key?.trim()?.lowercase()) {
                null, "file" -> listOf(FILE)
                "channel" -> listOf(CHANNEL)
                "both" -> listOf(FILE, CHANNEL)
                else -> null
            }
        }
    }

    /** The pages a reader shows, in its order: image entries (every entry if [anyEntry]) by natural order of their paths. */
    fun pages(entries: List<ArchiveEntry>, anyEntry: Boolean = false): List<ArchiveEntry> =
        entries.filter { anyEntry || Formats.isImageName(it.path) }.sortedWith(compareBy(NaturalOrder) { it.path })

    /**
     * [take] distinct indexes below [count] in a pseudo-random order fixed by [seed], the same on
     * every device and JVM: a Fisher-Yates shuffle driven by SplitMix64.
     */
    fun randomOrder(count: Int, take: Int, seed: Long): List<Int> {
        val order = IntArray(count) { it }
        var state = seed
        for (i in count - 1 downTo 1) {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            val j = java.lang.Long.remainderUnsigned(z, (i + 1).toLong()).toInt()
            val t = order[i]
            order[i] = order[j]
            order[j] = t
        }
        return order.take(take.coerceIn(0, count))
    }

    /** How listing [b] differs from listing [a]: paths in one only, sizes that differ, and whether the shared paths come in the same order. */
    data class ListingDiff(val onlyA: Int, val onlyB: Int, val sizeDiffers: Int, val sameOrder: Boolean) {
        val same: Boolean get() = onlyA == 0 && onlyB == 0 && sizeDiffers == 0 && sameOrder
    }

    /** How listing [b] differs from listing [a], by path. */
    fun compare(a: List<ArchiveEntry>, b: List<ArchiveEntry>): ListingDiff {
        val bySizeA = a.associate { it.path to it.size }
        val bySizeB = b.associate { it.path to it.size }
        val shared = a.map { it.path }.filter { it in bySizeB }
        val sizeDiffers = shared.count { bySizeA[it] != bySizeB[it] }
        return ListingDiff(
            onlyA = a.count { it.path !in bySizeB },
            onlyB = b.count { it.path !in bySizeA },
            sizeDiffers = sizeDiffers,
            sameOrder = shared == b.map { it.path }.filter { it in bySizeA }
        )
    }
}
