package dev.yabranked.client.compat

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.yabranked.client.RankedOptionsScreen

/**
 * Points Mod Menu's "Configure" button at [RankedOptionsScreen].
 *
 * Reached only through the `modmenu` entrypoint in `fabric.mod.json`, which
 * nothing but Mod Menu ever enumerates — so with Mod Menu absent this class is
 * never loaded and its compile-only dependency is never missed.
 */
class ModMenuIntegration : ModMenuApi {

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> =
        ConfigScreenFactory { parent -> RankedOptionsScreen(parent) }
}
