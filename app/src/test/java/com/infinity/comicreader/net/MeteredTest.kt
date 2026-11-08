package com.infinity.comicreader.net

import com.infinity.comicreader.net.Metered.Link
import com.infinity.comicreader.net.Metered.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Optional network work is skipped on metered connections, VPNs over one included. */
class MeteredTest {

    private val wifi = Link(connected = true, notMetered = true, activeMetered = false)

    @After
    fun tearDown() {
        Metered.probe = { wifi }
        Metered.clock = { System.nanoTime() / 1_000_000 }
        Metered.forget()
    }

    @Test
    fun unmeteredWifiAllowsOptionalWork() {
        assertEquals(Verdict.UNMETERED, Metered.verdict(wifi))
    }

    @Test
    fun mobileDataIsMetered() {
        assertEquals(Verdict.METERED, Metered.verdict(Link(connected = true, notMetered = false, activeMetered = true, cellular = true)))
    }

    @Test
    fun aHotspotWithoutNotMeteredIsMetered() {
        assertEquals(Verdict.METERED, Metered.verdict(Link(connected = true, notMetered = false, activeMetered = false)))
    }

    @Test
    fun noNetworkIsOffline() {
        assertEquals(Verdict.OFFLINE, Metered.verdict(null))
        assertEquals(Verdict.OFFLINE, Metered.verdict(wifi.copy(connected = false)))
    }

    @Test
    fun vpnOverMeteredIsMetered() {
        // A VPN claiming NOT_METERED while Android calls the active network metered.
        assertEquals(Verdict.METERED, Metered.verdict(Link(connected = true, notMetered = true, activeMetered = true, vpn = true)))
        // One inheriting the mobile network's lack of NOT_METERED.
        assertEquals(Verdict.METERED, Metered.verdict(Link(connected = true, notMetered = false, activeMetered = false, vpn = true)))
        assertEquals(Verdict.UNMETERED, Metered.verdict(wifi.copy(vpn = true)))
    }

    @Test
    fun meteredOnlyWhenTheLinkSaysSo() {
        var link: Link? = wifi.copy(notMetered = false)
        var now = 0L
        Metered.probe = { link }
        Metered.clock = { now }
        Metered.forget()
        assertEquals(Verdict.METERED, Metered.now())
        link = null
        now += 2_000
        assertTrue(Metered.now() != Verdict.METERED)
        link = wifi
        now += 2_000
        assertTrue(Metered.now() != Verdict.METERED)
    }

    @Test
    fun answersAreKeptForASecond() {
        var asked = 0
        var now = 0L
        Metered.probe = { asked++; wifi }
        Metered.clock = { now }
        Metered.forget()
        Metered.now()
        now += 500
        Metered.now()
        assertEquals(1, asked)
        now += 600
        Metered.now()
        assertEquals(2, asked)
    }
}
