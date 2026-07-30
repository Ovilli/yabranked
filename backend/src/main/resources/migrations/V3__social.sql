-- Friends, parties, endorsements, per-mode ladders, and the team/party shape
-- of a match. Everything here is additive: the columns added to existing tables
-- all carry defaults that reproduce the pre-social behaviour exactly, so a
-- backend rolled back to the previous version still reads these rows correctly.

-- --- Privacy -----------------------------------------------------------------
-- The whole PrivacySettings block, as JSON. A column per toggle would need a
-- migration every time one is added; the legacy hide_flag / hide_rating columns
-- stay and are kept in sync by PlayerRecord.withPrivacy, so an older backend
-- still honours the two settings it knows about.
ALTER TABLE players ADD COLUMN IF NOT EXISTS privacy text;

-- --- Team and party matches --------------------------------------------------
-- teams: side-ordered rosters as JSON, e.g. [["uuid","uuid"],["uuid","uuid"]].
-- NULL means a 1v1 described by player_a / player_b alone, which is every row
-- written before this migration.
ALTER TABLE matches ADD COLUMN IF NOT EXISTS teams text;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS team_scores text;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS winning_team integer;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS party_id uuid;
-- Whether the match may move ratings. Defaulted true so every existing row
-- keeps counting exactly as it did.
ALTER TABLE matches ADD COLUMN IF NOT EXISTS rated boolean NOT NULL DEFAULT true;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS shared_world boolean NOT NULL DEFAULT true;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS shared_seed boolean NOT NULL DEFAULT true;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS per_team_world_seeds text;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS per_team_card_seeds text;
ALTER TABLE matches ADD COLUMN IF NOT EXISTS team_count integer NOT NULL DEFAULT 2;

-- Every player in the match, flattened. player_a / player_b are only the first
-- player of each side once teams exist, so "matches this player was in" has to
-- ask this instead — and a GIN index makes the containment test indexed rather
-- than a sequential scan of the season.
ALTER TABLE matches ADD COLUMN IF NOT EXISTS participants uuid[];
UPDATE matches SET participants = ARRAY[player_a, player_b] WHERE participants IS NULL;
CREATE INDEX IF NOT EXISTS matches_participants_idx ON matches USING gin (participants);
CREATE INDEX IF NOT EXISTS matches_season_participants_idx ON matches (season, created_at DESC);

-- --- Friends -----------------------------------------------------------------
-- Stored once per pair with the lower uuid first (enforced in FriendshipRecord),
-- so "are these two friends" is one indexed lookup and a pair can never end up
-- recorded in one direction only.
CREATE TABLE IF NOT EXISTS friendships (
    a       uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    b       uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    since   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (a, b),
    CHECK (a < b)
);
CREATE INDEX IF NOT EXISTS friendships_b_idx ON friendships (b);

CREATE TABLE IF NOT EXISTS friend_requests (
    id          uuid PRIMARY KEY,
    from_uuid   uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    to_uuid     uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- one pending request per direction; the service additionally refuses a
    -- second one when the reverse direction is already pending
    UNIQUE (from_uuid, to_uuid)
);
CREATE INDEX IF NOT EXISTS friend_requests_to_idx ON friend_requests (to_uuid);

-- --- Endorsements ------------------------------------------------------------
-- The primary key is the rule: one endorsement per teammate per match, so a
-- rematch loop cannot farm levels.
CREATE TABLE IF NOT EXISTS endorsements (
    match_id    uuid NOT NULL REFERENCES matches (id) ON DELETE CASCADE,
    from_uuid   uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    to_uuid     uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    category    text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (match_id, from_uuid, to_uuid)
);
CREATE INDEX IF NOT EXISTS endorsements_to_idx ON endorsements (to_uuid);

-- --- Per-mode ladders --------------------------------------------------------
-- One row per player per mode per season. season_stats keeps its exact meaning
-- (the solo ranked ladder); these rows are written for every mode played,
-- casual included, which is what makes the per-mode playtime breakdown possible.
CREATE TABLE IF NOT EXISTS mode_stats (
    uuid            uuid NOT NULL REFERENCES players (uuid) ON DELETE CASCADE,
    season          integer NOT NULL,
    format          text NOT NULL,
    rating          integer NOT NULL,
    matches_played  integer NOT NULL DEFAULT 0,
    wins            integer NOT NULL DEFAULT 0,
    losses          integer NOT NULL DEFAULT 0,
    draws           integer NOT NULL DEFAULT 0,
    playtime_s      bigint NOT NULL DEFAULT 0,
    forfeits        integer NOT NULL DEFAULT 0,
    current_streak  integer NOT NULL DEFAULT 0,
    best_streak     integer NOT NULL DEFAULT 0,
    peak_rating     integer NOT NULL,
    PRIMARY KEY (uuid, season, format)
);
CREATE INDEX IF NOT EXISTS mode_stats_ladder_idx ON mode_stats (season, format, rating DESC);
