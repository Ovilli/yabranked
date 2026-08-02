-- --- Reports gain a lifecycle -------------------------------------------------
-- Filing a report wrote a row and nothing ever wrote to it again. There was no
-- record that a moderator had looked, no way to tell a judged accusation from a
-- fresh one, and — because `POST /v1/reports` sets `replays.under_review` and
-- nothing cleared it — the recording of every reported match was pinned against
-- the retention sweep permanently.

-- OPEN | REVIEWING | ACTIONED | DISMISSED, as text rather than an enum type:
-- adding a state to a Postgres enum is a migration, and the reader already
-- degrades an unknown value to OPEN rather than failing the listing.
ALTER TABLE reports ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'OPEN';

-- Null while the report is unresolved. REVIEWING deliberately leaves it null:
-- claiming a report is not deciding it.
ALTER TABLE reports ADD COLUMN IF NOT EXISTS resolved_at timestamptz;

-- Free text. The admin console is one password on the LAN and has no user
-- accounts, so there is no id to reference here — but "which of us dismissed
-- this" still gets asked, and a name answers it.
ALTER TABLE reports ADD COLUMN IF NOT EXISTS resolved_by text;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS resolution_note text;

-- The baseline's UNIQUE (match_id, reporter) is a bug, not a policy. The rule
-- became one report per *accused* per match when team formats landed — a 4v4
-- has four opponents and they do not misbehave as a unit — and `ReportStore`
-- was changed to match. The constraint was not, so reporting a second opponent
-- in the same match passed the application's check and then died on the insert.
-- Only Postgres was affected; the in-memory store the tests run against has no
-- constraint, which is why nothing caught it.
--
-- Found by shape rather than by name: `reports_match_id_reporter_key` is only
-- what Postgres happens to call it, and a `DROP CONSTRAINT IF EXISTS` on a
-- guessed name that misses is a no-op that leaves the bug in place and says
-- nothing. This drops whatever unique constraint covers exactly (match_id,
-- reporter), and does nothing if there is none.
DO $$
DECLARE
    doomed text;
BEGIN
    SELECT con.conname INTO doomed
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
     WHERE rel.relname = 'reports'
       AND con.contype = 'u'
       AND (
             SELECT array_agg(att.attname ORDER BY att.attname)
               FROM unnest(con.conkey) AS k(attnum)
               JOIN pg_attribute att
                 ON att.attrelid = con.conrelid AND att.attnum = k.attnum
           ) = ARRAY['match_id', 'reporter'];

    IF doomed IS NOT NULL THEN
        EXECUTE format('ALTER TABLE reports DROP CONSTRAINT %I', doomed);
    END IF;
END $$;

ALTER TABLE reports ADD CONSTRAINT reports_match_reporter_accused_key
    UNIQUE (match_id, reporter, accused);

-- The moderation queue is "open reports, newest first"; the count of reports
-- against one account is what ranks them by signal instead of by recency.
CREATE INDEX IF NOT EXISTS reports_status_created_idx ON reports (status, created_at DESC);
CREATE INDEX IF NOT EXISTS reports_accused_idx ON reports (accused);
