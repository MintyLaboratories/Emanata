package com.infinity.comicreader.compact

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pages convert on half the cores at most, fewer as the device warms up or saves power. */
class CompactWorkersTest {

    @Test
    fun halfTheCoresAtMostFour() {
        assertEquals(4, CompactWorkers.max(8))
        assertEquals(3, CompactWorkers.max(6))
        assertEquals(4, CompactWorkers.max(16))
        assertEquals(1, CompactWorkers.max(1))
    }

    @Test
    fun fewerAsTheDeviceWarmsOrSavesPower() {
        assertEquals(4, CompactWorkers.allowed(4, PowerManager.THERMAL_STATUS_NONE, powerSave = false))
        assertEquals(3, CompactWorkers.allowed(4, PowerManager.THERMAL_STATUS_LIGHT, powerSave = false))
        assertEquals(2, CompactWorkers.allowed(4, PowerManager.THERMAL_STATUS_MODERATE, powerSave = false))
        assertEquals(1, CompactWorkers.allowed(4, PowerManager.THERMAL_STATUS_SEVERE, powerSave = false))
        assertEquals(1, CompactWorkers.allowed(4, PowerManager.THERMAL_STATUS_NONE, powerSave = true))
        assertEquals(1, CompactWorkers.allowed(1, PowerManager.THERMAL_STATUS_LIGHT, powerSave = false))
    }
}
