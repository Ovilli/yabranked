-- YAB Ranked Postgres schema (target for the Postgres store; the service
-- currently runs on in-memory implementations of the same interfaces).
--
-- This file is migration 1, the baseline: it creates the schema on an empty
-- database and is never re-run on one that already has it (see
-- store/Migrations.kt). New columns therefore go in a new
-- resources/migrations/V<n>__<name>.sql plus an entry in SchemaMigrator.ALL —
-- editing this file only affects databases created from scratch afterwards.

CREATE TABLE IF NOT EXISTS players (
    uuid            uuid PRIMARY KEY,
    name            text NOT NULL,
    banned_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    country         text,
    background      text NOT NULL DEFAULT 'default',
    hide_flag       boolean NOT NULL DEFAULT false,
    hide_rating     boolean NOT NULL DEFAULT false
);

CREATE TABLE IF NOT EXISTS season_stats (
    uuid             uuid NOT NULL REFERENCES players (uuid),
    season           integer NOT NULL,
    rating           integer NOT NULL,
    matches_played   integer NOT NULL DEFAULT 0,
    wins             integer NOT NULL DEFAULT 0,
    losses           integer NOT NULL DEFAULT 0,
    draws            integer NOT NULL DEFAULT 0,
    playtime_seconds bigint NOT NULL DEFAULT 0,
    forfeits         integer NOT NULL DEFAULT 0,
    peak_rating      integer NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, season)
);

CREATE TABLE IF NOT EXISTS matches (
    id               uuid PRIMARY KEY,
    season           integer NOT NULL,
    format           text NOT NULL,
    world_seed       bigint NOT NULL,
    card_seed        bigint NOT NULL,
    time_limit_s     bigint NOT NULL,
    player_a         uuid NOT NULL REFERENCES players (uuid),
    player_b         uuid NOT NULL REFERENCES players (uuid),
    status           text NOT NULL,
    server_token     text NOT NULL,
    server_address   text,
    outcome          text,
    rating_a_before  integer NOT NULL,
    rating_b_before  integer NOT NULL,
    rating_a_after   integer,
    rating_b_after   integer,
    duration_s       bigint,
    team_a_score     integer,
    team_b_score     integer,
    forfeited_by     uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz
);

CREATE TABLE IF NOT EXISTS reports (
    id          uuid PRIMARY KEY,
    match_id    uuid NOT NULL REFERENCES matches (id),
    reporter    uuid NOT NULL REFERENCES players (uuid),
    accused     uuid NOT NULL REFERENCES players (uuid),
    reason      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (match_id, reporter)
);

-- unlocked achievement milestones; achievement_id is a stable catalog string
CREATE TABLE IF NOT EXISTS player_achievements (
    uuid            uuid NOT NULL REFERENCES players (uuid),
    achievement_id  text NOT NULL,
    earned_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (uuid, achievement_id)
);

CREATE INDEX IF NOT EXISTS matches_player_a_idx ON matches (player_a, season, created_at DESC);
CREATE INDEX IF NOT EXISTS matches_player_b_idx ON matches (player_b, season, created_at DESC);
CREATE INDEX IF NOT EXISTS season_stats_rating_idx ON season_stats (season, rating DESC);

-- misc persisted settings (e.g. current_season)
CREATE TABLE IF NOT EXISTS settings (
    key    text PRIMARY KEY,
    value  text NOT NULL
);

-- Idempotent migrations for databases created before these columns existed.
-- CREATE TABLE IF NOT EXISTS above never alters an existing table, so add the
-- newer columns here. ADD COLUMN IF NOT EXISTS is a no-op on fresh schemas.
ALTER TABLE players ADD COLUMN IF NOT EXISTS country text;
ALTER TABLE players ADD COLUMN IF NOT EXISTS background text NOT NULL DEFAULT 'default';
ALTER TABLE players ADD COLUMN IF NOT EXISTS hide_flag boolean NOT NULL DEFAULT false;
ALTER TABLE players ADD COLUMN IF NOT EXISTS hide_rating boolean NOT NULL DEFAULT false;

ALTER TABLE season_stats ADD COLUMN IF NOT EXISTS playtime_seconds bigint NOT NULL DEFAULT 0;
ALTER TABLE season_stats ADD COLUMN IF NOT EXISTS forfeits integer NOT NULL DEFAULT 0;
ALTER TABLE season_stats ADD COLUMN IF NOT EXISTS peak_rating integer NOT NULL DEFAULT 0;

ALTER TABLE matches ADD COLUMN IF NOT EXISTS forfeited_by uuid;
