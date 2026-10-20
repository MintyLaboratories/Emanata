package com.infinity.comicreader.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.infinity.comicreader.R
import java.io.File

/** The mounted storage volumes as [StorageRoot]s, the device's shared storage first. */
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm?.storageVolumes?.forEach { volume ->
                if (volume.isPrimary) return@forEach
                val state = volume.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val dir = volume.directory?.absolutePath ?: return@forEach
                out.add(StorageRoot(dir, nameOf(volume.getDescription(context), dir), StorageRoot.Kind.REMOVABLE))
            }
        } else {
            // App-specific folders exist on every mounted volume: their volume is the part before /Android/.
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
                val dir = appDir.absolutePath.substringBefore("/Android/", "")
                if (dir.isEmpty()) return@forEach
                val description = sm?.getStorageVolume(File(dir))?.getDescription(context)
                out.add(StorageRoot(dir, nameOf(description, dir), StorageRoot.Kind.REMOVABLE))
            }
        }
        return out.distinctBy { it.path }
    }

    private fun nameOf(description: String?, dir: String): String =
        description?.takeIf { it.isNotBlank() } ?: File(dir).name
}
