package com.infinity.comicreader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsGridCoverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theGridCoverSizeIsKeptPerOrientation() {
        val prefs = Prefs(context)
        assertNull(prefs.gridCover(landscape = false))
        prefs.setGridCover(landscape = false, width = 200, height = 300)
        prefs.setGridCover(landscape = true, width = 240, height = 360)
        assertArrayEquals(intArrayOf(200, 300), Prefs(context).gridCover(landscape = false))
        assertArrayEquals(intArrayOf(240, 360), Prefs(context).gridCover(landscape = true))
    }

    @Test
    fun aGarbledSizeReadsAsNone() {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("grid_cover_portrait", "0x9").commit()
        assertNull(Prefs(context).gridCover(landscape = false))
    }
}
