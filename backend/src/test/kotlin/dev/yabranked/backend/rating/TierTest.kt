package dev.yabranked.backend.rating

import kotlin.test.Test
import kotlin.test.assertEquals

class TierTest {

    @Test
    fun `placements are unranked regardless of rating`() {
        assertEquals("Unranked", Tier.format(1500, isPlaced = false))
    }

    @Test
    fun `tier boundaries`() {
        assertEquals("Coal I", Tier.format(0, isPlaced = true))
        assertEquals("Coal III", Tier.format(599, isPlaced = true))
        assertEquals("Iron I", Tier.format(600, isPlaced = true))
        assertEquals("Iron III", Tier.format(899, isPlaced = true))
        assertEquals("Gold I", Tier.format(900, isPlaced = true))
        assertEquals("Gold II", Tier.format(1000, isPlaced = true))
        assertEquals("Emerald I", Tier.format(1200, isPlaced = true))
        assertEquals("Diamond III", Tier.format(1799, isPlaced = true))
        assertEquals("Netherite", Tier.format(1800, isPlaced = true))
        assertEquals("Netherite", Tier.format(3000, isPlaced = true))
    }
}
