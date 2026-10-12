-- Covering index for the agent's patch/tier scope aggregation (spec F-17, Q-22).
-- Plain CREATE INDEX: each migration runs in a transaction, so CONCURRENTLY is not usable.
-- Apply with the collector stopped; participants writes wait while the index is built.
SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_participants_scope_cover
    ON participants (game_id) INCLUDE (participant_id, tier, tier_division);