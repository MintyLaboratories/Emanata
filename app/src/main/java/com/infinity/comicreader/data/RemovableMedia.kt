package com.infinity.comicreader.data

import java.util.Locale

/**
 * Tells USB drives from SD cards, which Android lists alike. The block device
 * mounted for a volume says it best (`sd*` disks are USB, `mmcblk*` are SD cards);
 * failing that, the volume's description as the system words it ("USB drive").
 */
object RemovableMedia {
    /** SCSI disk majors, which USB mass storage uses: 8, 65-71, 128-135. */
    private val SCSI_MAJORS = setOf(8) + (65..71) + (128..135)
    private const val MMC_MAJOR = 179

    private val VOLD_DEVICE = Regex("""^/dev/block/vold/public:(\d+),\d+$""")

    /**
     * The kind of the volume whose folder is named [volumeId] (`1234-ABCD`) from the
     * lines of `/proc/self/mounts` in [mounts]; null when no mount says.
     */
    fun fromMounts(volumeId: String, mounts: String): StorageRoot.Kind? {
        if (volumeId.isEmpty()) return null
        for (line in mounts.lineSequence()) {
            val fields = line.trim().split(Regex("""\s+"""))
            if (fields.size < 2) continue
            val (device, point) = fields
            if (!point.endsWith("/$volumeId")) continue
            VOLD_DEVICE.matchEntire(device)?.let { m ->
                when (m.groupValues[1].toIntOrNull()) {
                    in SCSI_MAJORS -> return StorageRoot.Kind.USB
                    MMC_MAJOR -> return StorageRoot.Kind.REMOVABLE
                }
            }
            val name = device.substringAfterLast('/')
            if (name.startsWith("sd")) return StorageRoot.Kind.USB
            if (name.startsWith("mmcblk")) return StorageRoot.Kind.REMOVABLE
        }
        return null
    }

    /**
     * The kind a volume [description] names, against the system's own words for a USB
     * drive ([usbWords]) and an SD card ([sdWords]); null when it names neither, as a
     * volume label does.
     */
    fun fromDescription(description: String?, usbWords: String?, sdWords: String?): StorageRoot.Kind? {
        val d = description?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        fun names(words: String?) = !words.isNullOrBlank() && d.contains(words.lowercase(Locale.ROOT))
        return when {
            names(usbWords) || d.contains("usb") -> StorageRoot.Kind.USB
            names(sdWords) || Regex("""\bsd\b""").containsMatchIn(d) -> StorageRoot.Kind.REMOVABLE
            else -> null
        }
    }
}
