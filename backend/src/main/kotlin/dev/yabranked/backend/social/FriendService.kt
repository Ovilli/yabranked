package dev.yabranked.backend.social

import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.FriendRequestRecord
import dev.yabranked.backend.store.FriendStore
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Friend requests and friendships.
 *
 * Three rules do the safety work, all enforced here rather than in the API
 * layer so no future endpoint can skip one:
 *
 *  1. **The target must have used the ranked mod.** Having a player row means
 *     they have signed in at least once, so a request can never be aimed at an
 *     account that has no way to see or refuse it. It does *not* require the two
 *     to have played a match together — friends are usually made before the
 *     first game, not after it.
 *  2. **The target's toggle wins.** `allowFriendRequests = false` refuses the
 *     request; it does not queue it for later.
 *  3. **One pending request per pair, in either direction.** A declined request
 *     is deleted, and re-requesting is rate-limited by the API's budget.
 */
class FriendService(
    private val friends: FriendStore,
    private val players: PlayerStore,
    private val matches: MatchStore,
    private val seasons: SeasonService,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Cap per account. Unbounded lists are a storage and a fan-out problem:
     * every presence push walks this list.
     */
    val maxFriends: Int = 200,
    /**
     * How far back "you played together" looks. Wide enough that a friendship
     * request after a good match is never refused, bounded so the check stays
     * one indexed query.
     */
    private val sharedMatchWindow: Int = 200,
    /** Pending requests older than this are treated as gone. */
    private val requestTtl: Duration = Duration.ofDays(14),
) {
    sealed interface RequestResult {
        data class Sent(val request: FriendRequestRecord) : RequestResult

        /** The other player had already requested us; both are now friends. */
        data object AutoAccepted : RequestResult

        data class Rejected(val reason: String) : RequestResult
    }

    /**
     * Everyone [uuid] has shared a match with this season, newest first.
     *
     * A convenience list for the "add a friend" screen — the people you most
     * likely want to add — and no longer an allow-list: [canRequest] does not
     * consult it.
     */
    fun recentPlayers(uuid: UUID, limit: Int = 50): List<Pair<UUID, Instant>> {
        val season = seasons.currentSeason
        val seen = LinkedHashMap<UUID, Instant>()
        for (match in matches.historyFor(uuid, season, sharedMatchWindow)) {
            val at = match.completedAt ?: match.createdAt
            for (other in match.participants) {
                if (other == uuid) continue
                // historyFor is newest-first, so the first sighting is the newest
                seen.putIfAbsent(other, at)
            }
        }
        return seen.entries.take(limit).map { it.key to it.value }
    }

    /** How many decided matches the two have shared this season. */
    fun matchesTogether(a: UUID, b: UUID): Int =
        matches.historyFor(a, seasons.currentSeason, sharedMatchWindow)
            .count { b in it.participants }

    fun hasPlayedWith(a: UUID, b: UUID): Boolean =
        matches.historyFor(a, seasons.currentSeason, sharedMatchWindow)
            .any { b in it.participants }

    fun areFriends(a: UUID, b: UUID): Boolean = friends.areFriends(a, b)

    fun friendsOf(uuid: UUID): List<UUID> = friends.friendsOf(uuid).map { it.other(uuid) }

    fun incoming(uuid: UUID): List<FriendRequestRecord> = friends.incoming(uuid).filter(::isLive)

    fun outgoing(uuid: UUID): List<FriendRequestRecord> = friends.outgoing(uuid).filter(::isLive)

    fun pendingBetween(a: UUID, b: UUID): FriendRequestRecord? =
        friends.requestBetween(a, b)?.takeIf(::isLive)

    private fun isLive(request: FriendRequestRecord): Boolean =
        Duration.between(request.createdAt, clock.instant()) <= requestTtl

    /**
     * Whether [target] currently accepts requests from [from], or the reason
     * they do not. Used both to gate [request] and to grey out the button before
     * the player presses it.
     *
     * The only membership test is that [target] has a player row — i.e. has
     * used the mod. Anyone who has is addressable by name.
     */
    fun canRequest(from: UUID, target: UUID): String? {
        if (from == target) return "you cannot friend yourself"
        val record = players.getPlayer(target) ?: return "unknown player"
        if (record.isBanned) return "unknown player"
        if (!record.privacy.allowFriendRequests) return "this player is not accepting friend requests"
        if (friends.areFriends(from, target)) return "already friends"
        if (friends.friendCount(from) >= maxFriends) return "your friend list is full"
        if (friends.friendCount(target) >= maxFriends) return "their friend list is full"
        return null
    }

    fun request(from: UUID, target: UUID): RequestResult {
        canRequest(from, target)?.let { return RequestResult.Rejected(it) }

        // They asked first: accepting theirs is what the player meant, and it
        // avoids two mutually-pending requests neither side can resolve.
        val existing = friends.requestBetween(from, target)
        if (existing != null && isLive(existing) && existing.to == from) {
            accept(from, existing.id)
            return RequestResult.AutoAccepted
        }
        if (existing != null && !isLive(existing)) friends.deleteRequest(existing.id)
        if (existing != null && isLive(existing) && existing.from == from) {
            return RequestResult.Rejected("request already pending")
        }

        val record = FriendRequestRecord(
            id = UUID.randomUUID(),
            from = from,
            to = target,
            createdAt = clock.instant(),
        )
        if (!friends.insertRequest(record)) return RequestResult.Rejected("request already pending")
        return RequestResult.Sent(record)
    }

    /** Accept a request addressed to [self]. Returns the new friend's uuid. */
    fun accept(self: UUID, requestId: UUID): UUID? {
        val request = friends.getRequest(requestId) ?: return null
        // Only the addressee may accept — otherwise the sender could accept
        // their own request and add anyone at will.
        if (request.to != self) return null
        if (!isLive(request)) {
            friends.deleteRequest(request.id)
            return null
        }
        if (friends.friendCount(self) >= maxFriends || friends.friendCount(request.from) >= maxFriends) {
            return null
        }
        friends.addFriend(self, request.from, clock.instant())
        friends.deleteRequestsBetween(self, request.from)
        return request.from
    }

    /** Decline (as addressee) or cancel (as sender). */
    fun dismiss(self: UUID, requestId: UUID): Boolean {
        val request = friends.getRequest(requestId) ?: return false
        if (request.to != self && request.from != self) return false
        return friends.deleteRequest(request.id)
    }

    fun remove(self: UUID, other: UUID): Boolean {
        friends.deleteRequestsBetween(self, other)
        return friends.removeFriend(self, other)
    }
}
