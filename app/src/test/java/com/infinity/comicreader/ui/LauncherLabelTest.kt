package com.infinity.comicreader.ui

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherLabelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun launcherShowsTheShortName() {
        val pm = context.packageManager
        val launcher = pm.getActivityInfo(ComponentName(context, ListActivity::class.java), 0)
        assertEquals(context.getString(R.string.launcher_name), launcher.loadLabel(pm).toString())
    }

    @Test
    fun appKeepsTheFullName() {
        assertEquals(context.getString(R.string.app_name), context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
