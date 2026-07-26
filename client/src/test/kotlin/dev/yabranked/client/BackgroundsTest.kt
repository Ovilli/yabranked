package dev.yabranked.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackgroundsTest {

    @Test
    fun `ids are the picker order, unique, and lead with the default`() {
        val ids = Backgrounds.ids

        // "default" is what the backend stores for an unset background, so the
        // picker showing it anywhere but first would leave a player unable to
        // tell "I chose this" from "I never chose".
        assertEquals("default", ids.first())
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: $ids")
        assertTrue(ids.all { it == it.lowercase() && it.isNotBlank() }, "ids must be lowercase: $ids")
    }

    @Test
    fun `label looks up the catalogue regardless of case`() {
        assertEquals("Netherite", Backgrounds.label("netherite"))
        // the backend echoes back whatever string was stored, which need not
        // match the catalogue's casing
        assertEquals("Netherite", Backgrounds.label("NETHERITE"))
        assertEquals("Netherite", Backgrounds.label("NeThErItE"))
    }

    @Test
    fun `an unknown id falls back to its own capitalised name`() {
        // a background added server-side before the client ships its art must
        // still read as a name, not as an empty row
        assertEquals("Sunset", Backgrounds.label("sunset"))
        assertEquals("", Backgrounds.label(""))
    }

    @Test
    fun `every offered id has a label and shipped art`() {
        for (id in Backgrounds.ids) {
            assertTrue(Backgrounds.label(id).isNotBlank(), "no label for $id")
            // Ui.drawUserBackground blits textures/gui/user_background/<id>.png;
            // offering an id with no PNG draws a broken banner.
            assertNotNull(
                javaClass.classLoader.getResource("assets/yabranked-client/textures/gui/user_background/$id.png"),
                "no texture shipped for background '$id'",
            )
        }
    }
}
