package com.infinity.comicreader.data

import com.infinity.comicreader.net.Remotes

/**
 * Where a path lies, as books remember where they were last seen: the device's own
 * storage ([DEVICE]), a removable volume (`volume:` and its ID, as `/storage/1234-ABCD`
 * names it) or a share (`share:` and the share's ID in the app). A location that cannot
 * be reached now tells "Forget missing books" to leave its books be.
 */
object BookLocation {
    const val DEVICE = "device"
    const val VOLUME_PREFIX = "volume:"
    const val SHARE_PREFIX = "share:"

    /** Folders under `/storage` that are not volumes of their own. */
    private val NOT_VOLUMES = setOf("emulated", "self")

    fun of(path: String): String {
        Remotes.idOf(path)?.let { return SHARE_PREFIX + it }
        val parts = path.split('/').filter { it.isNotEmpty() }
        val volume = when {
            parts.size >= 2 && parts[0] == "storage" && parts[1] !in NOT_VOLUMES -> parts[1]
            parts.size >= 3 && parts[0] == "mnt" && parts[1] == "media_rw" -> parts[2]
            else -> null
        }
        return if (volume != null) VOLUME_PREFIX + volume else DEVICE
    }
}
