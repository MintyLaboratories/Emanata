package minty.emanata.reader

import java.io.File
import java.io.IOException

/**
 * Tells solid archives (RAR 4, RAR 5 and 7z), where each entry unpacks those stored
 * before it, from their headers alone: slow to jump around in, so worth repacking.
 */
object SolidArchives {
    /**
     * True for a solid RAR (by its main header) or 7z (some block holding several entries),
     * false for any other archive or file, or one whose headers cannot be read. Blocking.
     */
    fun isSolid(file: File): Boolean = when (ArchiveKind.detect(file)) {
        ArchiveKind.RAR -> rarSolid(head(file)) == true
        ArchiveKind.SEVEN_ZIP -> try {
            SevenZipReader(file).use { it.solid == true }
        } catch (e: Exception) {
            false
        }
        else -> false
    }

    /**
     * Whether the RAR whose first bytes are [header] is solid, by its main header; null when
     * it is no RAR or its main header is not where it starts (encrypted headers, cut short).
     */
    fun rarSolid(header: ByteArray): Boolean? = RarMain.of(header)?.solid

    /** The first [ArchiveKind.HEADER_BYTES] of [file], fewer when shorter; empty when unreadable. */
    private fun head(file: File): ByteArray = try {
        file.inputStream().use { input ->
            val out = ByteArray(ArchiveKind.HEADER_BYTES)
            var n = 0
            while (n < out.size) {
                val r = input.read(out, n, out.size - n)
                if (r < 0) break
                n += r
            }
            out.copyOf(n)
        }
    } catch (e: IOException) {
        ByteArray(0)
    }
}
