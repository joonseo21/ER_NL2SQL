-- NULL means not checked yet. A checked value always identifies its season and time.
-- Backfill is explicit collector work, not an unbounded scan inside Flyway.
DO $$
DECLARE name_table text;
BEGIN
    FOREACH name_table IN ARRAY ARRAY['items', 'trait_names', 'tactical_skills']
    LOOP
        EXECUTE format('ALTER TABLE %I
            ADD COLUMN seen_in_matches boolean,
            ADD COLUMN usage_season_id integer,
            ADD COLUMN usage_checked_at timestamptz,
            ADD CONSTRAINT %I CHECK (
                (seen_in_matches IS NULL AND usage_season_id IS NULL AND usage_checked_at IS NULL)
                OR (seen_in_matches IS NOT NULL AND usage_season_id IS NOT NULL
                    AND usage_season_id > 0 AND usage_checked_at IS NOT NULL))',
            name_table, name_table || '_match_usage_check');
    END LOOP;
END $$;

-- Existing table-level grants include new columns; state the intended contract explicitly.
GRANT SELECT ON items, trait_names, tactical_skills TO agent_ro;
