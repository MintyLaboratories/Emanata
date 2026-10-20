package com.infinity.comicreader.data

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
}
