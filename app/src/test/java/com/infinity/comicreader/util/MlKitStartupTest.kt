package com.infinity.comicreader.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MlKitStartupTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun mlKitDoesNotStartWithTheApp() {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES)
        val providers = info.providers.orEmpty().map { it.name }
        val services = info.services.orEmpty().map { it.name }
        // Declared by the same ML Kit library manifest as MlKitInitProvider: proves that manifest was merged.
        assertTrue("ML Kit's manifest not merged",
            "com.google.mlkit.common.internal.MlKitComponentDiscoveryService" in services)
        assertFalse("MlKitInitProvider registered", "com.google.mlkit.common.internal.MlKitInitProvider" in providers)
    }
}
