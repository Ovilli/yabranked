package dev.yabranked.client.ui

/**
 * The whole colour vocabulary of the ranked screens, plus the colourblind swap.
 *
 * Root of the class chain behind [Ui] (see there for why the object is split
 * this way). Everything further up the chain reads these names unqualified, so
 * a hex literal anywhere else is by definition off-palette — and invisible to
 * the colourblind toggle.
 */
open class UiPalette {
    // --- Text palette: exactly three greyscale roles. Colour hues (WIN/LOSS/
    // ACCENT) are reserved for the win-rate bar, results, and the tier crest —
    // never mixed into body text. One role per line; no mid-string colour
    // switches. This restraint is what keeps the screens from reading noisy.
    val WHITE = -1                       // PRIMARY   — the one thing that matters on a line
    val TEXT_DIM = 0xFFA0A0A0.toInt()    // SECONDARY — supporting text
    val TEXT_FAINT = 0xFF707070.toInt()  // FAINT     — labels, units, timestamps
    val TEXT_SOFT = 0xFFD2D2D2.toInt()   // resting off-white; brightens to WHITE on hover

    val PANEL_BG = 0xD0080808.toInt()
    val PANEL_BORDER = 0xFF555555.toInt()
    // Flat chrome (replaces the old nine-slice plate textures).
    val ROW_BG = 0xB0141414.toInt()
    val SLOT_BG = 0xFF0C0C0C.toInt()
    val SLOT_BORDER = 0xFF3A3A3A.toInt()

    /** Empty half of a ratio/progress bar, and the scrollbar gutter. */
    val TRACK = 0xFF1C1C1C.toInt()

    /** Filled half of a scrollbar. */
    val THUMB = 0xFF6A6A6A.toInt()

    /** Placeholder block standing in for content that has not loaded yet. */
    val SKELETON = 0xFF2A2A2A.toInt()

    // Button plates sit lighter than the near-black cards so they read as
    // interactive instead of blending into the panels.
    val BUTTON_BG = 0xFF343434.toInt()
    val BUTTON_BG_LIT = 0xFF4A4A4A.toInt()
    val BUTTON_BG_DISABLED = 0xFF262626.toInt()
    val BUTTON_BORDER = 0xFF5A5A5A.toInt()

    /** When true, win/loss use a blue/orange pair legible with red-green colour
     *  blindness instead of the default green/red. Driven by the options toggle. */
    var colorblindPalette = false

    val WIN: Int get() = if (colorblindPalette) 0xFF4C9BE8.toInt() else 0xFF5BD97A.toInt()
    val LOSS: Int get() = if (colorblindPalette) 0xFFE8913B.toInt() else 0xFFE05C5C.toInt()
    val DRAW = 0xFF9A9A9A.toInt()
    val ACCENT = 0xFFFFC93C.toInt()

    /** Wash over the row under the cursor. */
    val HOVER = alpha(WHITE, 0x1A)

    /** Wash over the row that is currently chosen — accent-tinted so it stays
     *  tellable from [HOVER] when the cursor happens to sit on the selection. */
    val SELECTION = alpha(ACCENT, 0x2B)

    /**
     * [color] re-alpha'd to [a] (0..255).
     *
     * Translucent overlays derive their colour this way rather than inlining a
     * hex value, so retinting a palette entry carries through to every wash and
     * glow built on it instead of leaving a stale literal behind.
     */
    fun alpha(color: Int, a: Int): Int = (a shl 24) or (color and 0x00FFFFFF)

    /**
     * One colour per side of a match, for anything that draws several sides at
     * once — the replay board and its movement tracks.
     *
     * Not WIN/LOSS: those mean "your result", and a replay is watched from
     * outside the match as often as from inside it. Hues are spread far enough
     * apart to stay tellable, and the viewer always labels tracks with the
     * player's name as well, because colour alone is never the only cue.
     */
    fun sideColor(index: Int): Int = SIDE_COLORS[index.coerceAtLeast(0) % SIDE_COLORS.size]

    private val SIDE_COLORS = intArrayOf(
        0xFFFFC93C.toInt(), // gold
        0xFF3FD0D8.toInt(), // cyan
        0xFFB07CE8.toInt(), // violet
        0xFFE8913B.toInt(), // amber
    )

    /** Tier colours, matching the metal each tier is named after. */
    fun tierColor(tier: String): Int = when (tier.substringBefore(' ')) {
        "Coal" -> 0xFF4A4A4A.toInt()
        "Iron" -> 0xFFD8D8D8.toInt()
        "Gold" -> 0xFFFFC93C.toInt()
        "Emerald" -> 0xFF2ECC71.toInt()
        "Diamond" -> 0xFF3FD0D8.toInt()
        "Netherite" -> 0xFF6B6070.toInt()
        else -> TEXT_DIM
    }

    /** [color] scaled towards black by [factor], alpha untouched. */
    protected fun darken(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * factor
        val gg = ((color ushr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or (r.toInt() shl 16) or (gg.toInt() shl 8) or b.toInt()
    }
}
