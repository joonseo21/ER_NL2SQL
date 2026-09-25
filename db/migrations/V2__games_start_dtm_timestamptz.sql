-- V2: games.start_dtm timestamp -> timestamptz, and backfill from participants.raw.
--
-- Idempotent: safe to run repeatedly.
--   * The ALTER only runs while the column is still "timestamp without time zone".
--     (Re-applying USING ... AT TIME ZONE to a timestamptz would shift values.)
--   * The backfill only fills rows whose start_dtm IS NULL and skips unparsable values.
-- Must run as the table owner (postgres). Requires PostgreSQL 16+ (pg_input_is_valid).
BEGIN;

SET LOCAL lock_timeout = '10s';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'games'
          AND column_name = 'start_dtm'
          AND data_type = 'timestamp without time zone'
    ) THEN
        -- Legacy naive values (if any) came from the KST-based ER server.
        ALTER TABLE public.games
            ALTER COLUMN start_dtm TYPE timestamptz
            USING start_dtm AT TIME ZONE 'Asia/Seoul';
    END IF;
END
$$;

-- raw.startDtm looks like 2026-09-25T05:10:50.050+0900 (also accepts +09:00, +09 and Z).
-- Values without an offset or with an invalid calendar date are left NULL.
WITH parsed AS (
    SELECT p.game_id,
           CASE
               WHEN p.raw ->> 'startDtm' ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}(:?\d{2})?)$'
                    AND pg_input_is_valid(p.raw ->> 'startDtm', 'timestamptz')
               THEN (p.raw ->> 'startDtm')::timestamptz
           END AS start_dtm
    FROM public.participants p
    WHERE jsonb_typeof(p.raw -> 'startDtm') = 'string'
),
per_game AS (
    SELECT game_id, min(start_dtm) AS start_dtm
    FROM parsed
    WHERE start_dtm IS NOT NULL
    GROUP BY game_id
)
UPDATE public.games g
SET start_dtm = per_game.start_dtm
FROM per_game
WHERE g.game_id = per_game.game_id
  AND g.start_dtm IS NULL;

COMMIT;
