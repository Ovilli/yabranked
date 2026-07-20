-- YAB Ranked Postgres schema (target for Phase 2; Phase 1 runs in-memory).

CREATE TABLE players (
    uuid            uuid PRIMARY KEY,
    name            text NOT NULL,
    rating          integer NOT NULL,
    matches_played  integer NOT NULL DEFAULT 0,
    wins            integer NOT NULL DEFAULT 0,
    losses          integer NOT NULL DEFAULT 0,
    draws           integer NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE matches (
    id               uuid PRIMARY KEY,
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

CREATE INDEX matches_player_a_idx ON matches (player_a, created_at DESC);
CREATE INDEX matches_player_b_idx ON matches (player_b, created_at DESC);
CREATE INDEX players_rating_idx ON players (rating DESC);
