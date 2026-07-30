-- --- Replays become packet captures ------------------------------------------
-- The v1 format was a one-per-second position track stored whole in
-- `replays.payload`, and it could describe a route and never a match. It is
-- replaced by a capture of every clientbound packet each participant was sent,
-- which a client can feed back into a fake connection and stand inside.
--
-- Two consequences for the schema:
--
--  * The bytes do not live here. A recording is tens of megabytes per player,
--    appended to in chunks while the match is being played and read back in
--    ranges afterwards — a filesystem's job, and close to the worst possible use
--    of a bytea. The `replays` row keeps only the index (`meta`) and the byte
--    total, so the quota can be counted without touching the recording.
--  * A v1 payload is not a short v2 recording, it is a different thing wearing
--    the same name. Existing rows are dropped rather than migrated: there is no
--    packet data anywhere to reconstruct, and keeping them would only mean
--    replay screens offering matches that cannot be opened.
DELETE FROM replays;

ALTER TABLE replays DROP COLUMN IF EXISTS payload;

-- The recording's index: `MatchReplayMeta` as the agent sent it. Empty while the
-- first chunks are arriving, because a stream is uploaded before it is described.
ALTER TABLE replays ADD COLUMN IF NOT EXISTS meta text NOT NULL DEFAULT '';

-- Packet bytes held across every stream of this match. Maintained by the append
-- route from the blob store's own total, so the quota never has to stat a disk.
ALTER TABLE replays ADD COLUMN IF NOT EXISTS size_bytes bigint NOT NULL DEFAULT 0;

-- False while the container is still uploading. A partial recording is playable
-- up to where it got to; a viewer that does not say so is claiming the match
-- ended where the upload stopped.
ALTER TABLE replays ADD COLUMN IF NOT EXISTS complete boolean NOT NULL DEFAULT false;
