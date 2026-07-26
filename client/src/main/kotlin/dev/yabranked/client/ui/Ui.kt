package dev.yabranked.client.ui

/**
 * Shared drawing helpers for the ranked screens: panels, dividers, stat widgets,
 * the rank crest / VS sprites and the rating chart.
 *
 * Kotlin cannot spread one object over several files, and every screen calls
 * these as `Ui.foo`. So the helpers live in the open-class chain
 * [UiPalette] → [UiPrimitives] → [UiIcons] → [UiDomain] and `Ui` is the single
 * object at the end of it — the source stays in files small enough to read
 * without any call site or import having to know about the split.
 */
object Ui : UiDomain() {

    /**
     * One sample on the rating chart: the rating after a match and when it was
     * played (epoch seconds, null if unknown).
     *
     * Has to be declared on the object itself: Kotlin does not resolve a
     * supertype's nested classes through the subclass, so moving it down into
     * [UiDomain] would break `Ui.ChartPoint` at every call site.
     */
    data class ChartPoint(val rating: Int, val at: Long?)
}
