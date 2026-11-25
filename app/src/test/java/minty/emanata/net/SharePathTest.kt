package minty.emanata.net

import minty.emanata.net.SharePath.Parsed
import minty.emanata.net.SharePath.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

class SharePathTest {

    private fun valid(input: String) = (SharePath.parse(input) as Parsed.Valid).path

    private fun problem(input: String) = (SharePath.parse(input) as Parsed.Invalid).problem

    @Test
    fun hostShareAndFolder() {
        assertEquals(SharePath("nas.local", 445, "books", ""), valid("nas.local/books"))
        assertEquals(SharePath("nas.local", 445, "books", "comics/series"), valid("nas.local/books/comics/series/"))
        assertEquals(SharePath("192.0.2.10", 445, "books", "comics"), valid(" 192.0.2.10/books/comics "))
    }

    @Test
    fun windowsAndUrlSpellingsReadAlike() {
        val expected = SharePath("nas.local", 445, "books", "comics")
        assertEquals(expected, valid("""\\nas.local\books\comics"""))
        assertEquals(expected, valid("smb://nas.local/books/comics"))
        assertEquals(expected, valid("SMB://nas.local//books//comics"))
    }

    @Test
    fun portsAndIpv6() {
        assertEquals(SharePath("nas.local", 4450, "books", ""), valid("nas.local:4450/books"))
        assertEquals(SharePath("2001:db8::1", 445, "books", "a"), valid("[2001:db8::1]/books/a"))
        assertEquals(SharePath("2001:db8::1", 4450, "books", ""), valid("[2001:db8::1]:4450/books"))
    }

    @Test
    fun problems() {
        assertEquals(Problem.EMPTY, problem("  "))
        assertEquals(Problem.NO_SHARE, problem("nas.local"))
        assertEquals(Problem.NO_SHARE, problem("nas.local/"))
        assertEquals(Problem.BAD_HOST, problem("nas_local!/books"))
        assertEquals(Problem.BAD_HOST, problem("[2001:db8::1/books"))
        assertEquals(Problem.BAD_HOST, problem("a:b:c/books"))
        assertEquals(Problem.BAD_PORT, problem("nas.local:0/books"))
        assertEquals(Problem.BAD_PORT, problem("nas.local:99999/books"))
        assertEquals(Problem.BAD_PORT, problem("nas.local:port/books"))
        assertEquals(Problem.BAD_NAME, problem("nas.local/bo*oks"))
        assertEquals(Problem.BAD_NAME, problem("nas.local/books/../other"))
        assertEquals(Problem.BAD_NAME, problem("nas.local/books/a:b"))
    }

    @Test
    fun writtenBackAsTheFormTakesIt() {
        for (text in listOf("nas.local/books", "nas.local:4450/books/comics", "[2001:db8::1]/books")) {
            assertEquals(text, valid(text).toString())
            assertEquals(valid(text), valid(valid(text).toString()))
        }
    }
}
