package dev.yabranked.client

/**
 * Guard for work that belongs to *opening* a screen rather than laying it out.
 *
 * Minecraft calls `Screen.init()` again on every window resize, on the same
 * instance. Anything in there that is not widget construction therefore
 * repeats: the open sound chirped once per frame of a window drag, and the
 * backend fetches fired again with it — a burst of HTTP requests for nothing,
 * since the data already in hand is still good.
 *
 * Hold one of these per screen and wrap that work in [once].
 */
class FirstInit {
    private var done = false

    fun once(block: () -> Unit) {
        if (done) return
        done = true
        block()
    }
}
