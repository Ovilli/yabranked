package dev.yabranked.backend.api

import dev.yabranked.backend.rating.Tier
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.proto.MatchSide
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.QueueServerMessage
import dev.yabranked.proto.Visibility
import java.util.UUID

/**
 * Builds the `MatchFound` payload one player receives for one match.
 *
 * Shared because there are now two ways into a match — the matchmaker and a
 * party starting its own — and they must describe it identically. Written once
 * so the party path cannot quietly drift from the queue path.
 *
 * Blocking: reads the player and rating stores. Callers dispatch it off the
 * event loop.
 */
internal fun buildMatchFound(
    deps: ApiDependencies,
    player: UUID,
    match: MatchRecord,
): QueueServerMessage.MatchFound {
    val mySide = match.sideOf(player) ?: 0
    val opponent = match.opponentsOf(player).firstOrNull()
        ?: if (mySide == 0) match.playerB else match.playerA
    val roster = deps.players.getPlayers(match.participants)

    fun refOf(uuid: UUID): PlayerRef {
        val record = roster[uuid]
        return PlayerRef(
            uuid.toString(),
            record?.name ?: "?",
            country = record?.country.takeIf { record?.privacy?.showCountry == Visibility.EVERYONE },
        )
    }

    // One MatchSide per roster, with each player's rating in the format actually
    // being played — the 1v1 ladder would be the wrong number on a 3v3 versus
    // screen.
    val sides = match.rosters.mapIndexed { index, side ->
        val ratings = side.map { deps.matchService.ratingFor(it, match.format) }
        MatchSide(
            index = index,
            players = side.map(::refOf),
            // A hidden rating is shown as 0 rather than dropped: the list stays
            // index-aligned with `players`.
            ratings = side.mapIndexed { seat, uuid ->
                if (roster[uuid]?.privacy?.showRating == Visibility.NOBODY) 0 else ratings[seat]
            },
            tiers = ratings.map { Tier.format(it, isPlaced = true) },
            averageRating = if (ratings.isEmpty()) 0 else ratings.average().toInt(),
            label = when {
                match.rosters.size > 2 && side.size == 1 -> roster[side.first()]?.name ?: "?"
                index == 0 -> "Team A"
                index == 1 -> "Team B"
                else -> "Team ${'A' + index}"
            },
        )
    }

    val opponentRecord = roster[opponent]
    val opponentPrivacy = opponentRecord?.privacy
    val showRating = opponentPrivacy?.showRating?.allows(isSelf = false, isFriend = false) ?: false
    val showStreak = opponentPrivacy?.showStreak?.allows(isSelf = false, isFriend = false) ?: false
    val opponentRating = deps.matchService.ratingFor(opponent, match.format)

    return QueueServerMessage.MatchFound(
        matchId = match.id.toString(),
        team = if (mySide == 0) MatchTeam.TEAM_A else MatchTeam.TEAM_B,
        opponent = refOf(opponent),
        serverAddress = match.serverAddress.orEmpty(),
        // Zeroed rather than omitted when hidden; the tier still conveys the
        // bracket, which matchmaking transparency needs.
        opponentRating = if (showRating) opponentRating else 0,
        opponentTier = Tier.format(
            opponentRating,
            isPlaced = deps.matchService.placementMatchesRemaining(
                deps.matchService.statsFor(opponent)
            ) <= 0,
        ),
        teams = sides,
        teamIndex = mySide,
        format = match.format,
        // Form, next to rating: the versus screen is the one place a streak
        // actually changes how you play.
        opponentStreak = if (showStreak) deps.matchService.winStreakOf(opponent) else null,
    )
}
