package minty.emanata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemovableMediaTest {

    private val mounts = """
        /dev/block/dm-5 /data ext4 rw,seclabel,nosuid,nodev,noatime 0 0
        /dev/block/vold/public:8,1 /mnt/media_rw/1A2B-3C4D vfat rw,dirsync,nosuid,nodev 0 0
        /dev/fuse /storage/1A2B-3C4D fuse rw,lazytime,nosuid,nodev 0 0
        /dev/block/vold/public:179,129 /mnt/media_rw/5E6F-7A8B exfat rw,dirsync,nosuid,nodev 0 0
        /dev/block/sdc1 /mnt/media_rw/0000-1111 vfat rw 0 0
    """.trimIndent()

    @Test
    fun blockDeviceTellsUsbFromSd() {
        assertEquals(StorageRoot.Kind.USB, RemovableMedia.fromMounts("1A2B-3C4D", mounts))
        assertEquals(StorageRoot.Kind.REMOVABLE, RemovableMedia.fromMounts("5E6F-7A8B", mounts))
        assertEquals(StorageRoot.Kind.USB, RemovableMedia.fromMounts("0000-1111", mounts))
        assertNull(RemovableMedia.fromMounts("9999-0000", mounts))
        assertNull(RemovableMedia.fromMounts("", mounts))
        assertNull(RemovableMedia.fromMounts("1A2B-3C4D", ""))
    }

    @Test
    fun descriptionInTheSystemsWords() {
        assertEquals(StorageRoot.Kind.USB, RemovableMedia.fromDescription("Generic USB drive", "USB drive", "SD card"))
        assertEquals(StorageRoot.Kind.REMOVABLE, RemovableMedia.fromDescription("Brand SD card", "USB drive", "SD card"))
        assertEquals(StorageRoot.Kind.USB, RemovableMedia.fromDescription("Clé USB", "Clé USB", "Carte SD"))
        // A volume label names neither.
        assertNull(RemovableMedia.fromDescription("BOOKS", "USB drive", "SD card"))
        assertNull(RemovableMedia.fromDescription(null, "USB drive", "SD card"))
        assertNull(RemovableMedia.fromDescription("BOOKS", null, null))
    }
}
