-- Inactivity decay bookkeeping for the season ladder.
--
-- Both columns are nullable on purpose: a row written before this migration has
-- no play history to infer, and decay must read that as "unknown" rather than
-- "idle since the epoch" — otherwise the first sweep after deploy would bill
-- every existing player for the entire life of the season.

ALTER TABLE season_stats ADD COLUMN IF NOT EXISTS last_played_at timestamptz;
ALTER TABLE season_stats ADD COLUMN IF NOT EXISTS decayed_through timestamptz;

-- The sweep scans the ladder for rows it has not yet billed; without this it is
-- a full table scan per season per run.
CREATE INDEX IF NOT EXISTS season_stats_decay_idx
    ON season_stats (season, last_played_at)
    WHERE last_played_at IS NOT NULL;
