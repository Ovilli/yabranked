package dev.yabranked.backend.rating

/**
 * MCSR-style tiers: six metal tiers of 300 rating each, split into three
 * divisions of 100. Players still in placements are Unranked.
 */
enum class Tier(val floor: Int) {
    COAL(0),
    IRON(600),
    GOLD(900),
    EMERALD(1200),
    DIAMOND(1500),
    NETHERITE(1800);

    companion object {
        const val UNRANKED = "Unranked"

        fun format(rating: Int, isPlaced: Boolean): String {
            if (!isPlaced) return UNRANKED

            val tier = entries.last { rating >= it.floor }
            if (tier == NETHERITE) {
                // top tier is open-ended, no divisions
                return tier.displayName()
            }

            val next = entries[tier.ordinal + 1]
            val bandSize = (next.floor - tier.floor) / 3
            val division = ((rating - tier.floor) / bandSize).coerceIn(0, 2)
            val numeral = when (division) {
                0 -> "I"
                1 -> "II"
                else -> "III"
            }
            return "${tier.displayName()} $numeral"
        }

        private fun Tier.displayName(): String =
            name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
