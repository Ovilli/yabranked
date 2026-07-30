-- --- Match replays -----------------------------------------------------------
-- One recording per match: the card, the marked events, and a one-per-second
-- position track per player. Stored as JSON text rather than columns because it
-- is always read whole, never queried into, and its shape belongs to the proto
-- module — a column per field would mean a migration per recorder change.
--
-- Retention has three independent reasons, none of which may override another:
-- `expires_at` is the default drop date, `replay_saves` is the players who
-- pinned it, and `under_review` is the moderator hold a report puts on it.
CREATE TABLE IF NOT EXISTS replays (
    match_id        uuid PRIMARY KEY REFERENCES matches (id) ON DELETE CASCADE,
    payload         text NOT NULL,
    recorded_at     timestamptz NOT NULL DEFAULT now(),
    duration_s      bigint NOT NULL DEFAULT 0,
    under_review    boolean NOT NULL DEFAULT false,
    expires_at      timestamptz NOT NULL
);

-- The pruner's query: unpinned, unreviewed, past its date.
CREATE INDEX IF NOT EXISTS replays_expiry_idx ON replays (expires_at) WHERE NOT under_review;
CREATE INDEX IF NOT EXISTS replays_review_idx ON replays (recorded_at DESC) WHERE under_review;

-- Who pinned which replay. A row here is one against that player's quota.
CREATE TABLE IF NOT EXISTS replay_saves (
    match_id    uuid NOT NULL REFERENCES replays (match_id) ON DELETE CASCADE,
    uuid        uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    saved_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, uuid)
);
CREATE INDEX IF NOT EXISTS replay_saves_player_idx ON replay_saves (uuid, saved_at DESC);
