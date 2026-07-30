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
        // No '=', a non-numeric local port, an empty target, a non-numeric port,
        // and a port out of range.
        val map = BackendConfig.parsePortMap("nonsense,abc=host:1,25602=,25604=host:abc,25605=host:99999,25603=host:3")
        assertEquals(mapOf(25603 to "host:3"), map)
    }

    @Test
    fun `a bare hostname is accepted, because SRV supplies the port`() {
        // playit.gg's "Minecraft Java" tunnel publishes an SRV record and the
        // client connects without a port. Requiring a colon dropped these
        // silently — a config value wrong in the quietest possible way.
        assertEquals(
            mapOf(25600 to "abc.playit.gg", 25601 to "def.playit.gg:45124"),
            BackendConfig.parsePortMap("25600=abc.playit.gg,25601=def.playit.gg:45124"),
        )
    }
}
