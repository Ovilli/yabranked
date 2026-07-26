package dev.yabranked.client.ui

import dev.yabranked.proto.PlayerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MC-free half of [Ui]: the text and number helpers every screen formats
 * through. Nothing here touches GuiGraphics, so it runs without a game.
 */
class UiFormatTest {

    private fun profile(wins: Int, losses: Int, draws: Int) = PlayerProfile(
        uuid = "u",
        name = "Player",
        rating = 1000,
        placementMatchesRemaining = 0,
        wins = wins,
        losses = losses,
        draws = draws,
    )

    @Test
    fun `duration pads the seconds and dashes when unknown`() {
        assertEquals("—", Ui.duration(null), "an unfinished match must not read as 0:00")
        assertEquals("0:00", Ui.duration(0))
        assertEquals("0:09", Ui.duration(9))
        assertEquals("1:05", Ui.duration(65))
        assertEquals("10:00", Ui.duration(600))
    }

    @Test
    fun `duration keeps counting in minutes past an hour`() {
        // it labels match clocks, which are minutes long; rolling into hours
        // here would make the HUD timer jump from 59:59 to 1:00
        assertEquals("60:00", Ui.duration(3600))
        assertEquals("125:30", Ui.duration(7530))
    }

    @Test
    fun `durationLong drops to the largest unit that has a value`() {
        assertEquals("18s", Ui.durationLong(18))
        assertEquals("42m", Ui.durationLong(42 * 60))
        assertEquals("3h 12m", Ui.durationLong(3 * 3600 + 12 * 60))
        assertEquals("1h 0m", Ui.durationLong(3600))
        assertEquals("1m", Ui.durationLong(119), "seconds are dropped once minutes show")
    }

    @Test
    fun `durationLong treats nothing and nonsense alike`() {
        // total playtime is 0 for a new account and null when the backend is old
        assertEquals("—", Ui.durationLong(null))
        assertEquals("—", Ui.durationLong(0))
        assertEquals("—", Ui.durationLong(-5))
    }

    @Test
    fun `relativeTime buckets an age from epoch seconds`() {
        val now = System.currentTimeMillis() / 1000

        assertEquals("just now", Ui.relativeTime(now - 30))
        assertEquals("5m ago", Ui.relativeTime(now - 5 * 60))
        assertEquals("3h ago", Ui.relativeTime(now - 3 * 3600))
        assertEquals("2d ago", Ui.relativeTime(now - 2 * 86_400))
        assertEquals("4w ago", Ui.relativeTime(now - 4 * 604_800))
    }

    @Test
    fun `relativeTime is blank for an unknown timestamp`() {
        // blank, not "—": callers concatenate it into a longer line and skip it
        // when empty
        assertEquals("", Ui.relativeTime(null))
        assertEquals("", Ui.relativeTime(0))
    }

    @Test
    fun `a clock ahead of the server reads as just now, not a huge age`() {
        val future = System.currentTimeMillis() / 1000 + 3600

        assertEquals("just now", Ui.relativeTime(future))
    }

    @Test
    fun `flagHeight keeps the sprite aspect and never collapses to nothing`() {
        // sampling a square region drew only the top stripe, hence the 128x85
        // ratio being computed rather than assumed
        assertEquals(85, Ui.flagHeight(128))
        assertEquals(170, Ui.flagHeight(256))
        assertTrue(Ui.flagHeight(9) in 5..6, "9px wide flag was ${Ui.flagHeight(9)}px tall")
        assertEquals(1, Ui.flagHeight(1), "a 0-tall blit would draw nothing at all")
        assertEquals(1, Ui.flagHeight(0))
    }

    @Test
    fun `winRatePercent ignores draws and rounds down`() {
        assertEquals(50, Ui.winRatePercent(profile(wins = 5, losses = 5, draws = 0)))
        // draws are neither won nor lost, so they must not dilute the rate
        assertEquals(50, Ui.winRatePercent(profile(wins = 5, losses = 5, draws = 10)))
        assertEquals(33, Ui.winRatePercent(profile(wins = 1, losses = 2, draws = 0)))
        assertEquals(100, Ui.winRatePercent(profile(wins = 3, losses = 0, draws = 0)))
    }

    @Test
    fun `a player with no decided games is zero percent, not a division by zero`() {
        assertEquals(0, Ui.winRatePercent(profile(wins = 0, losses = 0, draws = 0)))
        assertEquals(0, Ui.winRatePercent(profile(wins = 0, losses = 0, draws = 4)))
    }

    @Test
    fun `tierColor reads the tier and ignores the division`() {
        // the backend sends "Gold II"; all three divisions share one crest colour
        assertEquals(Ui.tierColor("Gold"), Ui.tierColor("Gold II"))
        assertEquals(Ui.tierColor("Netherite"), Ui.tierColor("Netherite"))
    }

    @Test
    fun `every named tier gets its own colour`() {
        val tiers = listOf("Coal", "Iron", "Gold", "Emerald", "Diamond", "Netherite")
        val colors = tiers.map { Ui.tierColor(it) }

        assertEquals(tiers.size, colors.toSet().size, "two tiers share a colour: $colors")
        assertTrue(colors.none { it == Ui.TEXT_DIM }, "a real tier fell through to the unknown colour")
        assertTrue(colors.all { (it ushr 24) and 0xFF == 0xFF }, "tier colours must be fully opaque: $colors")
    }

    @Test
    fun `an unknown or unranked tier falls back to dim text`() {
        assertEquals(Ui.TEXT_DIM, Ui.tierColor("Unranked"))
        assertEquals(Ui.TEXT_DIM, Ui.tierColor(""))
        // the lookup is case-sensitive on purpose — it matches what Tier.format
        // emits, and a stray "gold" is a bug worth seeing rather than papering over
        assertEquals(Ui.TEXT_DIM, Ui.tierColor("gold"))
    }

    @Test
    fun `the colourblind palette swaps win and loss for a distinguishable pair`() {
        val defaultWin = Ui.WIN
        val defaultLoss = Ui.LOSS
        try {
            Ui.colorblindPalette = true
            assertTrue(Ui.WIN != defaultWin, "the toggle did not change the win colour")
            assertTrue(Ui.LOSS != defaultLoss, "the toggle did not change the loss colour")
            assertTrue(Ui.WIN != Ui.LOSS, "win and loss must stay tellable apart")
            // red/green blindness is the case being served: neither replacement
            // may be the red or green it replaced
            assertTrue(Ui.WIN != defaultLoss && Ui.LOSS != defaultWin)
        } finally {
            Ui.colorblindPalette = false
        }

        assertEquals(defaultWin, Ui.WIN, "turning the toggle off must restore the default palette")
        assertEquals(defaultLoss, Ui.LOSS)
    }
}
