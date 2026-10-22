package com.infinity.comicreader.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DavXmlTest {

    private fun parse(xml: String) = DavXml.parse(xml.byteInputStream())

    @Test
    fun readsEachResponseFromItsSuccessfulPropertyGroupsOnly() {
        val entries = parse(
            """<?xml version="1.0"?>
            <multistatus xmlns="DAV:" xmlns:x="urn:example">
              <response>
                <href>https://nas.local/dav/</href>
                <propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat>
                <propstat><prop><getcontentlength/></prop><status>HTTP/1.1 404 Not Found</status></propstat>
              </response>
              <response>
                <href>/dav/a%20b.cbz</href>
                <propstat>
                  <prop>
                    <resourcetype/>
                    <getcontentlength>1234</getcontentlength>
                    <getlastmodified>Tue, 14 Nov 2023 22:13:20 GMT</getlastmodified>
                    <creationdate>2023-11-14T22:13:19Z</creationdate>
                    <x:status>ignored</x:status>
                  </prop>
                  <status>HTTP/1.1 200 OK</status>
                </propstat>
              </response>
              <response><href>/dav/gone.cbz</href><status>HTTP/1.1 404 Not Found</status></response>
            </multistatus>"""
        )
        assertEquals(
            listOf(
                DavXml.Entry("https://nas.local/dav/", true, -1, 0, 0),
                DavXml.Entry("/dav/a%20b.cbz", false, 1234, 1_700_000_000_000L, 1_699_999_999_000L)
            ),
            entries
        )
    }

    @Test
    fun prefixesDoNotMatterAndQuotasRead() {
        val entries = parse(
            """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/</D:href><D:propstat><D:prop>
            <D:quota-available-bytes>100</D:quota-available-bytes><D:quota-used-bytes>50</D:quota-used-bytes>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""
        )
        assertEquals(100L, entries.single().quotaAvailable)
        assertEquals(50L, entries.single().quotaUsed)
    }

    @Test
    fun externalEntitiesAreNeverFetched() {
        val entries = runCatching {
            parse(
                """<?xml version="1.0"?><!DOCTYPE m [<!ENTITY e SYSTEM "file:///etc/hostname">]>
                <multistatus xmlns="DAV:"><response><href>/dav/&e;</href></response></multistatus>"""
            )
        }.getOrDefault(emptyList())
        assertTrue(entries.all { it.href == "/dav/" })
    }

    @Test
    fun datesInEitherForm() {
        assertEquals(1_700_000_000_000L, DavXml.millis("Tue, 14 Nov 2023 22:13:20 GMT"))
        assertEquals(1_700_000_000_000L, DavXml.millis("2023-11-14T23:13:20+01:00"))
        assertEquals(0L, DavXml.millis("yesterday"))
        assertEquals(0L, DavXml.millis(""))
    }
}
