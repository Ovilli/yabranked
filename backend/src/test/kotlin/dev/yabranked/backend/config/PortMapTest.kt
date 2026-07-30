package dev.yabranked.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `YABRANKED_PORT_MAP` parsing.
 *
 * The failure that matters is a *wrong* mapping rather than a missing one: a port
 * pointed at the wrong address sends players to something nobody is listening on,
 * and they see a connection timeout with no clue why. So anything malformed is
 * dropped, and the match simply fails to provision.
 */
class PortMapTest {

    @Test
    fun `parses local to public pairs`() {
        val map = BackendConfig.parsePortMap("25600=abc.playit.gg:45123,25601=abc.playit.gg:45124")
        assertEquals(2, map.size)
        assertEquals("abc.playit.gg:45123", map[25600])
        assertEquals("abc.playit.gg:45124", map[25601])
    }

    @Test
    fun `tolerates spacing`() {
        val map = BackendConfig.parsePortMap(" 25600 = host:1 , 25601 = host:2 ")
        assertEquals(mapOf(25600 to "host:1", 25601 to "host:2"), map)
    }

    @Test
    fun `unset means no mapping, which is the ordinary case`() {
        assertTrue(BackendConfig.parsePortMap(null).isEmpty())
        assertTrue(BackendConfig.parsePortMap("").isEmpty())
    }

    @Test
    fun `malformed entries are dropped rather than guessed at`() {
        // No port on the target, no '=', a non-numeric local port, an empty target.
        val map = BackendConfig.parsePortMap("25600=hostwithoutport,nonsense,abc=host:1,25602=,25603=host:3")
        assertEquals(mapOf(25603 to "host:3"), map)
    }
}
