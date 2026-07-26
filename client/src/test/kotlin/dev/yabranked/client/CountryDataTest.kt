package dev.yabranked.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CountryDataTest {

    /**
     * Codes we offer in the picker but ship no flag PNG for. Anything listed
     * here draws a broken flag for anyone who picks it — the entry is a bug
     * receipt, not a permission, and the fix is to add the art (or drop the
     * code), then delete the line.
     */
    private val knownMissingFlags = setOf("zw")

    @Test
    fun `codes are unique, lowercase, two-letter`() {
        val codes = CountryData.codes

        assertTrue(codes.isNotEmpty())
        assertEquals(codes.size, codes.toSet().size, "duplicate codes offered by the picker")
        val malformed = codes.filter { it.length != 2 || it != it.lowercase() }
        assertTrue(malformed.isEmpty(), "flag lookups lowercase the code and expect 2 letters: $malformed")
    }

    @Test
    fun `an empty or blank query offers everything`() {
        // the picker opens with an empty search box; it must not start empty
        assertEquals(CountryData.codes, CountryData.search(""))
        assertEquals(CountryData.codes, CountryData.search("   "))
    }

    @Test
    fun `search matches the code itself`() {
        assertTrue("de" in CountryData.search("de"))
        assertTrue("gb" in CountryData.search("gb"))
        // codes with no friendly name are reachable by their two letters alone
        assertTrue("gs" in CountryData.search("gs"))
    }

    @Test
    fun `search matches the display name, case-insensitively and trimmed`() {
        assertTrue("de" in CountryData.search("Germany"))
        assertTrue("de" in CountryData.search("GERMANY"))
        assertTrue("de" in CountryData.search("  germany  "))
        // partial words work, which is what makes type-ahead usable
        assertTrue("de" in CountryData.search("germ"))
    }

    @Test
    fun `search is a substring match, so it also hits names containing the query`() {
        val hits = CountryData.search("united")

        assertTrue("gb" in hits, "United Kingdom")
        assertTrue("us" in hits, "United States")
        assertTrue("ae" in hits, "United Arab Emirates")

        // The flip side of substring matching: "us" finds Australia as well as
        // the US. Deliberate — narrowing to prefixes would lose "…of America"
        // style matches, and the list stays short enough to scan.
        assertTrue("au" in CountryData.search("us"))
    }

    @Test
    fun `search keeps the A to Z catalogue order and never invents codes`() {
        val hits = CountryData.search("a")

        assertTrue(hits.isNotEmpty())
        assertTrue(CountryData.codes.containsAll(hits), "search returned a code not in the catalogue")
        assertEquals(CountryData.codes.filter { it in hits }, hits, "search reordered the list")
    }

    @Test
    fun `a query nothing matches yields an empty list`() {
        // an empty result is a legitimate state for the picker to render; it
        // must not fall back to "everything", which reads as "search is broken"
        assertEquals(emptyList(), CountryData.search("zzzzz"))
    }

    @Test
    fun `name resolves known codes case-insensitively`() {
        assertEquals("Germany", CountryData.name("de"))
        assertEquals("Germany", CountryData.name("DE"))
        assertEquals("United States", CountryData.name("us"))
    }

    @Test
    fun `an unnamed code falls back to its upper-cased self`() {
        // every shipped flag is selectable; only the common ones have a name
        assertEquals("GS", CountryData.name("gs"))
        assertEquals("ZZ", CountryData.name("zz"))
        assertEquals("", CountryData.name(""))
    }

    @Test
    fun `every selectable code has a flag texture shipped`() {
        val missing = CountryData.codes.filter {
            javaClass.classLoader.getResource("assets/yabranked-client/textures/flags/$it.png") == null
        }

        assertEquals(
            emptyList(),
            missing - knownMissingFlags,
            "the picker offers codes with no flag art",
        )
    }

    @Test
    fun `every named code is one the picker can actually offer`() {
        // a name for a code absent from [codes] is dead weight nobody can reach
        for (code in listOf("de", "us", "jp", "br")) {
            assertTrue(code in CountryData.codes, "$code is named but not selectable")
            assertNotNull(CountryData.name(code))
        }
    }
}
