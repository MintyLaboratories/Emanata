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
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
            .map { it.name }
        assertTrue("merged manifest not read", "com.infinity.comicreader.files.BookProvider" in providers)
        assertFalse("MlKitInitProvider registered", "com.google.mlkit.common.internal.MlKitInitProvider" in providers)
    }
}
