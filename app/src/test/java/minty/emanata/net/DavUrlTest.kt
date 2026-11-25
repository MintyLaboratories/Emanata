package minty.emanata.net

import minty.emanata.net.DavUrl.Parsed
import minty.emanata.net.SharePath.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavUrlTest {

    private fun valid(input: String) = (DavUrl.parse(input) as Parsed.Valid).url

    private fun problem(input: String) = (DavUrl.parse(input) as Parsed.Invalid).problem

    @Test
    fun hostAndFolderOverHttps() {
        assertEquals(DavUrl(true, "nas.local", 443, "dav/comics"), valid("https://nas.local/dav/comics/"))
        assertEquals(DavUrl(true, "nas.local", 443, ""), valid("https://nas.local"))
        assertEquals(DavUrl(true, "nas.local", 443, ""), valid("HTTPS://nas.local/"))
    }

    @Test
    fun davsAndABareHostMeanHttps() {
        val expected = DavUrl(true, "nas.local", 443, "comics")
        assertEquals(expected, valid("davs://nas.local/comics"))
        assertEquals(expected, valid(" nas.local/comics "))
        assertEquals(expected, valid("nas.local//comics?x=1#top"))
    }

    @Test
    fun portsAndIpv6() {
        assertEquals(DavUrl(true, "nas.local", 8443, "a"), valid("https://nas.local:8443/a"))
        assertEquals(DavUrl(true, "2001:db8::1", 443, "a"), valid("https://[2001:db8::1]/a"))
        assertEquals(DavUrl(true, "2001:db8::1", 8443, ""), valid("https://[2001:db8::1]:8443"))
    }

    @Test
    fun escapesAreDecodedAndKept() {
        assertEquals("My Comics/Vol 1", valid("https://nas.local/My%20Comics/Vol 1").dir)
        assertEquals("caf\u00e9", valid("https://nas.local/caf%C3%A9").dir)
        assertEquals("100%", valid("https://nas.local/100%").dir)
        assertEquals("a:b", valid("https://nas.local/a:b").dir)
    }

    @Test
    fun plainHttpOnlyToPrivateAddresses() {
        assertEquals(DavUrl(false, "localhost", 8080, "dav"), valid("http://localhost:8080/dav"))
        assertEquals(DavUrl(false, "127.0.0.1", 80, ""), valid("dav://127.0.0.1"))
        assertEquals(DavUrl(false, "nas.local", 80, "dav"), valid("http://nas.local/dav"))
        for (host in listOf("10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.200.7", "169.254.7.7", "[fe80::1]")) {
            assertEquals(host, false, valid("http://$host/dav").secure)
        }
        for (host in listOf("192.0.2.10", "172.32.0.1", "172.15.0.1", "8.8.8.8", "nas.example", "nas", "192.168.200", "[2001:db8::1]", "local",
            "010.0.0.1", "10.01.0.1", "0172.16.0.1", "0x0a.0.0.1")) {
            assertEquals(host, Problem.INSECURE, problem("http://$host/dav"))
        }
        assertTrue(DavUrl.isLoopback("LOCALHOST"))
        assertFalse(DavUrl.isLoopback("127.0.0.1.example"))
        assertFalse(DavUrl.isPrivate("192.168.200.7.example"))
        assertTrue(DavUrl.isPrivate("NAS.Local."))
        assertFalse("digits of other scripts", DavUrl.isPrivate("\u0661\u0660.0.0.1"))
    }

    @Test
    fun theNetworkSecurityConfigLeavesPlainHttpToTheApp() {
        val xml = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("""<base-config cleartextTrafficPermitted="true" />"""))
        assertTrue(java.io.File("src/main/AndroidManifest.xml").readText().contains("@xml/network_security_config"))
    }

    @Test
    fun problems() {
        assertEquals(Problem.EMPTY, problem("  "))
        assertEquals(Problem.BAD_HOST, problem("https://nas_local!/dav"))
        assertEquals(Problem.BAD_HOST, problem("https://user@nas.local/dav"))
        assertEquals(Problem.BAD_HOST, problem("https://user:pw@nas.local/dav"))
        assertEquals(Problem.BAD_HOST, problem("smb://nas.local/books"))
        assertEquals(Problem.BAD_HOST, problem("""\\nas.local\books"""))
        assertEquals(Problem.BAD_HOST, problem("https:///dav"))
        assertEquals(Problem.BAD_PORT, problem("https://nas.local:0/dav"))
        assertEquals(Problem.BAD_PORT, problem("https://nas.local:https/dav"))
        assertEquals(Problem.BAD_NAME, problem("https://nas.local/a/../b"))
        assertEquals(Problem.BAD_NAME, problem("https://nas.local/a%2Fb"))
        assertEquals(Problem.BAD_NAME, problem("https://nas.local/%FF"))
    }

    @Test
    fun writtenBackAsTheFormTakesIt() {
        for (text in listOf(
            "https://nas.local/", "https://nas.local:8443/dav/comics", "https://[2001:db8::1]/a",
            "http://localhost:8080/dav", "https://nas.local/100%25/a%3Fb"
        )) {
            assertEquals(text, valid(text).toString())
            assertEquals(valid(text), valid(valid(text).toString()))
        }
    }

    @Test
    fun foldersMayHoldAnAt() {
        val url = valid("https://cloud.example.com/remote.php/dav/files/user%40example.com/")
        assertEquals("remote.php/dav/files/user@example.com", url.dir)
        assertEquals("https://cloud.example.com/remote.php/dav/files/user@example.com", url.toString())
        assertEquals(url, valid(url.toString()))
        assertEquals(url, valid("https://cloud.example.com/remote.php/dav/files/user@example.com/"))
    }

    @Test
    fun anSmbPathRefusesAWebAddress() {
        assertEquals(SharePath.Parsed.Invalid(Problem.WEB_ADDRESS), SharePath.parse("https://nas.local/dav"))
        assertEquals(SharePath.Parsed.Invalid(Problem.WEB_ADDRESS), SharePath.parse(" davs://nas.local/dav"))
    }
}
