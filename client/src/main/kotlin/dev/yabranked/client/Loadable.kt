package dev.yabranked.client

/**
 * What a screen has to draw for one backend read.
 *
 * Every screen used to hand-roll this out of two fields — a nullable value plus
 * a nullable error string — and each invented its own reading of the pair. The
 * expensive part was that "loaded, and there is nothing" looks exactly like
 * "still loading" when the only evidence is `null`/`isEmpty()`, so an empty
 * ladder sat under a spinner that never stopped. Naming the three states makes
 * that case impossible to forget: [Loaded] with an empty list is a finished
 * read, and [placeholder] insists on the copy for it.
 */
sealed interface Loadable<out T> {
    /** The loaded value; null while loading and after a failure. */
    val valueOrNull: T?

    /** Either state with nothing to draw yet. [message] is the line to show. */
    sealed interface Pending : Loadable<Nothing> {
        val message: String
        override val valueOrNull: Nothing? get() = null
    }

    data object Loading : Pending {
        override val message = "Loading…"
    }

    data class Failed(override val message: String) : Pending

    data class Loaded<out T>(val value: T) : Loadable<T> {
        override val valueOrNull: T get() = value
    }
}

/**
 * The finished [BackendClient.Fetch] as the state a screen renders.
 *
 * The only place a fetch failure turns into UI copy, so offline / expired
 * session / outdated mod read the same on every screen instead of once per
 * hand-written `when`.
 */
fun <T> BackendClient.Fetch<T>.toLoadable(): Loadable<T> = when (this) {
    is BackendClient.Fetch.Ok -> Loadable.Loaded(value)
    is BackendClient.Fetch.Error -> Loadable.Failed(message)
}

/**
 * The line to draw instead of a list, or null once there are rows to draw.
 *
 * [empty] is the copy for a read that came back with nothing in it — a state
 * this insists on naming, since it is the one the old `null`/`isEmpty()` pairs
 * kept mistaking for "still loading". Single-value reads match on [Loadable]
 * directly; they have no empty state to confuse.
 */
fun Loadable<List<*>>.placeholder(empty: String): String? = when (this) {
    is Loadable.Pending -> message
    is Loadable.Loaded -> empty.takeIf { value.isEmpty() }
}
