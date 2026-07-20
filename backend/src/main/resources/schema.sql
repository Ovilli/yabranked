-- YAB Ranked Postgres schema (target for the Postgres store; the service
-- currently runs on in-memory implementations of the same interfaces).

CREATE TABLE players (
    uuid            uuid PRIMARY KEY,
    name            text NOT NULL,
    banned_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE season_stats (
    uuid            uuid NOT NULL REFERENCES players (uuid),
    season          integer NOT NULL,
    rating          integer NOT NULL,
    matches_played  integer NOT NULL DEFAULT 0,
    wins            integer NOT NULL DEFAULT 0,
    losses          integer NOT NULL DEFAULT 0,
    draws           integer NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, season)
);

CREATE TABLE matches (
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
    created_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz
);

CREATE TABLE reports (
    id          uuid PRIMARY KEY,
    match_id    uuid NOT NULL REFERENCES matches (id),
    reporter    uuid NOT NULL REFERENCES players (uuid),
    accused     uuid NOT NULL REFERENCES players (uuid),
    reason      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (match_id, reporter)
);

CREATE INDEX matches_player_a_idx ON matches (player_a, season, created_at DESC);
CREATE INDEX matches_player_b_idx ON matches (player_b, season, created_at DESC);
CREATE INDEX season_stats_rating_idx ON season_stats (season, rating DESC);
