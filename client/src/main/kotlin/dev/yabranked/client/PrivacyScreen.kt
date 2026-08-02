package dev.yabranked.client

import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.PrivacySettings
import dev.yabranked.proto.Visibility
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Who may see what on your profile.
 *
 * Each row is one field with three settings — everyone, friends only, nobody —
 * plus the two social switches that decide whether strangers can reach you at
 * all. Nothing here is applied locally: the server owns these, redacts on its
 * side, and the screen redraws from the profile it answers with, so what you
 * see is exactly what it will enforce.
 */
class PrivacyScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("Privacy")) {

    private var settings: PrivacySettings = RankedState.profile?.privacy ?: PrivacySettings()
    private var saving = false
    private var notice: String? = null
    private var openedAt = 0L

    /** Y of the first visibility row; set during [init] so the hover-hint
     *  lookup reads the same grid the buttons were laid out on. */
    private var rowsTop = TOP

    private class Row(
        val label: String,
        val hint: String,
        val get: (PrivacySettings) -> Visibility,
        val set: (PrivacySettings, Visibility) -> PrivacySettings,
    )

    private val rows = listOf(
        Row("Country flag", "Your flag beside your name.",
            { it.showCountry }, { s, v -> s.copy(showCountry = v) }),
        Row("Rating", "Your exact MMR. Your tier is always shown.",
            { it.showRating }, { s, v -> s.copy(showRating = v) }),
        Row("Playtime & modes", "How long you have played, and which modes.",
            { it.showPlaytime }, { s, v -> s.copy(showPlaytime = v) }),
        Row("Match history", "Your recent matches and their results.",
            { it.showMatchHistory }, { s, v -> s.copy(showMatchHistory = v) }),
        Row("Achievements", "The milestones you have unlocked.",
            { it.showAchievements }, { s, v -> s.copy(showAchievements = v) }),
        Row("Endorsements", "Your endorsement level.",
            { it.showEndorsements }, { s, v -> s.copy(showEndorsements = v) }),
        Row("Win streak", "Your current streak, including on the versus screen.",
            { it.showStreak }, { s, v -> s.copy(showStreak = v) }),
        Row("Online status", "Whether you show as in menus, in queue or in a match.",
            { it.showOnlineStatus }, { s, v -> s.copy(showOnlineStatus = v) }),
    )

    override fun layout() {
        val centerX = width / 2
        // The visibility rows and the two social switches are one block. It sits
        // at TOP on a roomy window, but is lifted so its last row can never
        // reach the Done bar: at a 240px GUI height the switches landed on top
        // of Done, and the last widget added wins the click, so Done silently
        // ate both of them. Tucking the first row a few pixels under the header
        // plate is the cosmetic price of every row staying clickable.
        val blockHeight = rows.size * ROW + SWITCH_GAP + SWITCH_HEIGHT
        rowsTop = minOf(TOP, height - BOTTOM_BAR - blockHeight)
        var y = rowsTop
        val switchesY = rowsTop + rows.size * ROW + SWITCH_GAP
        rows.forEach { row ->
            addRenderableWidget(
                RankedButton(centerX - 120, y, 240, 18, rowLabel(row)) {
                    settings = row.set(settings, next(row.get(settings)))
                    Sfx.tick()
                    push()
                    rebuildWidgets()
                }
            )
            y += ROW
        }

        addRenderableWidget(
            RankedButton(centerX - 120, switchesY, 118, SWITCH_HEIGHT, Component.literal(
                if (settings.allowFriendRequests) "Friend requests: on" else "Friend requests: off"
            )) {
                settings = settings.copy(allowFriendRequests = !settings.allowFriendRequests)
                Sfx.toggle(settings.allowFriendRequests)
                push()
                rebuildWidgets()
            }
        )
        addRenderableWidget(
            RankedButton(centerX + 2, switchesY, 118, SWITCH_HEIGHT, Component.literal(inviteLabel())) {
                // One button, three states: off → friends only → anyone. Two
                // separate switches let a player pick "off but friends only",
                // which means nothing.
                settings = when {
                    !settings.allowPartyInvites ->
                        settings.copy(allowPartyInvites = true, partyInvitesFromFriendsOnly = true)
                    settings.partyInvitesFromFriendsOnly ->
                        settings.copy(partyInvitesFromFriendsOnly = false)
                    else -> settings.copy(allowPartyInvites = false)
                }
                Sfx.tick()
                push()
                rebuildWidgets()
            }
        )

        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Done"), Ui.ICON_BACK) { onClose() }
        )
    }

    private fun inviteLabel(): String = when {
        !settings.allowPartyInvites -> "Party invites: off"
        settings.partyInvitesFromFriendsOnly -> "Party invites: friends"
        else -> "Party invites: anyone"
    }

    private fun rowLabel(row: Row): Component =
        Component.literal("§f${row.label}: ${visibilityLabel(row.get(settings))}")

    private fun visibilityLabel(visibility: Visibility) = when (visibility) {
        Visibility.EVERYONE -> "§aEveryone"
        Visibility.FRIENDS -> "§eFriends"
        Visibility.NOBODY -> "§cNobody"
    }

    private fun next(visibility: Visibility) = when (visibility) {
        Visibility.EVERYONE -> Visibility.FRIENDS
        Visibility.FRIENDS -> Visibility.NOBODY
        Visibility.NOBODY -> Visibility.EVERYONE
    }

    /** Send the whole block; the server answers with what it actually stored. */
    private fun push() {
        val backend = RankedState.backend ?: run {
            notice = "Sign in to change your privacy settings"
            return
        }
        val wanted = settings
        saving = true
        YabRankedClient.workers.execute {
            val updated = backend.updateProfile(privacy = wanted)
            minecraft.execute {
                saving = false
                if (updated == null) {
                    notice = "§cCould not save — try again"
                    return@execute
                }
                notice = null
                RankedState.profile = updated
                settings = updated.privacy
                // The two legacy toggles on the options screen mirror these, so
                // they are kept in step rather than left showing stale state.
                RankedState.hideOwnFlag = updated.hideFlag
                RankedState.hideElo = updated.hideRating
                Config.save()
                rebuildWidgets()
            }
        }
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        val centerX = width / 2

        Ui.title(g, font, centerX, "§lPRIVACY")

        // Hint for whichever row the cursor is over, in a fixed strip so the
        // rows never shift as the mouse moves across them.
        val index = ((mouseY - rowsTop) / ROW).takeIf { mouseX >= centerX - 120 && mouseX <= centerX + 120 }
        val hint = rows.getOrNull(index ?: -1)?.hint
            ?: "Friends-only fields are visible to accepted friends and to you."
        g.centeredText(font, hint, centerX, height - 46, Ui.TEXT_FAINT)

        // One line, not two stacked on the same y: a failed save leaves [notice]
        // set, so a retry used to print "Saving…" straight over it.
        if (saving) g.centeredText(font, "§7Saving…", centerX, height - 58, Ui.TEXT_DIM)
        else notice?.let { g.centeredText(font, Ui.fit(font, it, width - 20), centerX, height - 58, Ui.ACCENT) }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val TOP = 38
        const val ROW = 20

        /** Height of the two social switches, and the gap above them. */
        const val SWITCH_HEIGHT = 18
        const val SWITCH_GAP = 4

        /** Space the Done bar owns at the bottom: it sits at `height - 28`, so
         *  nothing above it may pass `height - BOTTOM_BAR`. */
        const val BOTTOM_BAR = 32
    }
}
