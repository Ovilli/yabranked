package dev.yabranked.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * Small palette of UI sounds, so the ranked screens feel tactile and consistent
 * (MCSR-style). All are variations on the vanilla button click at different
 * pitch/volume — cheap, always available, and they blend rather than clash.
 */
object Sfx {
    private fun ui(pitch: Float, volume: Float) {
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume)
        )
    }

    /** Soft, low "page in" when a ranked screen opens. */
    fun open() = ui(pitch = 0.7f, volume = 0.35f)

    /** Neutral confirm/select, e.g. opening a row's profile. */
    fun select() = ui(pitch = 1.15f, volume = 0.5f)

    /** Toggle flip: bright when turning on, dull when turning off. */
    fun toggle(on: Boolean) = ui(pitch = if (on) 1.45f else 0.85f, volume = 0.5f)

    /** Quiet, high tick for a discrete scroll step. */
    fun tick() = ui(pitch = 1.6f, volume = 0.18f)

    /** A brighter two-note-ish flourish for a saved change. */
    fun success() {
        ui(pitch = 1.2f, volume = 0.55f)
        ui(pitch = 1.6f, volume = 0.4f)
    }

    /** Lower-pitched cue for backing out / cancelling. */
    fun back() = ui(pitch = 0.85f, volume = 0.45f)
}
