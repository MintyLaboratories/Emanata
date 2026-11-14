package com.infinity.comicreader.ai

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.SmartCrop
import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers find their text with PP-OCR; its model's arrivals make covers on screen out of date. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverTextDetectorsTest {
    private val ppocr = object : CoverTextDetector {
        override val id = "ppocr"
        override fun lines(src: Bitmap): List<SmartCrop.TextBox>? = emptyList()
    }

    @After
    fun tearDown() = CoverTextDetectors.standIn(null)

    @Test
    fun coversUsePpOcr() {
        assertSame(ppocr, CoverTextDetectors { ppocr }.detector())
    }

    @Test
    fun theAppsDetectorIsTheTextDetector() {
        CoverTextDetectors.standIn(null)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val d = CoverTextDetectors.shared(context)
        assertSame(d, CoverTextDetectors.shared(context))
        assertTrue(d.detector() is TextDetector)
        assertEquals(TextDetector.ID, d.detector().id)
    }

    @Test
    fun theAppsDetectorsHoldOnlyTheApplicationContext() {
        CoverTextDetectors.standIn(null)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val caller = ContextWrapper(app)
        val ppocr = CoverTextDetectors::class.java.getDeclaredField("ppocr")
            .apply { isAccessible = true }.get(CoverTextDetectors.shared(caller))!!
        val held = ppocr.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.isAccessible = true; it.get(ppocr) }
        assertFalse(held.any { it === caller })
        assertTrue(held.any { it === app })
    }

    @Test
    fun theModelArrivingIsAnArrivalAndAChange() {
        val d = CoverTextDetectors { ppocr }
        assertEquals(0, d.arrivals)
        assertEquals(0, d.changes.value)
        d.modelArrived()
        d.modelArrived()
        assertEquals(2, d.arrivals)
        assertEquals(2, d.changes.value)
    }
}
