package dev.yabranked.client

/**
 * Catalogue of profile-card background ids that ship under
 * textures/gui/user_background. The backend stores the chosen id as a plain
 * string, so adding art here + a PNG is all a new option needs.
 */
object Backgrounds {
    /** id → display label, in picker order. */
    private val entries = linkedMapOf(
        "default" to "Default",
        "slate" to "Slate",
        "gold" to "Gold",
        "emerald" to "Emerald",
        "diamond" to "Diamond",
        "netherite" to "Netherite",
        "crimson" to "Crimson",
        "ocean" to "Ocean",
        "ember" to "Ember",
        "aurora" to "Aurora",
    )

    val ids: List<String> = entries.keys.toList()

    fun label(id: String): String = entries[id.lowercase()] ?: id.replaceFirstChar { it.uppercase() }
}
