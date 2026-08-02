package dev.yabranked.client

import dev.yabranked.proto.FriendListResponse
import net.minecraft.client.Minecraft

/**
 * Signing in, and signing back in.
 *
 * This used to live inside [RankedScreen] as a private method behind a button,
 * which was fine for the first sign-in and nothing else. A bearer token expires
 * on its own schedule, and when it did the client kept holding the dead session:
 * `RankedState.isAuthenticated` stayed true, so the ranked menu went on hiding
 * the very button that would have fixed it, and every screen said "Session
 * expired — sign in again" while offering no way to do so.
 *
 * Three things follow from having it here. The button is one caller,
 * [BackendClient.onUnauthorized] is another — a refused request now drops the
 * session and comes straight back here — and opening the ranked menu is the
 * third. Because the handshake needs nothing from the player (the account and
 * its access token are already in [Minecraft.getUser]), it can simply happen,
 * rather than being something the player has to be told to do: the session is
 * held in memory on both ends, so before this every game launch, every backend
 * restart and every token expiry made a player press a button whose only
 * possible answer was yes.
 */
object RankedAuth {

    private val log = org.slf4j.LoggerFactory.getLogger("yabranked-auth")

    /** True while a sign-in is in flight, so a burst of 401s makes one attempt. */
    @Volatile
    private var signingIn = false

    /**
     * Wall-clock of the last automatic attempt.
     *
     * A backend that answers 401 to everything — a revoked token, a restarted
     * instance that lost its registry — would otherwise have the client
     * re-authenticating once per poll, forever. The same counter paces
     * [ensureSignedIn], whose caller is a screen layout: `rebuildWidgets()` runs
     * it again on every state change, and an unreachable backend must not turn
     * that into a handshake per frame.
     */
    @Volatile
    private var lastAutoAttempt = 0L

    private const val AUTO_RETRY_COOLDOWN_MS = 30_000L

    /**
     * Sign in unless there is already a session or an attempt in flight, and
     * only if the last automatic one is old enough to be worth repeating.
     *
     * This is the ranked menu opening, not the player asking, so it is silent:
     * the Log in button is still there for a failure, and pressing it goes
     * straight to [signIn] and ignores the cooldown.
     */
    fun ensureSignedIn(minecraft: Minecraft, onDone: (BackendClient.AuthResult) -> Unit = {}) {
        if (RankedState.isAuthenticated || signingIn) return
        val now = System.currentTimeMillis()
        if (now - lastAutoAttempt < AUTO_RETRY_COOLDOWN_MS) return
        lastAutoAttempt = now
        signIn(minecraft, onDone)
    }

    /**
     * Authenticate and populate [RankedState]. Render thread.
     *
     * [onDone] runs on the render thread once the outcome is known, for a caller
     * that wants to refresh itself.
     */
    fun signIn(minecraft: Minecraft, onDone: (BackendClient.AuthResult) -> Unit = {}) {
        if (signingIn) return
        signingIn = true
        RankedState.statusMessage = "Signing in…"

        val user = minecraft.user
        val sessionService = minecraft.services().sessionService()
        val backend = BackendClient(YabRankedClient.backendUrl, YabRankedClient.modVersion)
        backend.onUnauthorized = { minecraft.execute { sessionExpired(minecraft, backend) } }

        YabRankedClient.workers.execute {
            val result = backend.authenticate(user.name) { serverId ->
                sessionService.joinServer(user.profileId, user.accessToken, serverId)
            }
            minecraft.execute {
                signingIn = false
                when (result) {
                    is BackendClient.AuthResult.Ok -> {
                        // A session that worked clears the cooldown: the next
                        // expiry is a fresh problem and should be answered at
                        // once, not held off because this attempt was recent.
                        lastAutoAttempt = 0L
                        RankedState.backend = backend
                        RankedState.profile = result.session.profile
                        // The server is authoritative for these two prefs; mirror
                        // them into the local toggles so the options screen shows
                        // the real state and doesn't clobber it on next save.
                        RankedState.hideOwnFlag = result.session.profile.hideFlag
                        RankedState.hideElo = result.session.profile.hideRating
                        RankedState.statusMessage = null
                        // The party socket is also the channel invites and friend
                        // requests arrive on, so it opens as soon as there is a
                        // session — not when a party is created.
                        RankedParty.connect()
                        loadOwnState(minecraft, backend, result)
                    }
                    is BackendClient.AuthResult.Outdated ->
                        RankedState.statusMessage = "§c${result.message}"
                    is BackendClient.AuthResult.Failed ->
                        RankedState.statusMessage = "§c${result.message}"
                }
                onDone(result)
            }
        }
    }

    /** Win streak and pending friend requests, both drawn on the ranked menu. */
    private fun loadOwnState(
        minecraft: Minecraft,
        backend: BackendClient,
        result: BackendClient.AuthResult.Ok,
    ) {
        val uuid = result.session.profile.uuid
        YabRankedClient.workers.execute {
            val hist = backend.fetchHistory(uuid, limit = 20).orElse(emptyList())
            val friends = backend.fetchFriends()
            minecraft.execute {
                RankedState.winStreak = result.session.profile.currentStreak
                    ?: RankedState.currentWinStreak(hist)
                RankedState.friendRequests = friends.orElse(FriendListResponse()).incoming.size
            }
        }
    }

    /**
     * A request came back 401. The session is already gone by the time this runs.
     *
     * Tries once to get a new one, quietly — the player did nothing wrong and
     * there is nothing for them to decide. If that fails, the notice is the
     * whole of the message, and the ranked menu is showing its Log in button
     * again by then because the dead session is no longer being counted as one.
     */
    private fun sessionExpired(minecraft: Minecraft, backend: BackendClient) {
        // Only the client's *current* session matters. An old BackendClient from
        // a previous sign-in can still have a request in flight, and its 401 is
        // not news about the session in use.
        if (RankedState.backend !== backend) return

        val now = System.currentTimeMillis()
        if (signingIn || now - lastAutoAttempt < AUTO_RETRY_COOLDOWN_MS) return
        lastAutoAttempt = now

        log.info("session expired; signing back in")
        RankedNotice.info("Session expired — signing you back in", title = "Ranked")
        signIn(minecraft) { result ->
            if (result !is BackendClient.AuthResult.Ok) {
                RankedNotice.error("Could not sign back in — press Log in", title = "Ranked")
            }
        }
    }
}
