-- Large append-mostly tables hit the default 10% analyze / 20% vacuum ratios only after days of
-- collection, so planner statistics and visibility maps go stale. Lower both to 2% so the
-- user-selection and NL2SQL plans keep using index-only scans. Table storage options only; no
-- row is read or changed.
ALTER TABLE participants SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE participant_equipment SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE participant_traits SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE participant_mastery SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE participant_matchups SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE participant_deaths SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
ALTER TABLE games SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.02);
