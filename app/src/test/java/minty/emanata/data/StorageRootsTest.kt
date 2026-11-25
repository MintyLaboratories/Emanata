package minty.emanata.data

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class StorageRootsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun assertDeviceFirst() {
        val roots = StorageRoots.list(context)
        val first = roots.first()
        assertEquals(Environment.getExternalStorageDirectory().absolutePath, first.path)
        assertEquals("Device", first.name)
        assertEquals(StorageRoot.Kind.DEVICE, first.kind)
        assertEquals(roots.size, roots.distinctBy { it.path }.size)
    }

    @Test
    @Config(sdk = [29])
    fun deviceStorageComesFirstBeforeVolumeApi() = assertDeviceFirst()

    @Test
    @Config(sdk = [34])
    fun deviceStorageComesFirstWithVolumeApi() = assertDeviceFirst()

    @Test
    fun orderedByKind() {
        val share = StorageRoot("/shares/a", "Share A", StorageRoot.Kind.SHARE)
        val usb = StorageRoot("/storage/1A2B-3C4D", "Drive A", StorageRoot.Kind.USB)
        val card = StorageRoot("/storage/1234-ABCD", "Card", StorageRoot.Kind.REMOVABLE)
        val device = StorageRoot("/storage/emulated/0", "Device", StorageRoot.Kind.DEVICE)
        assertEquals(listOf(device, card, usb, share), StorageRoots.ordered(listOf(share, usb, card, device)))
    }

    @Test
    fun spaceOfAnUnreachableRootIsUnknown() {
        assertEquals(null, StorageRoots.space(StorageRoot("/no/such/root", "Gone", StorageRoot.Kind.USB)))
    }
}
