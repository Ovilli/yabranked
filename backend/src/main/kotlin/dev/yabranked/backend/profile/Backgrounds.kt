package dev.yabranked.backend.profile

/**
 * Profile-card background ids the client ships art for under
 * textures/gui/user_background. The stored id is interpolated into a texture
 * path client-side, so an unvalidated value is attacker-controlled path input;
 * only ids in this set are accepted.
 *
 * Mirrors `dev.yabranked.client.Backgrounds` — a new option needs the id here
 * and the PNG there.
 */
object Backgrounds {
    val ids: Set<String> = setOf(
        "default",
        "slate",
        "gold",
        "emerald",
        "diamond",
        "netherite",
        "crimson",
        "ocean",
        "ember",
        "aurora",
    )

    /** Canonical id for client input, or null when it is not a known background. */
    fun normalize(id: String): String? = id.lowercase().takeIf { it in ids }
}
