package dev.yabranked.client

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.util.Properties

/**
 * Tiny properties-file persistence for the client-side options. Kept flat and
 * dependency-free: the option set is small and the config dir is provided by
 * the loader, so there is no need for a real config framework.
 */
object Config {
    private val log = LoggerFactory.getLogger("yabranked-client")
    private val file = FabricLoader.getInstance().configDir.resolve("yabranked.properties").toFile()

    fun load() {
        if (!file.exists()) return
        try {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            RankedState.showFlags = p.getProperty("showFlags", "true").toBoolean()
            RankedState.hideOwnFlag = p.getProperty("hideOwnFlag", "false").toBoolean()
            RankedState.hideElo = p.getProperty("hideElo", "false").toBoolean()
            RankedState.hideOpponentElo = p.getProperty("hideOpponentElo", "false").toBoolean()
        } catch (e: Exception) {
            log.warn("failed to load options; using defaults", e)
        }
    }

    fun save() {
        try {
            val p = Properties()
            p.setProperty("showFlags", RankedState.showFlags.toString())
            p.setProperty("hideOwnFlag", RankedState.hideOwnFlag.toString())
            p.setProperty("hideElo", RankedState.hideElo.toString())
            p.setProperty("hideOpponentElo", RankedState.hideOpponentElo.toString())
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it, "YAB Ranked client options") }
        } catch (e: Exception) {
            log.warn("failed to save options", e)
        }
    }
}
