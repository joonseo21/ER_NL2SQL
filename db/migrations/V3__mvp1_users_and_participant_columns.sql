-- V3 (DRAFT, not applied to production): MVP-1 schema, additive part.
-- Tested on a copy of production on 2026-10-08 (50,856 participants): 13.5 s, re-run safe.
--
-- Adds users, tiers, participant child tables and new nullable columns.
-- Nothing is dropped or renamed here, so the pre-V3 collector keeps working after this file
-- is applied. Adding participant_id rewrites participants once under an ACCESS EXCLUSIVE
-- lock, so stop the collector while it runs. Filling the new columns from participants.raw,
-- removing the promoted keys from raw and restricting agent_ro columns belong to the
-- follow-up migration.
--
-- Idempotent: safe to run repeatedly (IF NOT EXISTS / ON CONFLICT DO NOTHING).
-- Must run as the table owner (postgres). Requires PostgreSQL 16+.
BEGIN;

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- Tier boundaries by season. Tier is decided from mmr_before at the time of the game.
-- Demigod / Eternity are rank-based and cannot be told apart from MMR, so they are
-- folded into '미스릴 이상'.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tiers (
    season_id integer NOT NULL,
    tier text NOT NULL,
    division smallint NOT NULL DEFAULT 0,   -- 4..1 inside a tier, 0 when the tier has no division
    min_mmr integer NOT NULL,
    sort_order smallint NOT NULL,
    PRIMARY KEY (season_id, tier, division),
    UNIQUE (season_id, min_mmr)
);

INSERT INTO tiers(season_id, tier, division, min_mmr, sort_order) VALUES
    (41, '플래티넘 미만', 0, 0, 0),
    (41, '플래티넘', 4, 3600, 1),
    (41, '플래티넘', 3, 3950, 2),
    (41, '플래티넘', 2, 4300, 3),
    (41, '플래티넘', 1, 4650, 4),
    (41, '다이아몬드', 4, 5000, 5),
    (41, '다이아몬드', 3, 5350, 6),
    (41, '다이아몬드', 2, 5700, 7),
    (41, '다이아몬드', 1, 6050, 8),
    (41, '메테오라이트', 4, 6400, 9),
    (41, '메테오라이트', 3, 6700, 10),
    (41, '메테오라이트', 2, 7000, 11),
    (41, '메테오라이트', 1, 7300, 12),
    (41, '미스릴 이상', 0, 7600, 13)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- users: every nickname seen in a collected game. Replaces rankers as the seed source and
-- the USER rows of collect_queue as the crawl frontier. uid is not stored: it changes with
-- the nickname and is looked up again on every crawl. Not readable by agent_ro.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    user_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_id integer NOT NULL,
    nickname text NOT NULL,
    last_mmr integer,
    tier text,
    games_seen integer NOT NULL DEFAULT 0 CHECK (games_seen >= 0),
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_game_id bigint,
    last_game_at timestamptz,
    crawl_status text NOT NULL DEFAULT 'NEW'
        CHECK (crawl_status IN ('NEW', 'DONE', 'NOT_FOUND', 'ERROR')),
    last_crawled_at timestamptz,
    crawled_newest_game_id bigint,          -- newest gameId of the list at the last crawl (resume point)
    crawl_error text,
    UNIQUE (season_id, nickname)
);

CREATE INDEX IF NOT EXISTS idx_users_crawl_pick
    ON users (season_id, tier, crawl_status, last_crawled_at);

-- ---------------------------------------------------------------------------
-- games: values that are identical for every participant of a game.
-- ---------------------------------------------------------------------------
ALTER TABLE games
    ADD COLUMN IF NOT EXISTS match_size integer,
    ADD COLUMN IF NOT EXISTS team_count integer,
    ADD COLUMN IF NOT EXISTS mmr_avg integer,
    ADD COLUMN IF NOT EXISTS main_weather integer,
    ADD COLUMN IF NOT EXISTS sub_weather integer;

-- ---------------------------------------------------------------------------
-- participants: numeric id, anonymous user link, copied filter columns and promoted
-- BattleUserResult fields. Column names are the snake_case form of the API key.
-- ---------------------------------------------------------------------------
ALTER TABLE participants
    ADD COLUMN IF NOT EXISTS participant_id bigint GENERATED ALWAYS AS IDENTITY,
    ADD COLUMN IF NOT EXISTS user_id bigint REFERENCES users(user_id),
    -- copied from games / tiers so most questions need no join
    ADD COLUMN IF NOT EXISTS version_major integer,
    ADD COLUMN IF NOT EXISTS version_minor integer,
    ADD COLUMN IF NOT EXISTS tier text,
    ADD COLUMN IF NOT EXISTS tier_division smallint,
    -- result and basics
    ADD COLUMN IF NOT EXISTS victory integer,
    ADD COLUMN IF NOT EXISTS character_level integer,
    ADD COLUMN IF NOT EXISTS skin_code integer,
    ADD COLUMN IF NOT EXISTS player_deaths integer,
    ADD COLUMN IF NOT EXISTS team_kill integer,
    ADD COLUMN IF NOT EXISTS total_field_kill integer,
    ADD COLUMN IF NOT EXISTS give_up integer,
    ADD COLUMN IF NOT EXISTS escape_state integer,
    ADD COLUMN IF NOT EXISTS account_level integer,
    ADD COLUMN IF NOT EXISTS mmr_gain_in_game integer,
    ADD COLUMN IF NOT EXISTS mmr_loss_entry_cost integer,
    ADD COLUMN IF NOT EXISTS gambit boolean,
    ADD COLUMN IF NOT EXISTS kings_gambit boolean,
    ADD COLUMN IF NOT EXISTS mmr_gain_gambit integer,
    ADD COLUMN IF NOT EXISTS watch_time integer,
    ADD COLUMN IF NOT EXISTS total_time integer,
    ADD COLUMN IF NOT EXISTS duration integer,              -- differs per participant, not per game
    ADD COLUMN IF NOT EXISTS use_emoticon_count integer,
    -- damage dealt
    ADD COLUMN IF NOT EXISTS damage_to_player_basic integer,
    ADD COLUMN IF NOT EXISTS damage_to_player_skill integer,
    ADD COLUMN IF NOT EXISTS damage_to_player_item_skill integer,
    ADD COLUMN IF NOT EXISTS damage_to_player_direct integer,
    ADD COLUMN IF NOT EXISTS damage_to_monster integer,
    -- damage taken
    ADD COLUMN IF NOT EXISTS damage_from_player integer,
    ADD COLUMN IF NOT EXISTS damage_from_player_basic integer,
    ADD COLUMN IF NOT EXISTS damage_from_player_skill integer,
    ADD COLUMN IF NOT EXISTS damage_from_player_item_skill integer,
    ADD COLUMN IF NOT EXISTS damage_from_player_direct integer,
    ADD COLUMN IF NOT EXISTS damage_from_monster integer,
    ADD COLUMN IF NOT EXISTS damage_offseted_by_shield_player integer,
    -- healing, shielding, crowd control
    ADD COLUMN IF NOT EXISTS heal_amount integer,
    ADD COLUMN IF NOT EXISTS team_recover integer,
    ADD COLUMN IF NOT EXISTS protect_absorb integer,
    ADD COLUMN IF NOT EXISTS cc_time_to_player double precision,
    -- fights
    ADD COLUMN IF NOT EXISTS team_down integer,
    ADD COLUMN IF NOT EXISTS team_elimination integer,
    ADD COLUMN IF NOT EXISTS terminate_count integer,
    ADD COLUMN IF NOT EXISTS clutch_count integer,
    ADD COLUMN IF NOT EXISTS kills_phase_one integer,
    ADD COLUMN IF NOT EXISTS kills_phase_two integer,
    ADD COLUMN IF NOT EXISTS kills_phase_three integer,
    ADD COLUMN IF NOT EXISTS deaths_phase_one integer,
    ADD COLUMN IF NOT EXISTS deaths_phase_two integer,
    ADD COLUMN IF NOT EXISTS deaths_phase_three integer,
    ADD COLUMN IF NOT EXISTS credit_revival_count integer,
    ADD COLUMN IF NOT EXISTS credit_revived_others_count integer,
    -- final stats
    ADD COLUMN IF NOT EXISTS max_hp integer,
    ADD COLUMN IF NOT EXISTS attack_power integer,
    ADD COLUMN IF NOT EXISTS defense integer,
    ADD COLUMN IF NOT EXISTS attack_speed double precision,
    ADD COLUMN IF NOT EXISTS move_speed double precision,
    ADD COLUMN IF NOT EXISTS skill_amp integer,
    ADD COLUMN IF NOT EXISTS adaptive_force integer,
    ADD COLUMN IF NOT EXISTS cool_down_reduction double precision,
    ADD COLUMN IF NOT EXISTS critical_strike_chance double precision,
    ADD COLUMN IF NOT EXISTS life_steal double precision,
    ADD COLUMN IF NOT EXISTS attack_range double precision,
    ADD COLUMN IF NOT EXISTS sight_range double precision,
    -- credits and crafting
    ADD COLUMN IF NOT EXISTS total_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS total_use_vf_credit integer,
    ADD COLUMN IF NOT EXISTS craft_uncommon integer,
    ADD COLUMN IF NOT EXISTS craft_rare integer,
    ADD COLUMN IF NOT EXISTS craft_epic integer,
    ADD COLUMN IF NOT EXISTS craft_legend integer,
    ADD COLUMN IF NOT EXISTS craft_mythic integer,
    ADD COLUMN IF NOT EXISTS camp_fire_craft_uncommon integer,
    ADD COLUMN IF NOT EXISTS camp_fire_craft_rare integer,
    ADD COLUMN IF NOT EXISTS camp_fire_craft_epic integer,
    ADD COLUMN IF NOT EXISTS camp_fire_craft_legendary integer,
    -- credits gained per kill source
    ADD COLUMN IF NOT EXISTS kill_player_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_chicken_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_boar_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_wild_dog_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_wolf_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_bear_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_bat_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_raven_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_wickline_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_alpha_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_omega_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_gamma_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_drone_gain_vf_credit integer,
    ADD COLUMN IF NOT EXISTS kill_item_bounty_gain_vf_credit integer,
    -- vision and objectives
    ADD COLUMN IF NOT EXISTS add_telephoto_camera integer,
    ADD COLUMN IF NOT EXISTS remove_telephoto_camera integer,
    ADD COLUMN IF NOT EXISTS use_hyper_loop integer,
    ADD COLUMN IF NOT EXISTS use_security_console integer,
    ADD COLUMN IF NOT EXISTS use_recon_drone integer,
    ADD COLUMN IF NOT EXISTS use_emp_drone integer,
    ADD COLUMN IF NOT EXISTS tactical_skill_use_count integer,
    ADD COLUMN IF NOT EXISTS kill_gamma boolean,
    ADD COLUMN IF NOT EXISTS enter_dimension_rift integer,
    ADD COLUMN IF NOT EXISTS win_from_dimension_rift integer,
    ADD COLUMN IF NOT EXISTS enter_dimension_empowered_rift integer,
    ADD COLUMN IF NOT EXISTS win_from_dimension_empowered_rift integer,
    ADD COLUMN IF NOT EXISTS sum_get_buff_cube integer,
    -- build (single values)
    ADD COLUMN IF NOT EXISTS trait_first_core integer,
    ADD COLUMN IF NOT EXISTS tactical_skill_group integer,
    ADD COLUMN IF NOT EXISTS tactical_skill_level integer,
    ADD COLUMN IF NOT EXISTS route_id_of_start integer,
    ADD COLUMN IF NOT EXISTS place_of_start integer,
    -- ordered or fixed-length values kept as arrays instead of one row per element
    ADD COLUMN IF NOT EXISTS skill_order integer[],                 -- skillOrderInfo, in level-up order
    ADD COLUMN IF NOT EXISTS total_vf_credits integer[],            -- totalVFCredits, per minute
    ADD COLUMN IF NOT EXISTS used_vf_credits integer[],             -- usedVFCredits, per minute
    ADD COLUMN IF NOT EXISTS total_tk_per_min integer[],            -- totalTKPerMin
    ADD COLUMN IF NOT EXISTS food_craft_count integer[],            -- foodCraftCount, by grade
    ADD COLUMN IF NOT EXISTS item_transferred_drone integer[],      -- items bought by remote drone
    ADD COLUMN IF NOT EXISTS item_transferred_console integer[],    -- items bought at a console
    -- name -> value maps whose keys vary by participant
    ADD COLUMN IF NOT EXISTS skill_level_info jsonb,
    ADD COLUMN IF NOT EXISTS credit_source jsonb,
    ADD COLUMN IF NOT EXISTS kill_monsters jsonb;

CREATE UNIQUE INDEX IF NOT EXISTS uq_participants_participant_id
    ON participants (participant_id);
CREATE INDEX IF NOT EXISTS idx_participants_patch_character
    ON participants (version_major, version_minor, character_num);
CREATE INDEX IF NOT EXISTS idx_participants_tier
    ON participants (tier);
CREATE INDEX IF NOT EXISTS idx_participants_user
    ON participants (user_id);

-- ---------------------------------------------------------------------------
-- Child tables for multi-valued fields that are filtered by code.
-- ---------------------------------------------------------------------------

-- equipment / equipmentGrade (kind = 'FINAL') and equipFirstItemForLog (kind = 'FIRST').
CREATE TABLE IF NOT EXISTS participant_equipment (
    participant_id bigint NOT NULL REFERENCES participants(participant_id) ON DELETE CASCADE,
    kind text NOT NULL CHECK (kind IN ('FINAL', 'FIRST')),
    slot smallint NOT NULL,                 -- 0 weapon, 1 chest, 2 head, 3 arm, 4 leg
    item_code integer NOT NULL,
    grade smallint,                         -- equipmentGrade, FINAL only
    PRIMARY KEY (participant_id, kind, slot, item_code)
);
CREATE INDEX IF NOT EXISTS idx_participant_equipment_item
    ON participant_equipment (item_code, kind);

-- traitFirstCore / traitFirstSub / traitSecondSub.
CREATE TABLE IF NOT EXISTS participant_traits (
    participant_id bigint NOT NULL REFERENCES participants(participant_id) ON DELETE CASCADE,
    slot_type text NOT NULL CHECK (slot_type IN ('CORE', 'FIRST_SUB', 'SECOND_SUB')),
    trait_code integer NOT NULL,
    PRIMARY KEY (participant_id, slot_type, trait_code)
);
CREATE INDEX IF NOT EXISTS idx_participant_traits_trait
    ON participant_traits (trait_code);

-- masteryLevel.
CREATE TABLE IF NOT EXISTS participant_mastery (
    participant_id bigint NOT NULL REFERENCES participants(participant_id) ON DELETE CASCADE,
    mastery_code integer NOT NULL,
    mastery_level integer NOT NULL,
    PRIMARY KEY (participant_id, mastery_code)
);

-- killDetails / deathDetails: how often this participant killed, and was killed by, each
-- opponent character. character_num is copied so matchup questions need no join.
CREATE TABLE IF NOT EXISTS participant_matchups (
    participant_id bigint NOT NULL REFERENCES participants(participant_id) ON DELETE CASCADE,
    character_num integer NOT NULL,
    opponent_character_num integer NOT NULL,
    kills integer NOT NULL DEFAULT 0,
    deaths integer NOT NULL DEFAULT 0,
    PRIMARY KEY (participant_id, opponent_character_num)
);
CREATE INDEX IF NOT EXISTS idx_participant_matchups_pair
    ON participant_matchups (character_num, opponent_character_num);

-- killer / killerCharacter / killerWeapon / causeOfDeath / placeOfDeath and their 2, 3 forms.
-- The killer nickname (killDetail) is resolved to killer_participant_id at load time and is
-- not stored.
CREATE TABLE IF NOT EXISTS participant_deaths (
    participant_id bigint NOT NULL REFERENCES participants(participant_id) ON DELETE CASCADE,
    death_seq smallint NOT NULL CHECK (death_seq BETWEEN 1 AND 3),
    killer_type text,                       -- player, wildAnimal, restrictedArea, ...
    killer_participant_id bigint REFERENCES participants(participant_id) ON DELETE SET NULL,
    killer_character_num integer,
    killer_name text,                       -- killerCharacter as sent (character or monster name)
    killer_weapon text,
    cause_of_death text,
    place_of_death integer,
    PRIMARY KEY (participant_id, death_seq)
);
CREATE INDEX IF NOT EXISTS idx_participant_deaths_killer_character
    ON participant_deaths (killer_character_num);

-- ---------------------------------------------------------------------------
-- Grants. Roles do not exist yet when a fresh volume runs this file before 020-roles.sh,
-- so 020-roles.sh must grant the same privileges. agent_ro grants and the removal of its
-- access to participants.nickname / raw come with the follow-up migration.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'collector') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            users, tiers, participant_equipment, participant_traits, participant_mastery,
            participant_matchups, participant_deaths
        TO collector;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO collector;
    END IF;
END
$$;

COMMIT;
