package minty.emanata.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The merged manifest declares no Google Play services, ML Kit or Firebase component. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoPlayServicesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theManifestDeclaresNoGoogleComponent() {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val names = listOf(info.activities, info.services, info.providers, info.receivers).flatMap { it.orEmpty().map { c -> c.name } }
        assertEquals(emptyList<String>(), names.filter { it.startsWith("com.google.android.gms.") || it.startsWith("com.google.mlkit.") || it.startsWith("com.google.firebase.") || it.startsWith("com.google.android.datatransport.") })
        val meta = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        assertEquals(emptyList<String>(), meta?.keySet().orEmpty().filter { it.startsWith("com.google.") })
    }
}
