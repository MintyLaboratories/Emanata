package minty.emanata.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import minty.emanata.R
import minty.emanata.net.Remotes
import java.io.File

/**
 * The mounted storage volumes as [StorageRoot]s, in the order every list shows
 * them ([ordered]): the device's shared storage, SD cards, USB drives (readable
 * with all-files access, Android 11+), then network shares.
 */
object StorageRoots {
    fun list(context: Context): List<StorageRoot> {
        val out = ArrayList<StorageRoot>()
        out.add(
            StorageRoot(
                Environment.getExternalStorageDirectory().absolutePath,
                context.getString(R.string.root_device),
                StorageRoot.Kind.DEVICE
            )
        )
        val sm = context.getSystemService(StorageManager::class.java)
        val mounts by lazy { runCatching { File("/proc/self/mounts").readText() }.getOrDefault("") }
        fun root(dir: String, description: String?) =
            StorageRoot(dir, nameOf(description, dir), kindOf(dir, description, mounts))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm?.storageVolumes?.forEach { volume ->
                if (volume.isPrimary) return@forEach
                val state = volume.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val dir = volume.directory?.absolutePath ?: return@forEach
                out.add(root(dir, volume.getDescription(context)))
            }
        } else {
            // App-specific folders exist on every mounted volume: their volume is the part before /Android/.
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
                val dir = appDir.absolutePath.substringBefore("/Android/", "")
                if (dir.isEmpty()) return@forEach
                out.add(root(dir, sm?.getStorageVolume(File(dir))?.getDescription(context)))
            }
        }
        for (share in Remotes.shares()) out.add(StorageRoot(share.root, share.name, StorageRoot.Kind.SHARE))
        return ordered(out.distinctBy { it.path })
    }

    /** [roots] by kind (device, SD cards, USB drives, shares), keeping their order within each kind. */
    fun ordered(roots: List<StorageRoot>): List<StorageRoot> = roots.sortedBy { it.kind.ordinal }

    /**
     * Free and total bytes of [root]; null when it cannot be asked. A share is asked
     * over the network: blocking, and throwing when it cannot be reached.
     */
    fun space(root: StorageRoot): Pair<Long, Long>? {
        Remotes.idOf(root.path)?.let { return Remotes.space(it) }
        val dir = File(root.path)
        val total = runCatching { dir.totalSpace }.getOrDefault(0L)
        if (total <= 0) return null
        return runCatching { dir.usableSpace }.getOrDefault(0L) to total
    }

    private fun kindOf(dir: String, description: String?, mounts: String): StorageRoot.Kind =
        RemovableMedia.fromMounts(File(dir).name, mounts)
            ?: RemovableMedia.fromDescription(description, systemWords("storage_usb_drive"), systemWords("storage_sd_card"))
            ?: StorageRoot.Kind.REMOVABLE

    /** A string of the platform's own resources (how it names volumes), or null where it has none. */
    @SuppressLint("DiscouragedApi")
    private fun systemWords(name: String): String? = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier(name, "string", "android")
        if (id == 0) null else res.getString(id)
    }.getOrNull()

    private fun nameOf(description: String?, dir: String): String =
        description?.takeIf { it.isNotBlank() } ?: File(dir).name
}
