-- V4 (DRAFT, not applied to production): move BattleUserResult values out of participants.raw.
-- Tested on a copy of production on 2026-10-08 (50,856 participants): 3 min 36 s, re-run 3.6 s.
--
-- For every participant row that still holds the full API response in raw:
--   1. fill games / participants columns and the participant_* child tables (added in V3),
--   2. register users and link participants.user_id,
--   3. verify that every moved value equals the value in raw, and abort if anything differs,
--   4. remove the moved keys from raw. Keys that are not moved stay in raw.
-- Then agent_ro loses access to participants.nickname / raw / is_ranker, rankers and users.
--
-- A row is "not migrated yet" while raw still has the key characterNum, so the file is
-- idempotent and also migrates rows written by a pre-V4 collector after an earlier run.
-- After this file the collector must be the version that writes the new columns itself.
--
-- Equivalence used by the verification: a key that is absent, JSON null, an empty string or an
-- empty container in raw is treated as "no value". Numbers are compared by value.
-- Space is returned to the OS only after VACUUM FULL participants (run separately).
-- Must run as the table owner (postgres). Requires PostgreSQL 16+ (pg_input_is_valid).
BEGIN;

SET LOCAL lock_timeout = '10s';

CREATE TEMP TABLE v4_todo ON COMMIT DROP AS
SELECT participant_id, game_id FROM participants WHERE raw ? 'characterNum';
CREATE UNIQUE INDEX ON v4_todo (participant_id);
CREATE INDEX ON v4_todo (game_id);
ANALYZE v4_todo;

-- ---------------------------------------------------------------------------
-- 1. games: values shared by every participant of a game.
-- ---------------------------------------------------------------------------
UPDATE games g
SET match_size = s.match_size, mmr_avg = s.mmr_avg,
    main_weather = s.main_weather, sub_weather = s.sub_weather
FROM (
    SELECT DISTINCT ON (p.game_id) p.game_id,
           (p.raw ->> 'matchSize')::numeric::integer AS match_size,
           (p.raw ->> 'mmrAvg')::numeric::integer AS mmr_avg,
           (p.raw ->> 'mainWeather')::numeric::integer AS main_weather,
           (p.raw ->> 'subWeather')::numeric::integer AS sub_weather
    FROM participants p
    JOIN v4_todo t USING (participant_id)
    ORDER BY p.game_id, p.participant_id
) s
WHERE g.game_id = s.game_id;

UPDATE games g
SET team_count = s.team_count
FROM (
    SELECT game_id, count(DISTINCT team_number) AS team_count
    FROM participants
    WHERE game_id IN (SELECT game_id FROM v4_todo)
    GROUP BY game_id
) s
WHERE g.game_id = s.game_id;

-- ---------------------------------------------------------------------------
-- 2. participants: promoted columns, arrays, maps and the copied patch / tier columns.
-- ---------------------------------------------------------------------------
UPDATE participants p
SET
    victory = (s."victory")::integer,
    character_level = (s."characterLevel")::integer,
    skin_code = (s."skinCode")::integer,
    player_deaths = (s."playerDeaths")::integer,
    team_kill = (s."teamKill")::integer,
    total_field_kill = (s."totalFieldKill")::integer,
    give_up = (s."giveUp")::integer,
    escape_state = (s."escapeState")::integer,
    account_level = (s."accountLevel")::integer,
    mmr_gain_in_game = (s."mmrGainInGame")::integer,
    mmr_loss_entry_cost = (s."mmrLossEntryCost")::integer,
    mmr_gain_gambit = (s."mmrGainGambit")::integer,
    watch_time = (s."watchTime")::integer,
    total_time = (s."totalTime")::integer,
    duration = (s."duration")::integer,
    use_emoticon_count = (s."useEmoticonCount")::integer,
    damage_to_player_basic = (s."damageToPlayer_basic")::integer,
    damage_to_player_skill = (s."damageToPlayer_skill")::integer,
    damage_to_player_item_skill = (s."damageToPlayer_itemSkill")::integer,
    damage_to_player_direct = (s."damageToPlayer_direct")::integer,
    damage_to_monster = (s."damageToMonster")::integer,
    damage_from_player = (s."damageFromPlayer")::integer,
    damage_from_player_basic = (s."damageFromPlayer_basic")::integer,
    damage_from_player_skill = (s."damageFromPlayer_skill")::integer,
    damage_from_player_item_skill = (s."damageFromPlayer_itemSkill")::integer,
    damage_from_player_direct = (s."damageFromPlayer_direct")::integer,
    damage_from_monster = (s."damageFromMonster")::integer,
    damage_offseted_by_shield_player = (s."damageOffsetedByShield_Player")::integer,
    heal_amount = (s."healAmount")::integer,
    team_recover = (s."teamRecover")::integer,
    protect_absorb = (s."protectAbsorb")::integer,
    team_down = (s."teamDown")::integer,
    team_elimination = (s."teamElimination")::integer,
    terminate_count = (s."terminateCount")::integer,
    clutch_count = (s."clutchCount")::integer,
    kills_phase_one = (s."killsPhaseOne")::integer,
    kills_phase_two = (s."killsPhaseTwo")::integer,
    kills_phase_three = (s."killsPhaseThree")::integer,
    deaths_phase_one = (s."deathsPhaseOne")::integer,
    deaths_phase_two = (s."deathsPhaseTwo")::integer,
    deaths_phase_three = (s."deathsPhaseThree")::integer,
    credit_revival_count = (s."creditRevivalCount")::integer,
    credit_revived_others_count = (s."creditRevivedOthersCount")::integer,
    max_hp = (s."maxHp")::integer,
    attack_power = (s."attackPower")::integer,
    defense = (s."defense")::integer,
    skill_amp = (s."skillAmp")::integer,
    adaptive_force = (s."adaptiveForce")::integer,
    total_gain_vf_credit = (s."totalGainVFCredit")::integer,
    total_use_vf_credit = (s."totalUseVFCredit")::integer,
    craft_uncommon = (s."craftUncommon")::integer,
    craft_rare = (s."craftRare")::integer,
    craft_epic = (s."craftEpic")::integer,
    craft_legend = (s."craftLegend")::integer,
    craft_mythic = (s."craftMythic")::integer,
    camp_fire_craft_uncommon = (s."campFireCraftUncommon")::integer,
    camp_fire_craft_rare = (s."campFireCraftRare")::integer,
    camp_fire_craft_epic = (s."campFireCraftEpic")::integer,
    camp_fire_craft_legendary = (s."campFireCraftLegendary")::integer,
    kill_player_gain_vf_credit = (s."killPlayerGainVFCredit")::integer,
    kill_chicken_gain_vf_credit = (s."killChickenGainVFCredit")::integer,
    kill_boar_gain_vf_credit = (s."killBoarGainVFCredit")::integer,
    kill_wild_dog_gain_vf_credit = (s."killWildDogGainVFCredit")::integer,
    kill_wolf_gain_vf_credit = (s."killWolfGainVFCredit")::integer,
    kill_bear_gain_vf_credit = (s."killBearGainVFCredit")::integer,
    kill_bat_gain_vf_credit = (s."killBatGainVFCredit")::integer,
    kill_raven_gain_vf_credit = (s."killRavenGainVFCredit")::integer,
    kill_wickline_gain_vf_credit = (s."killWicklineGainVFCredit")::integer,
    kill_alpha_gain_vf_credit = (s."killAlphaGainVFCredit")::integer,
    kill_omega_gain_vf_credit = (s."killOmegaGainVFCredit")::integer,
    kill_gamma_gain_vf_credit = (s."killGammaGainVFCredit")::integer,
    kill_drone_gain_vf_credit = (s."killDroneGainVFCredit")::integer,
    kill_item_bounty_gain_vf_credit = (s."killItemBountyGainVFCredit")::integer,
    add_telephoto_camera = (s."addTelephotoCamera")::integer,
    remove_telephoto_camera = (s."removeTelephotoCamera")::integer,
    use_hyper_loop = (s."useHyperLoop")::integer,
    use_security_console = (s."useSecurityConsole")::integer,
    use_recon_drone = (s."useReconDrone")::integer,
    use_emp_drone = (s."useEmpDrone")::integer,
    tactical_skill_use_count = (s."tacticalSkillUseCount")::integer,
    enter_dimension_rift = (s."enterDimensionRift")::integer,
    win_from_dimension_rift = (s."winFromDimensionRift")::integer,
    enter_dimension_empowered_rift = (s."enterDimensionEmpoweredRift")::integer,
    win_from_dimension_empowered_rift = (s."winFromDimensionEmpoweredRift")::integer,
    sum_get_buff_cube = (s."sumGetBuffCube")::integer,
    trait_first_core = (s."traitFirstCore")::integer,
    tactical_skill_group = (s."tacticalSkillGroup")::integer,
    tactical_skill_level = (s."tacticalSkillLevel")::integer,
    route_id_of_start = (s."routeIdOfStart")::integer,
    cc_time_to_player = (s."ccTimeToPlayer")::double precision,
    attack_speed = (s."attackSpeed")::double precision,
    move_speed = (s."moveSpeed")::double precision,
    cool_down_reduction = (s."coolDownReduction")::double precision,
    critical_strike_chance = (s."criticalStrikeChance")::double precision,
    life_steal = (s."lifeSteal")::double precision,
    attack_range = (s."attackRange")::double precision,
    sight_range = (s."sightRange")::double precision,
    gambit = s."gambit",
    kings_gambit = s."kingsGambit",
    kill_gamma = s."killGamma",
    skill_level_info = s."skillLevelInfo",
    credit_source = s."creditSource",
    kill_monsters = s."killMonsters",
    total_vf_credits = CASE WHEN jsonb_typeof(s."totalVFCredits") = 'array' THEN
        ARRAY(SELECT v::numeric::integer
              FROM jsonb_array_elements_text(s."totalVFCredits") WITH ORDINALITY a(v, o) ORDER BY o) END,
    used_vf_credits = CASE WHEN jsonb_typeof(s."usedVFCredits") = 'array' THEN
        ARRAY(SELECT v::numeric::integer
              FROM jsonb_array_elements_text(s."usedVFCredits") WITH ORDINALITY a(v, o) ORDER BY o) END,
    total_tk_per_min = CASE WHEN jsonb_typeof(s."totalTKPerMin") = 'array' THEN
        ARRAY(SELECT v::numeric::integer
              FROM jsonb_array_elements_text(s."totalTKPerMin") WITH ORDINALITY a(v, o) ORDER BY o) END,
    food_craft_count = CASE WHEN jsonb_typeof(s."foodCraftCount") = 'array' THEN
        ARRAY(SELECT v::numeric::integer
              FROM jsonb_array_elements_text(s."foodCraftCount") WITH ORDINALITY a(v, o) ORDER BY o) END,
    item_transferred_drone = CASE WHEN jsonb_typeof(s."itemTransferredDrone") = 'array' THEN
        ARRAY(SELECT v::numeric::integer
              FROM jsonb_array_elements_text(s."itemTransferredDrone") WITH ORDINALITY a(v, o) ORDER BY o) END,
    item_transferred_console = CASE WHEN jsonb_typeof(s."itemTransferredConsole") = 'array' THEN
        ARRAY(SELECT v::numeric::integer
              FROM jsonb_array_elements_text(s."itemTransferredConsole") WITH ORDINALITY a(v, o) ORDER BY o) END,
    place_of_start = NULLIF(s."placeOfStart", '')::integer,
    skill_order = CASE WHEN jsonb_typeof(s."skillOrderInfo") = 'object' THEN
        ARRAY(SELECT e.value::numeric::integer
              FROM jsonb_each_text(s."skillOrderInfo") e ORDER BY e.key::integer) END,
    version_major = s.game_version_major,
    version_minor = s.game_version_minor,
    tier = (SELECT t.tier FROM tiers t
            WHERE t.season_id = s.game_season_id AND t.min_mmr <= p.mmr_before
            ORDER BY t.min_mmr DESC LIMIT 1),
    tier_division = (SELECT t.division FROM tiers t
                     WHERE t.season_id = s.game_season_id AND t.min_mmr <= p.mmr_before
                     ORDER BY t.min_mmr DESC LIMIT 1)
FROM (
    -- jsonb_to_record reads raw once per row. One "raw -> key" per column would decompress the
    -- whole document for every key.
    SELECT p2.participant_id, g.season_id AS game_season_id,
           g.version_major AS game_version_major, g.version_minor AS game_version_minor, x.*
    FROM participants p2
    JOIN v4_todo t USING (participant_id)
    JOIN games g ON g.game_id = p2.game_id
    CROSS JOIN LATERAL jsonb_to_record(p2.raw) AS x(
        "victory" numeric, "characterLevel" numeric, "skinCode" numeric, "playerDeaths" numeric,
        "teamKill" numeric, "totalFieldKill" numeric, "giveUp" numeric, "escapeState" numeric,
        "accountLevel" numeric, "mmrGainInGame" numeric, "mmrLossEntryCost" numeric, "mmrGainGambit" numeric,
        "watchTime" numeric, "totalTime" numeric, "duration" numeric, "useEmoticonCount" numeric,
        "damageToPlayer_basic" numeric, "damageToPlayer_skill" numeric, "damageToPlayer_itemSkill" numeric, "damageToPlayer_direct" numeric,
        "damageToMonster" numeric, "damageFromPlayer" numeric, "damageFromPlayer_basic" numeric, "damageFromPlayer_skill" numeric,
        "damageFromPlayer_itemSkill" numeric, "damageFromPlayer_direct" numeric, "damageFromMonster" numeric, "damageOffsetedByShield_Player" numeric,
        "healAmount" numeric, "teamRecover" numeric, "protectAbsorb" numeric, "teamDown" numeric,
        "teamElimination" numeric, "terminateCount" numeric, "clutchCount" numeric, "killsPhaseOne" numeric,
        "killsPhaseTwo" numeric, "killsPhaseThree" numeric, "deathsPhaseOne" numeric, "deathsPhaseTwo" numeric,
        "deathsPhaseThree" numeric, "creditRevivalCount" numeric, "creditRevivedOthersCount" numeric, "maxHp" numeric,
        "attackPower" numeric, "defense" numeric, "skillAmp" numeric, "adaptiveForce" numeric,
        "totalGainVFCredit" numeric, "totalUseVFCredit" numeric, "craftUncommon" numeric, "craftRare" numeric,
        "craftEpic" numeric, "craftLegend" numeric, "craftMythic" numeric, "campFireCraftUncommon" numeric,
        "campFireCraftRare" numeric, "campFireCraftEpic" numeric, "campFireCraftLegendary" numeric, "killPlayerGainVFCredit" numeric,
        "killChickenGainVFCredit" numeric, "killBoarGainVFCredit" numeric, "killWildDogGainVFCredit" numeric, "killWolfGainVFCredit" numeric,
        "killBearGainVFCredit" numeric, "killBatGainVFCredit" numeric, "killRavenGainVFCredit" numeric, "killWicklineGainVFCredit" numeric,
        "killAlphaGainVFCredit" numeric, "killOmegaGainVFCredit" numeric, "killGammaGainVFCredit" numeric, "killDroneGainVFCredit" numeric,
        "killItemBountyGainVFCredit" numeric, "addTelephotoCamera" numeric, "removeTelephotoCamera" numeric, "useHyperLoop" numeric,
        "useSecurityConsole" numeric, "useReconDrone" numeric, "useEmpDrone" numeric, "tacticalSkillUseCount" numeric,
        "enterDimensionRift" numeric, "winFromDimensionRift" numeric, "enterDimensionEmpoweredRift" numeric, "winFromDimensionEmpoweredRift" numeric,
        "sumGetBuffCube" numeric, "traitFirstCore" numeric, "tacticalSkillGroup" numeric, "tacticalSkillLevel" numeric,
        "routeIdOfStart" numeric, "ccTimeToPlayer" numeric, "attackSpeed" numeric, "moveSpeed" numeric,
        "coolDownReduction" numeric, "criticalStrikeChance" numeric, "lifeSteal" numeric, "attackRange" numeric,
        "sightRange" numeric, "gambit" boolean, "kingsGambit" boolean, "killGamma" boolean,
        "totalVFCredits" jsonb, "usedVFCredits" jsonb, "totalTKPerMin" jsonb, "foodCraftCount" jsonb,
        "itemTransferredDrone" jsonb, "itemTransferredConsole" jsonb, "skillLevelInfo" jsonb, "creditSource" jsonb,
        "killMonsters" jsonb, "placeOfStart" text, "skillOrderInfo" jsonb
    )
) s
WHERE p.participant_id = s.participant_id;

-- ---------------------------------------------------------------------------
-- 3. users: one row per (season, nickname) seen in a game. Recomputed from all participants,
--    crawl state of existing rows is kept.
-- ---------------------------------------------------------------------------
INSERT INTO users(season_id, nickname, last_mmr, tier, games_seen, first_seen_at, last_game_id, last_game_at)
SELECT a.season_id, a.nickname, a.last_mmr,
       (SELECT t.tier FROM tiers t
        WHERE t.season_id = a.season_id AND t.min_mmr <= a.last_mmr
        ORDER BY t.min_mmr DESC LIMIT 1),
       a.games_seen, a.first_seen_at, a.last_game_id, a.last_game_at
FROM (
    SELECT g.season_id, p.nickname,
           count(*)::integer AS games_seen,
           min(g.fetched_at) AS first_seen_at,
           (array_agg(p.mmr_after ORDER BY g.start_dtm DESC NULLS LAST, g.game_id DESC))[1] AS last_mmr,
           (array_agg(g.game_id ORDER BY g.start_dtm DESC NULLS LAST, g.game_id DESC))[1] AS last_game_id,
           max(g.start_dtm) AS last_game_at
    FROM participants p
    JOIN games g USING (game_id)
    WHERE g.season_id IS NOT NULL
    GROUP BY g.season_id, p.nickname
) a
ON CONFLICT (season_id, nickname) DO UPDATE SET
    last_mmr = EXCLUDED.last_mmr, tier = EXCLUDED.tier, games_seen = EXCLUDED.games_seen,
    last_game_id = EXCLUDED.last_game_id, last_game_at = EXCLUDED.last_game_at;

UPDATE participants p
SET user_id = u.user_id
FROM games g, users u
WHERE g.game_id = p.game_id
  AND u.season_id = g.season_id
  AND u.nickname = p.nickname
  AND p.user_id IS NULL;

-- ---------------------------------------------------------------------------
-- 4. Child tables.
-- ---------------------------------------------------------------------------
INSERT INTO participant_equipment(participant_id, kind, slot, item_code, grade)
SELECT p.participant_id, 'FINAL', e.key::smallint, e.value::numeric::integer,
       (p.raw -> 'equipmentGrade' ->> e.key)::numeric::smallint
FROM participants p
JOIN v4_todo t USING (participant_id)
CROSS JOIN LATERAL jsonb_each_text(
    CASE WHEN jsonb_typeof(p.raw -> 'equipment') = 'object' THEN p.raw -> 'equipment' ELSE '{}'::jsonb END) e
ON CONFLICT DO NOTHING;

INSERT INTO participant_equipment(participant_id, kind, slot, item_code)
SELECT p.participant_id, 'FIRST', e.key::smallint, i.value::numeric::integer
FROM participants p
JOIN v4_todo t USING (participant_id)
CROSS JOIN LATERAL jsonb_each(
    CASE WHEN jsonb_typeof(p.raw -> 'equipFirstItemForLog') = 'object'
         THEN p.raw -> 'equipFirstItemForLog' ELSE '{}'::jsonb END) e
CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE WHEN jsonb_typeof(e.value) = 'array' THEN e.value ELSE '[]'::jsonb END) i
ON CONFLICT DO NOTHING;

INSERT INTO participant_traits(participant_id, slot_type, trait_code)
SELECT p.participant_id, 'CORE', p.trait_first_core
FROM participants p
JOIN v4_todo t USING (participant_id)
WHERE p.trait_first_core IS NOT NULL
ON CONFLICT DO NOTHING;

INSERT INTO participant_traits(participant_id, slot_type, trait_code)
SELECT p.participant_id, s.slot_type, v.value::numeric::integer
FROM participants p
JOIN v4_todo t USING (participant_id)
CROSS JOIN (VALUES ('FIRST_SUB', 'traitFirstSub'), ('SECOND_SUB', 'traitSecondSub')) s(slot_type, raw_key)
CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE WHEN jsonb_typeof(p.raw -> s.raw_key) = 'array' THEN p.raw -> s.raw_key ELSE '[]'::jsonb END) v
ON CONFLICT DO NOTHING;

INSERT INTO participant_mastery(participant_id, mastery_code, mastery_level)
SELECT p.participant_id, e.key::integer, e.value::numeric::integer
FROM participants p
JOIN v4_todo t USING (participant_id)
CROSS JOIN LATERAL jsonb_each_text(
    CASE WHEN jsonb_typeof(p.raw -> 'masteryLevel') = 'object' THEN p.raw -> 'masteryLevel' ELSE '{}'::jsonb END) e
ON CONFLICT DO NOTHING;

-- killDetails / deathDetails are JSON documents sent as strings.
INSERT INTO participant_matchups(participant_id, character_num, opponent_character_num, kills, deaths)
SELECT participant_id, max(character_num), opponent_character_num, sum(kills)::integer, sum(deaths)::integer
FROM (
    SELECT p.participant_id, p.character_num, e.key::integer AS opponent_character_num,
           e.value::numeric::integer AS kills, 0 AS deaths
    FROM participants p
    JOIN v4_todo t USING (participant_id)
    CROSS JOIN LATERAL jsonb_each_text(coalesce(NULLIF(p.raw ->> 'killDetails', '')::jsonb, '{}'::jsonb)) e
    UNION ALL
    SELECT p.participant_id, p.character_num, e.key::integer, 0, e.value::numeric::integer
    FROM participants p
    JOIN v4_todo t USING (participant_id)
    CROSS JOIN LATERAL jsonb_each_text(coalesce(NULLIF(p.raw ->> 'deathDetails', '')::jsonb, '{}'::jsonb)) e
) m
GROUP BY participant_id, opponent_character_num
ON CONFLICT DO NOTHING;

-- Deaths 1..3. The killer nickname (killDetail, killDetail2, killDetail3) is resolved to the
-- killer's participant row of the same game; the nickname itself stays in raw only.
INSERT INTO participant_deaths(participant_id, death_seq, killer_type, killer_participant_id,
    killer_character_num, killer_name, killer_weapon, cause_of_death, place_of_death)
SELECT p.participant_id, 1, p.raw ->> 'killer', k.participant_id,
       coalesce(k.character_num,
                (SELECT min(c.character_code) FROM characters c
                 WHERE p.raw ->> 'killer' = 'player'
                   AND lower(c.name_en) = lower(p.raw ->> 'killerCharacter'))),
       NULLIF(p.raw ->> 'killerCharacter', ''), NULLIF(p.raw ->> 'killerWeapon', ''),
       NULLIF(p.raw ->> 'causeOfDeath', ''), NULLIF(p.raw ->> 'placeOfDeath', '')::integer
FROM participants p
JOIN v4_todo t USING (participant_id)
LEFT JOIN participants k
       ON k.game_id = p.game_id
      AND k.nickname = p.raw ->> 'killDetail'
      AND p.raw ->> 'killer' = 'player'
WHERE coalesce(p.raw ->> 'killer', '') <> ''
ON CONFLICT DO NOTHING;

INSERT INTO participant_deaths(participant_id, death_seq, killer_type, killer_participant_id,
    killer_character_num, killer_name, killer_weapon, cause_of_death, place_of_death)
SELECT p.participant_id, 2, p.raw ->> 'killer2', k.participant_id,
       coalesce(k.character_num,
                (SELECT min(c.character_code) FROM characters c
                 WHERE p.raw ->> 'killer2' = 'player'
                   AND lower(c.name_en) = lower(p.raw ->> 'killerCharacter2'))),
       NULLIF(p.raw ->> 'killerCharacter2', ''), NULLIF(p.raw ->> 'killerWeapon2', ''),
       NULLIF(p.raw ->> 'causeOfDeath2', ''), NULLIF(p.raw ->> 'placeOfDeath2', '')::integer
FROM participants p
JOIN v4_todo t USING (participant_id)
LEFT JOIN participants k
       ON k.game_id = p.game_id
      AND k.nickname = p.raw ->> 'killDetail2'
      AND p.raw ->> 'killer2' = 'player'
WHERE coalesce(p.raw ->> 'killer2', '') <> ''
ON CONFLICT DO NOTHING;

INSERT INTO participant_deaths(participant_id, death_seq, killer_type, killer_participant_id,
    killer_character_num, killer_name, killer_weapon, cause_of_death, place_of_death)
SELECT p.participant_id, 3, p.raw ->> 'killer3', k.participant_id,
       coalesce(k.character_num,
                (SELECT min(c.character_code) FROM characters c
                 WHERE p.raw ->> 'killer3' = 'player'
                   AND lower(c.name_en) = lower(p.raw ->> 'killerCharacter3'))),
       NULLIF(p.raw ->> 'killerCharacter3', ''), NULLIF(p.raw ->> 'killerWeapon3', ''),
       NULLIF(p.raw ->> 'causeOfDeath3', ''), NULLIF(p.raw ->> 'placeOfDeath3', '')::integer
FROM participants p
JOIN v4_todo t USING (participant_id)
LEFT JOIN participants k
       ON k.game_id = p.game_id
      AND k.nickname = p.raw ->> 'killDetail3'
      AND p.raw ->> 'killer3' = 'player'
WHERE coalesce(p.raw ->> 'killer3', '') <> ''
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Verification: every moved value must equal the value still present in raw.
-- ---------------------------------------------------------------------------
CREATE TEMP TABLE v4_mismatch ON COMMIT DROP AS
SELECT e.key AS what, e.value::bigint AS rows_differing
FROM (
    SELECT to_jsonb(c) AS j FROM (
        SELECT
        count(*) FILTER (WHERE x."teamNumber" IS NOT NULL AND to_jsonb(p.team_number) IS DISTINCT FROM x."teamNumber") AS "teamNumber",
        count(*) FILTER (WHERE x."characterNum" IS NOT NULL AND to_jsonb(p.character_num) IS DISTINCT FROM x."characterNum") AS "characterNum",
        count(*) FILTER (WHERE x."bestWeapon" IS NOT NULL AND to_jsonb(p.best_weapon) IS DISTINCT FROM x."bestWeapon") AS "bestWeapon",
        count(*) FILTER (WHERE x."bestWeaponLevel" IS NOT NULL AND to_jsonb(p.best_weapon_level) IS DISTINCT FROM x."bestWeaponLevel") AS "bestWeaponLevel",
        count(*) FILTER (WHERE x."gameRank" IS NOT NULL AND to_jsonb(p.game_rank) IS DISTINCT FROM x."gameRank") AS "gameRank",
        count(*) FILTER (WHERE x."playerKill" IS NOT NULL AND to_jsonb(p.player_kill) IS DISTINCT FROM x."playerKill") AS "playerKill",
        count(*) FILTER (WHERE x."playerAssistant" IS NOT NULL AND to_jsonb(p.player_assistant) IS DISTINCT FROM x."playerAssistant") AS "playerAssistant",
        count(*) FILTER (WHERE x."monsterKill" IS NOT NULL AND to_jsonb(p.monster_kill) IS DISTINCT FROM x."monsterKill") AS "monsterKill",
        count(*) FILTER (WHERE x."damageToPlayer" IS NOT NULL AND to_jsonb(p.damage_to_player) IS DISTINCT FROM x."damageToPlayer") AS "damageToPlayer",
        count(*) FILTER (WHERE x."mmrBefore" IS NOT NULL AND to_jsonb(p.mmr_before) IS DISTINCT FROM x."mmrBefore") AS "mmrBefore",
        count(*) FILTER (WHERE x."mmrGain" IS NOT NULL AND to_jsonb(p.mmr_gain) IS DISTINCT FROM x."mmrGain") AS "mmrGain",
        count(*) FILTER (WHERE x."mmrAfter" IS NOT NULL AND to_jsonb(p.mmr_after) IS DISTINCT FROM x."mmrAfter") AS "mmrAfter",
        count(*) FILTER (WHERE x."playTime" IS NOT NULL AND to_jsonb(p.play_time) IS DISTINCT FROM x."playTime") AS "playTime",
        count(*) FILTER (WHERE x."victory" IS NOT NULL AND to_jsonb(p.victory) IS DISTINCT FROM x."victory") AS "victory",
        count(*) FILTER (WHERE x."characterLevel" IS NOT NULL AND to_jsonb(p.character_level) IS DISTINCT FROM x."characterLevel") AS "characterLevel",
        count(*) FILTER (WHERE x."skinCode" IS NOT NULL AND to_jsonb(p.skin_code) IS DISTINCT FROM x."skinCode") AS "skinCode",
        count(*) FILTER (WHERE x."playerDeaths" IS NOT NULL AND to_jsonb(p.player_deaths) IS DISTINCT FROM x."playerDeaths") AS "playerDeaths",
        count(*) FILTER (WHERE x."teamKill" IS NOT NULL AND to_jsonb(p.team_kill) IS DISTINCT FROM x."teamKill") AS "teamKill",
        count(*) FILTER (WHERE x."totalFieldKill" IS NOT NULL AND to_jsonb(p.total_field_kill) IS DISTINCT FROM x."totalFieldKill") AS "totalFieldKill",
        count(*) FILTER (WHERE x."giveUp" IS NOT NULL AND to_jsonb(p.give_up) IS DISTINCT FROM x."giveUp") AS "giveUp",
        count(*) FILTER (WHERE x."escapeState" IS NOT NULL AND to_jsonb(p.escape_state) IS DISTINCT FROM x."escapeState") AS "escapeState",
        count(*) FILTER (WHERE x."accountLevel" IS NOT NULL AND to_jsonb(p.account_level) IS DISTINCT FROM x."accountLevel") AS "accountLevel",
        count(*) FILTER (WHERE x."mmrGainInGame" IS NOT NULL AND to_jsonb(p.mmr_gain_in_game) IS DISTINCT FROM x."mmrGainInGame") AS "mmrGainInGame",
        count(*) FILTER (WHERE x."mmrLossEntryCost" IS NOT NULL AND to_jsonb(p.mmr_loss_entry_cost) IS DISTINCT FROM x."mmrLossEntryCost") AS "mmrLossEntryCost",
        count(*) FILTER (WHERE x."mmrGainGambit" IS NOT NULL AND to_jsonb(p.mmr_gain_gambit) IS DISTINCT FROM x."mmrGainGambit") AS "mmrGainGambit",
        count(*) FILTER (WHERE x."watchTime" IS NOT NULL AND to_jsonb(p.watch_time) IS DISTINCT FROM x."watchTime") AS "watchTime",
        count(*) FILTER (WHERE x."totalTime" IS NOT NULL AND to_jsonb(p.total_time) IS DISTINCT FROM x."totalTime") AS "totalTime",
        count(*) FILTER (WHERE x."duration" IS NOT NULL AND to_jsonb(p.duration) IS DISTINCT FROM x."duration") AS "duration",
        count(*) FILTER (WHERE x."useEmoticonCount" IS NOT NULL AND to_jsonb(p.use_emoticon_count) IS DISTINCT FROM x."useEmoticonCount") AS "useEmoticonCount",
        count(*) FILTER (WHERE x."damageToPlayer_basic" IS NOT NULL AND to_jsonb(p.damage_to_player_basic) IS DISTINCT FROM x."damageToPlayer_basic") AS "damageToPlayer_basic",
        count(*) FILTER (WHERE x."damageToPlayer_skill" IS NOT NULL AND to_jsonb(p.damage_to_player_skill) IS DISTINCT FROM x."damageToPlayer_skill") AS "damageToPlayer_skill",
        count(*) FILTER (WHERE x."damageToPlayer_itemSkill" IS NOT NULL AND to_jsonb(p.damage_to_player_item_skill) IS DISTINCT FROM x."damageToPlayer_itemSkill") AS "damageToPlayer_itemSkill",
        count(*) FILTER (WHERE x."damageToPlayer_direct" IS NOT NULL AND to_jsonb(p.damage_to_player_direct) IS DISTINCT FROM x."damageToPlayer_direct") AS "damageToPlayer_direct",
        count(*) FILTER (WHERE x."damageToMonster" IS NOT NULL AND to_jsonb(p.damage_to_monster) IS DISTINCT FROM x."damageToMonster") AS "damageToMonster",
        count(*) FILTER (WHERE x."damageFromPlayer" IS NOT NULL AND to_jsonb(p.damage_from_player) IS DISTINCT FROM x."damageFromPlayer") AS "damageFromPlayer",
        count(*) FILTER (WHERE x."damageFromPlayer_basic" IS NOT NULL AND to_jsonb(p.damage_from_player_basic) IS DISTINCT FROM x."damageFromPlayer_basic") AS "damageFromPlayer_basic",
        count(*) FILTER (WHERE x."damageFromPlayer_skill" IS NOT NULL AND to_jsonb(p.damage_from_player_skill) IS DISTINCT FROM x."damageFromPlayer_skill") AS "damageFromPlayer_skill",
        count(*) FILTER (WHERE x."damageFromPlayer_itemSkill" IS NOT NULL AND to_jsonb(p.damage_from_player_item_skill) IS DISTINCT FROM x."damageFromPlayer_itemSkill") AS "damageFromPlayer_itemSkill",
        count(*) FILTER (WHERE x."damageFromPlayer_direct" IS NOT NULL AND to_jsonb(p.damage_from_player_direct) IS DISTINCT FROM x."damageFromPlayer_direct") AS "damageFromPlayer_direct",
        count(*) FILTER (WHERE x."damageFromMonster" IS NOT NULL AND to_jsonb(p.damage_from_monster) IS DISTINCT FROM x."damageFromMonster") AS "damageFromMonster",
        count(*) FILTER (WHERE x."damageOffsetedByShield_Player" IS NOT NULL AND to_jsonb(p.damage_offseted_by_shield_player) IS DISTINCT FROM x."damageOffsetedByShield_Player") AS "damageOffsetedByShield_Player",
        count(*) FILTER (WHERE x."healAmount" IS NOT NULL AND to_jsonb(p.heal_amount) IS DISTINCT FROM x."healAmount") AS "healAmount",
        count(*) FILTER (WHERE x."teamRecover" IS NOT NULL AND to_jsonb(p.team_recover) IS DISTINCT FROM x."teamRecover") AS "teamRecover",
        count(*) FILTER (WHERE x."protectAbsorb" IS NOT NULL AND to_jsonb(p.protect_absorb) IS DISTINCT FROM x."protectAbsorb") AS "protectAbsorb",
        count(*) FILTER (WHERE x."teamDown" IS NOT NULL AND to_jsonb(p.team_down) IS DISTINCT FROM x."teamDown") AS "teamDown",
        count(*) FILTER (WHERE x."teamElimination" IS NOT NULL AND to_jsonb(p.team_elimination) IS DISTINCT FROM x."teamElimination") AS "teamElimination",
        count(*) FILTER (WHERE x."terminateCount" IS NOT NULL AND to_jsonb(p.terminate_count) IS DISTINCT FROM x."terminateCount") AS "terminateCount",
        count(*) FILTER (WHERE x."clutchCount" IS NOT NULL AND to_jsonb(p.clutch_count) IS DISTINCT FROM x."clutchCount") AS "clutchCount",
        count(*) FILTER (WHERE x."killsPhaseOne" IS NOT NULL AND to_jsonb(p.kills_phase_one) IS DISTINCT FROM x."killsPhaseOne") AS "killsPhaseOne",
        count(*) FILTER (WHERE x."killsPhaseTwo" IS NOT NULL AND to_jsonb(p.kills_phase_two) IS DISTINCT FROM x."killsPhaseTwo") AS "killsPhaseTwo",
        count(*) FILTER (WHERE x."killsPhaseThree" IS NOT NULL AND to_jsonb(p.kills_phase_three) IS DISTINCT FROM x."killsPhaseThree") AS "killsPhaseThree",
        count(*) FILTER (WHERE x."deathsPhaseOne" IS NOT NULL AND to_jsonb(p.deaths_phase_one) IS DISTINCT FROM x."deathsPhaseOne") AS "deathsPhaseOne",
        count(*) FILTER (WHERE x."deathsPhaseTwo" IS NOT NULL AND to_jsonb(p.deaths_phase_two) IS DISTINCT FROM x."deathsPhaseTwo") AS "deathsPhaseTwo",
        count(*) FILTER (WHERE x."deathsPhaseThree" IS NOT NULL AND to_jsonb(p.deaths_phase_three) IS DISTINCT FROM x."deathsPhaseThree") AS "deathsPhaseThree",
        count(*) FILTER (WHERE x."creditRevivalCount" IS NOT NULL AND to_jsonb(p.credit_revival_count) IS DISTINCT FROM x."creditRevivalCount") AS "creditRevivalCount",
        count(*) FILTER (WHERE x."creditRevivedOthersCount" IS NOT NULL AND to_jsonb(p.credit_revived_others_count) IS DISTINCT FROM x."creditRevivedOthersCount") AS "creditRevivedOthersCount",
        count(*) FILTER (WHERE x."maxHp" IS NOT NULL AND to_jsonb(p.max_hp) IS DISTINCT FROM x."maxHp") AS "maxHp",
        count(*) FILTER (WHERE x."attackPower" IS NOT NULL AND to_jsonb(p.attack_power) IS DISTINCT FROM x."attackPower") AS "attackPower",
        count(*) FILTER (WHERE x."defense" IS NOT NULL AND to_jsonb(p.defense) IS DISTINCT FROM x."defense") AS "defense",
        count(*) FILTER (WHERE x."skillAmp" IS NOT NULL AND to_jsonb(p.skill_amp) IS DISTINCT FROM x."skillAmp") AS "skillAmp",
        count(*) FILTER (WHERE x."adaptiveForce" IS NOT NULL AND to_jsonb(p.adaptive_force) IS DISTINCT FROM x."adaptiveForce") AS "adaptiveForce",
        count(*) FILTER (WHERE x."totalGainVFCredit" IS NOT NULL AND to_jsonb(p.total_gain_vf_credit) IS DISTINCT FROM x."totalGainVFCredit") AS "totalGainVFCredit",
        count(*) FILTER (WHERE x."totalUseVFCredit" IS NOT NULL AND to_jsonb(p.total_use_vf_credit) IS DISTINCT FROM x."totalUseVFCredit") AS "totalUseVFCredit",
        count(*) FILTER (WHERE x."craftUncommon" IS NOT NULL AND to_jsonb(p.craft_uncommon) IS DISTINCT FROM x."craftUncommon") AS "craftUncommon",
        count(*) FILTER (WHERE x."craftRare" IS NOT NULL AND to_jsonb(p.craft_rare) IS DISTINCT FROM x."craftRare") AS "craftRare",
        count(*) FILTER (WHERE x."craftEpic" IS NOT NULL AND to_jsonb(p.craft_epic) IS DISTINCT FROM x."craftEpic") AS "craftEpic",
        count(*) FILTER (WHERE x."craftLegend" IS NOT NULL AND to_jsonb(p.craft_legend) IS DISTINCT FROM x."craftLegend") AS "craftLegend",
        count(*) FILTER (WHERE x."craftMythic" IS NOT NULL AND to_jsonb(p.craft_mythic) IS DISTINCT FROM x."craftMythic") AS "craftMythic",
        count(*) FILTER (WHERE x."campFireCraftUncommon" IS NOT NULL AND to_jsonb(p.camp_fire_craft_uncommon) IS DISTINCT FROM x."campFireCraftUncommon") AS "campFireCraftUncommon",
        count(*) FILTER (WHERE x."campFireCraftRare" IS NOT NULL AND to_jsonb(p.camp_fire_craft_rare) IS DISTINCT FROM x."campFireCraftRare") AS "campFireCraftRare",
        count(*) FILTER (WHERE x."campFireCraftEpic" IS NOT NULL AND to_jsonb(p.camp_fire_craft_epic) IS DISTINCT FROM x."campFireCraftEpic") AS "campFireCraftEpic",
        count(*) FILTER (WHERE x."campFireCraftLegendary" IS NOT NULL AND to_jsonb(p.camp_fire_craft_legendary) IS DISTINCT FROM x."campFireCraftLegendary") AS "campFireCraftLegendary",
        count(*) FILTER (WHERE x."killPlayerGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_player_gain_vf_credit) IS DISTINCT FROM x."killPlayerGainVFCredit") AS "killPlayerGainVFCredit",
        count(*) FILTER (WHERE x."killChickenGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_chicken_gain_vf_credit) IS DISTINCT FROM x."killChickenGainVFCredit") AS "killChickenGainVFCredit",
        count(*) FILTER (WHERE x."killBoarGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_boar_gain_vf_credit) IS DISTINCT FROM x."killBoarGainVFCredit") AS "killBoarGainVFCredit",
        count(*) FILTER (WHERE x."killWildDogGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_wild_dog_gain_vf_credit) IS DISTINCT FROM x."killWildDogGainVFCredit") AS "killWildDogGainVFCredit",
        count(*) FILTER (WHERE x."killWolfGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_wolf_gain_vf_credit) IS DISTINCT FROM x."killWolfGainVFCredit") AS "killWolfGainVFCredit",
        count(*) FILTER (WHERE x."killBearGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_bear_gain_vf_credit) IS DISTINCT FROM x."killBearGainVFCredit") AS "killBearGainVFCredit",
        count(*) FILTER (WHERE x."killBatGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_bat_gain_vf_credit) IS DISTINCT FROM x."killBatGainVFCredit") AS "killBatGainVFCredit",
        count(*) FILTER (WHERE x."killRavenGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_raven_gain_vf_credit) IS DISTINCT FROM x."killRavenGainVFCredit") AS "killRavenGainVFCredit",
        count(*) FILTER (WHERE x."killWicklineGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_wickline_gain_vf_credit) IS DISTINCT FROM x."killWicklineGainVFCredit") AS "killWicklineGainVFCredit",
        count(*) FILTER (WHERE x."killAlphaGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_alpha_gain_vf_credit) IS DISTINCT FROM x."killAlphaGainVFCredit") AS "killAlphaGainVFCredit",
        count(*) FILTER (WHERE x."killOmegaGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_omega_gain_vf_credit) IS DISTINCT FROM x."killOmegaGainVFCredit") AS "killOmegaGainVFCredit",
        count(*) FILTER (WHERE x."killGammaGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_gamma_gain_vf_credit) IS DISTINCT FROM x."killGammaGainVFCredit") AS "killGammaGainVFCredit",
        count(*) FILTER (WHERE x."killDroneGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_drone_gain_vf_credit) IS DISTINCT FROM x."killDroneGainVFCredit") AS "killDroneGainVFCredit",
        count(*) FILTER (WHERE x."killItemBountyGainVFCredit" IS NOT NULL AND to_jsonb(p.kill_item_bounty_gain_vf_credit) IS DISTINCT FROM x."killItemBountyGainVFCredit") AS "killItemBountyGainVFCredit",
        count(*) FILTER (WHERE x."addTelephotoCamera" IS NOT NULL AND to_jsonb(p.add_telephoto_camera) IS DISTINCT FROM x."addTelephotoCamera") AS "addTelephotoCamera",
        count(*) FILTER (WHERE x."removeTelephotoCamera" IS NOT NULL AND to_jsonb(p.remove_telephoto_camera) IS DISTINCT FROM x."removeTelephotoCamera") AS "removeTelephotoCamera",
        count(*) FILTER (WHERE x."useHyperLoop" IS NOT NULL AND to_jsonb(p.use_hyper_loop) IS DISTINCT FROM x."useHyperLoop") AS "useHyperLoop",
        count(*) FILTER (WHERE x."useSecurityConsole" IS NOT NULL AND to_jsonb(p.use_security_console) IS DISTINCT FROM x."useSecurityConsole") AS "useSecurityConsole",
        count(*) FILTER (WHERE x."useReconDrone" IS NOT NULL AND to_jsonb(p.use_recon_drone) IS DISTINCT FROM x."useReconDrone") AS "useReconDrone",
        count(*) FILTER (WHERE x."useEmpDrone" IS NOT NULL AND to_jsonb(p.use_emp_drone) IS DISTINCT FROM x."useEmpDrone") AS "useEmpDrone",
        count(*) FILTER (WHERE x."tacticalSkillUseCount" IS NOT NULL AND to_jsonb(p.tactical_skill_use_count) IS DISTINCT FROM x."tacticalSkillUseCount") AS "tacticalSkillUseCount",
        count(*) FILTER (WHERE x."enterDimensionRift" IS NOT NULL AND to_jsonb(p.enter_dimension_rift) IS DISTINCT FROM x."enterDimensionRift") AS "enterDimensionRift",
        count(*) FILTER (WHERE x."winFromDimensionRift" IS NOT NULL AND to_jsonb(p.win_from_dimension_rift) IS DISTINCT FROM x."winFromDimensionRift") AS "winFromDimensionRift",
        count(*) FILTER (WHERE x."enterDimensionEmpoweredRift" IS NOT NULL AND to_jsonb(p.enter_dimension_empowered_rift) IS DISTINCT FROM x."enterDimensionEmpoweredRift") AS "enterDimensionEmpoweredRift",
        count(*) FILTER (WHERE x."winFromDimensionEmpoweredRift" IS NOT NULL AND to_jsonb(p.win_from_dimension_empowered_rift) IS DISTINCT FROM x."winFromDimensionEmpoweredRift") AS "winFromDimensionEmpoweredRift",
        count(*) FILTER (WHERE x."sumGetBuffCube" IS NOT NULL AND to_jsonb(p.sum_get_buff_cube) IS DISTINCT FROM x."sumGetBuffCube") AS "sumGetBuffCube",
        count(*) FILTER (WHERE x."traitFirstCore" IS NOT NULL AND to_jsonb(p.trait_first_core) IS DISTINCT FROM x."traitFirstCore") AS "traitFirstCore",
        count(*) FILTER (WHERE x."tacticalSkillGroup" IS NOT NULL AND to_jsonb(p.tactical_skill_group) IS DISTINCT FROM x."tacticalSkillGroup") AS "tacticalSkillGroup",
        count(*) FILTER (WHERE x."tacticalSkillLevel" IS NOT NULL AND to_jsonb(p.tactical_skill_level) IS DISTINCT FROM x."tacticalSkillLevel") AS "tacticalSkillLevel",
        count(*) FILTER (WHERE x."routeIdOfStart" IS NOT NULL AND to_jsonb(p.route_id_of_start) IS DISTINCT FROM x."routeIdOfStart") AS "routeIdOfStart",
        count(*) FILTER (WHERE x."ccTimeToPlayer" IS NOT NULL AND to_jsonb(p.cc_time_to_player) IS DISTINCT FROM x."ccTimeToPlayer") AS "ccTimeToPlayer",
        count(*) FILTER (WHERE x."attackSpeed" IS NOT NULL AND to_jsonb(p.attack_speed) IS DISTINCT FROM x."attackSpeed") AS "attackSpeed",
        count(*) FILTER (WHERE x."moveSpeed" IS NOT NULL AND to_jsonb(p.move_speed) IS DISTINCT FROM x."moveSpeed") AS "moveSpeed",
        count(*) FILTER (WHERE x."coolDownReduction" IS NOT NULL AND to_jsonb(p.cool_down_reduction) IS DISTINCT FROM x."coolDownReduction") AS "coolDownReduction",
        count(*) FILTER (WHERE x."criticalStrikeChance" IS NOT NULL AND to_jsonb(p.critical_strike_chance) IS DISTINCT FROM x."criticalStrikeChance") AS "criticalStrikeChance",
        count(*) FILTER (WHERE x."lifeSteal" IS NOT NULL AND to_jsonb(p.life_steal) IS DISTINCT FROM x."lifeSteal") AS "lifeSteal",
        count(*) FILTER (WHERE x."attackRange" IS NOT NULL AND to_jsonb(p.attack_range) IS DISTINCT FROM x."attackRange") AS "attackRange",
        count(*) FILTER (WHERE x."sightRange" IS NOT NULL AND to_jsonb(p.sight_range) IS DISTINCT FROM x."sightRange") AS "sightRange",
        count(*) FILTER (WHERE x."gambit" IS NOT NULL AND to_jsonb(p.gambit) IS DISTINCT FROM x."gambit") AS "gambit",
        count(*) FILTER (WHERE x."kingsGambit" IS NOT NULL AND to_jsonb(p.kings_gambit) IS DISTINCT FROM x."kingsGambit") AS "kingsGambit",
        count(*) FILTER (WHERE x."killGamma" IS NOT NULL AND to_jsonb(p.kill_gamma) IS DISTINCT FROM x."killGamma") AS "killGamma",
        count(*) FILTER (WHERE x."totalVFCredits" IS NOT NULL AND to_jsonb(p.total_vf_credits) IS DISTINCT FROM x."totalVFCredits") AS "totalVFCredits",
        count(*) FILTER (WHERE x."usedVFCredits" IS NOT NULL AND to_jsonb(p.used_vf_credits) IS DISTINCT FROM x."usedVFCredits") AS "usedVFCredits",
        count(*) FILTER (WHERE x."totalTKPerMin" IS NOT NULL AND to_jsonb(p.total_tk_per_min) IS DISTINCT FROM x."totalTKPerMin") AS "totalTKPerMin",
        count(*) FILTER (WHERE x."foodCraftCount" IS NOT NULL AND to_jsonb(p.food_craft_count) IS DISTINCT FROM x."foodCraftCount") AS "foodCraftCount",
        count(*) FILTER (WHERE x."itemTransferredDrone" IS NOT NULL AND to_jsonb(p.item_transferred_drone) IS DISTINCT FROM x."itemTransferredDrone") AS "itemTransferredDrone",
        count(*) FILTER (WHERE x."itemTransferredConsole" IS NOT NULL AND to_jsonb(p.item_transferred_console) IS DISTINCT FROM x."itemTransferredConsole") AS "itemTransferredConsole",
        count(*) FILTER (WHERE x."skillLevelInfo" IS NOT NULL AND to_jsonb(p.skill_level_info) IS DISTINCT FROM x."skillLevelInfo") AS "skillLevelInfo",
        count(*) FILTER (WHERE x."creditSource" IS NOT NULL AND to_jsonb(p.credit_source) IS DISTINCT FROM x."creditSource") AS "creditSource",
        count(*) FILTER (WHERE x."killMonsters" IS NOT NULL AND to_jsonb(p.kill_monsters) IS DISTINCT FROM x."killMonsters") AS "killMonsters",
        count(*) FILTER (WHERE x."matchSize" IS NOT NULL AND to_jsonb(g.match_size) IS DISTINCT FROM x."matchSize") AS "matchSize",
        count(*) FILTER (WHERE x."mmrAvg" IS NOT NULL AND to_jsonb(g.mmr_avg) IS DISTINCT FROM x."mmrAvg") AS "mmrAvg",
        count(*) FILTER (WHERE x."mainWeather" IS NOT NULL AND to_jsonb(g.main_weather) IS DISTINCT FROM x."mainWeather") AS "mainWeather",
        count(*) FILTER (WHERE x."subWeather" IS NOT NULL AND to_jsonb(g.sub_weather) IS DISTINCT FROM x."subWeather") AS "subWeather",
        count(*) FILTER (WHERE x."seasonId" IS NOT NULL AND to_jsonb(g.season_id) IS DISTINCT FROM x."seasonId") AS "seasonId",
        count(*) FILTER (WHERE x."matchingMode" IS NOT NULL AND to_jsonb(g.matching_mode) IS DISTINCT FROM x."matchingMode") AS "matchingMode",
        count(*) FILTER (WHERE x."matchingTeamMode" IS NOT NULL AND to_jsonb(g.matching_team_mode) IS DISTINCT FROM x."matchingTeamMode") AS "matchingTeamMode",
        count(*) FILTER (WHERE x."versionSeason" IS NOT NULL AND to_jsonb(g.version_season) IS DISTINCT FROM x."versionSeason") AS "versionSeason",
        count(*) FILTER (WHERE x."versionMajor" IS NOT NULL AND to_jsonb(g.version_major) IS DISTINCT FROM x."versionMajor") AS "versionMajor",
        count(*) FILTER (WHERE x."versionMinor" IS NOT NULL AND to_jsonb(g.version_minor) IS DISTINCT FROM x."versionMinor") AS "versionMinor",
        count(*) FILTER (WHERE to_jsonb(p.game_id) IS DISTINCT FROM x."gameId") AS "gameId",
        count(*) FILTER (WHERE to_jsonb(p.nickname) IS DISTINCT FROM x."nickname") AS "nickname",
        count(*) FILTER (WHERE x."serverName" IS NOT NULL AND to_jsonb(g.server_name) IS DISTINCT FROM x."serverName") AS "serverName",
        count(*) FILTER (WHERE x."placeOfStart" IS NOT NULL AND to_jsonb(p.place_of_start::text) IS DISTINCT FROM x."placeOfStart") AS "placeOfStart",
        count(*) FILTER (WHERE x."startDtm" IS NOT NULL AND (NOT pg_input_is_valid(x."startDtm" #>> '{}', 'timestamptz') OR (x."startDtm" #>> '{}')::timestamptz IS DISTINCT FROM g.start_dtm)) AS "startDtm"
        FROM participants p
        JOIN v4_todo t USING (participant_id)
        JOIN games g ON g.game_id = p.game_id
        CROSS JOIN LATERAL jsonb_to_record(p.raw) AS x(
        "teamNumber" jsonb, "characterNum" jsonb, "bestWeapon" jsonb, "bestWeaponLevel" jsonb,
        "gameRank" jsonb, "playerKill" jsonb, "playerAssistant" jsonb, "monsterKill" jsonb,
        "damageToPlayer" jsonb, "mmrBefore" jsonb, "mmrGain" jsonb, "mmrAfter" jsonb,
        "playTime" jsonb, "victory" jsonb, "characterLevel" jsonb, "skinCode" jsonb,
        "playerDeaths" jsonb, "teamKill" jsonb, "totalFieldKill" jsonb, "giveUp" jsonb,
        "escapeState" jsonb, "accountLevel" jsonb, "mmrGainInGame" jsonb, "mmrLossEntryCost" jsonb,
        "mmrGainGambit" jsonb, "watchTime" jsonb, "totalTime" jsonb, "duration" jsonb,
        "useEmoticonCount" jsonb, "damageToPlayer_basic" jsonb, "damageToPlayer_skill" jsonb, "damageToPlayer_itemSkill" jsonb,
        "damageToPlayer_direct" jsonb, "damageToMonster" jsonb, "damageFromPlayer" jsonb, "damageFromPlayer_basic" jsonb,
        "damageFromPlayer_skill" jsonb, "damageFromPlayer_itemSkill" jsonb, "damageFromPlayer_direct" jsonb, "damageFromMonster" jsonb,
        "damageOffsetedByShield_Player" jsonb, "healAmount" jsonb, "teamRecover" jsonb, "protectAbsorb" jsonb,
        "teamDown" jsonb, "teamElimination" jsonb, "terminateCount" jsonb, "clutchCount" jsonb,
        "killsPhaseOne" jsonb, "killsPhaseTwo" jsonb, "killsPhaseThree" jsonb, "deathsPhaseOne" jsonb,
        "deathsPhaseTwo" jsonb, "deathsPhaseThree" jsonb, "creditRevivalCount" jsonb, "creditRevivedOthersCount" jsonb,
        "maxHp" jsonb, "attackPower" jsonb, "defense" jsonb, "skillAmp" jsonb,
        "adaptiveForce" jsonb, "totalGainVFCredit" jsonb, "totalUseVFCredit" jsonb, "craftUncommon" jsonb,
        "craftRare" jsonb, "craftEpic" jsonb, "craftLegend" jsonb, "craftMythic" jsonb,
        "campFireCraftUncommon" jsonb, "campFireCraftRare" jsonb, "campFireCraftEpic" jsonb, "campFireCraftLegendary" jsonb,
        "killPlayerGainVFCredit" jsonb, "killChickenGainVFCredit" jsonb, "killBoarGainVFCredit" jsonb, "killWildDogGainVFCredit" jsonb,
        "killWolfGainVFCredit" jsonb, "killBearGainVFCredit" jsonb, "killBatGainVFCredit" jsonb, "killRavenGainVFCredit" jsonb,
        "killWicklineGainVFCredit" jsonb, "killAlphaGainVFCredit" jsonb, "killOmegaGainVFCredit" jsonb, "killGammaGainVFCredit" jsonb,
        "killDroneGainVFCredit" jsonb, "killItemBountyGainVFCredit" jsonb, "addTelephotoCamera" jsonb, "removeTelephotoCamera" jsonb,
        "useHyperLoop" jsonb, "useSecurityConsole" jsonb, "useReconDrone" jsonb, "useEmpDrone" jsonb,
        "tacticalSkillUseCount" jsonb, "enterDimensionRift" jsonb, "winFromDimensionRift" jsonb, "enterDimensionEmpoweredRift" jsonb,
        "winFromDimensionEmpoweredRift" jsonb, "sumGetBuffCube" jsonb, "traitFirstCore" jsonb, "tacticalSkillGroup" jsonb,
        "tacticalSkillLevel" jsonb, "routeIdOfStart" jsonb, "ccTimeToPlayer" jsonb, "attackSpeed" jsonb,
        "moveSpeed" jsonb, "coolDownReduction" jsonb, "criticalStrikeChance" jsonb, "lifeSteal" jsonb,
        "attackRange" jsonb, "sightRange" jsonb, "gambit" jsonb, "kingsGambit" jsonb,
        "killGamma" jsonb, "totalVFCredits" jsonb, "usedVFCredits" jsonb, "totalTKPerMin" jsonb,
        "foodCraftCount" jsonb, "itemTransferredDrone" jsonb, "itemTransferredConsole" jsonb, "skillLevelInfo" jsonb,
        "creditSource" jsonb, "killMonsters" jsonb, "matchSize" jsonb, "mmrAvg" jsonb,
        "mainWeather" jsonb, "subWeather" jsonb, "seasonId" jsonb, "matchingMode" jsonb,
        "matchingTeamMode" jsonb, "versionSeason" jsonb, "versionMajor" jsonb, "versionMinor" jsonb,
        "gameId" jsonb, "nickname" jsonb, "serverName" jsonb, "placeOfStart" jsonb,
        "startDtm" jsonb
        )
    ) c
) x, jsonb_each_text(x.j) e
WHERE e.value::bigint > 0;

-- Child tables are aggregated once and joined, so the check does not depend on per-row lookups.
ANALYZE participant_equipment, participant_traits, participant_mastery, participant_matchups,
        participant_deaths;

INSERT INTO v4_mismatch
WITH src AS MATERIALIZED (
    SELECT p.participant_id, p.skill_order, p.trait_first_core, x.*
    FROM participants p
    JOIN v4_todo t USING (participant_id)
    CROSS JOIN LATERAL jsonb_to_record(p.raw) AS x(
        "skillOrderInfo" jsonb, "equipment" jsonb, "equipmentGrade" jsonb, "equipFirstItemForLog" jsonb,
        "traitFirstSub" jsonb, "traitSecondSub" jsonb, "masteryLevel" jsonb,
        "killDetails" text, "deathDetails" text,
        "killer" text, "killerCharacter" text, "killerWeapon" text, "causeOfDeath" text, "placeOfDeath" text,
        "killer2" text, "killerCharacter2" text, "killerWeapon2" text, "causeOfDeath2" text, "placeOfDeath2" text,
        "killer3" text, "killerCharacter3" text, "killerWeapon3" text, "causeOfDeath3" text, "placeOfDeath3" text
    )
), eq AS (
    SELECT q.participant_id,
           jsonb_object_agg(q.slot::text, q.item_code) FILTER (WHERE q.kind = 'FINAL') AS final_items,
           jsonb_object_agg(q.slot::text, q.grade) FILTER (WHERE q.kind = 'FINAL') AS final_grades,
           jsonb_agg(jsonb_build_array(q.slot::integer, q.item_code) ORDER BY q.slot, q.item_code)
               FILTER (WHERE q.kind = 'FIRST') AS first_items
    FROM participant_equipment q
    JOIN v4_todo t USING (participant_id)
    GROUP BY q.participant_id
), tr AS (
    SELECT q.participant_id,
           jsonb_agg(q.trait_code ORDER BY q.trait_code) FILTER (WHERE q.slot_type = 'FIRST_SUB') AS first_sub,
           jsonb_agg(q.trait_code ORDER BY q.trait_code) FILTER (WHERE q.slot_type = 'SECOND_SUB') AS second_sub,
           max(q.trait_code) FILTER (WHERE q.slot_type = 'CORE') AS core
    FROM participant_traits q
    JOIN v4_todo t USING (participant_id)
    GROUP BY q.participant_id
), ma AS (
    SELECT q.participant_id, jsonb_object_agg(q.mastery_code::text, q.mastery_level) AS levels
    FROM participant_mastery q
    JOIN v4_todo t USING (participant_id)
    GROUP BY q.participant_id
), mu AS (
    SELECT q.participant_id,
           jsonb_object_agg(q.opponent_character_num::text, q.kills) FILTER (WHERE q.kills <> 0) AS kills,
           jsonb_object_agg(q.opponent_character_num::text, q.deaths) FILTER (WHERE q.deaths <> 0) AS deaths
    FROM participant_matchups q
    JOIN v4_todo t USING (participant_id)
    GROUP BY q.participant_id
)
SELECT e.key, e.value::bigint
FROM (
    SELECT to_jsonb(c) AS j FROM (
        SELECT
        count(*) FILTER (WHERE coalesce(to_jsonb(s.skill_order), '[]'::jsonb) IS DISTINCT FROM
            (SELECT coalesce(jsonb_agg(o.value ORDER BY o.key::integer), '[]'::jsonb)
             FROM jsonb_each(CASE WHEN jsonb_typeof(s."skillOrderInfo") = 'object'
                                  THEN s."skillOrderInfo" ELSE '{}'::jsonb END) o)) AS "skillOrderInfo",
        count(*) FILTER (WHERE coalesce(s."equipment", '{}'::jsonb)
            IS DISTINCT FROM coalesce(eq.final_items, '{}'::jsonb)) AS "equipment",
        count(*) FILTER (WHERE coalesce(s."equipmentGrade", '{}'::jsonb)
            IS DISTINCT FROM coalesce(eq.final_grades, '{}'::jsonb)) AS "equipmentGrade",
        count(*) FILTER (WHERE coalesce(eq.first_items, '[]'::jsonb) IS DISTINCT FROM
            (SELECT coalesce(jsonb_agg(jsonb_build_array(r.key::integer, i.value::numeric::integer)
                                       ORDER BY r.key::integer, i.value::numeric::integer), '[]'::jsonb)
             FROM jsonb_each(CASE WHEN jsonb_typeof(s."equipFirstItemForLog") = 'object'
                                  THEN s."equipFirstItemForLog" ELSE '{}'::jsonb END) r,
                  jsonb_array_elements_text(r.value) i)) AS "equipFirstItemForLog",
        count(*) FILTER (WHERE coalesce(tr.first_sub, '[]'::jsonb) IS DISTINCT FROM
            (SELECT coalesce(jsonb_agg(v.value::numeric::integer ORDER BY v.value::numeric::integer), '[]'::jsonb)
             FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(s."traitFirstSub") = 'array'
                                                 THEN s."traitFirstSub" ELSE '[]'::jsonb END) v)) AS "traitFirstSub",
        count(*) FILTER (WHERE coalesce(tr.second_sub, '[]'::jsonb) IS DISTINCT FROM
            (SELECT coalesce(jsonb_agg(v.value::numeric::integer ORDER BY v.value::numeric::integer), '[]'::jsonb)
             FROM jsonb_array_elements_text(CASE WHEN jsonb_typeof(s."traitSecondSub") = 'array'
                                                 THEN s."traitSecondSub" ELSE '[]'::jsonb END) v)) AS "traitSecondSub",
        count(*) FILTER (WHERE s.trait_first_core IS DISTINCT FROM tr.core) AS "traitCore",
        count(*) FILTER (WHERE coalesce(s."masteryLevel", '{}'::jsonb)
            IS DISTINCT FROM coalesce(ma.levels, '{}'::jsonb)) AS "masteryLevel",
        count(*) FILTER (WHERE coalesce(NULLIF(s."killDetails", '')::jsonb, '{}'::jsonb)
            IS DISTINCT FROM coalesce(mu.kills, '{}'::jsonb)) AS "killDetails",
        count(*) FILTER (WHERE coalesce(NULLIF(s."deathDetails", '')::jsonb, '{}'::jsonb)
            IS DISTINCT FROM coalesce(mu.deaths, '{}'::jsonb)) AS "deathDetails",
        count(*) FILTER (WHERE (coalesce(s."killer", '') <> '') IS DISTINCT FROM (d1.participant_id IS NOT NULL) OR coalesce(s."killer", '') IS DISTINCT FROM coalesce(d1.killer_type, '') OR coalesce(s."killerCharacter", '') IS DISTINCT FROM coalesce(d1.killer_name, '') OR coalesce(s."killerWeapon", '') IS DISTINCT FROM coalesce(d1.killer_weapon, '') OR coalesce(s."causeOfDeath", '') IS DISTINCT FROM coalesce(d1.cause_of_death, '') OR coalesce(s."placeOfDeath", '') IS DISTINCT FROM coalesce(d1.place_of_death::text, '')) AS "death1",
        count(*) FILTER (WHERE (coalesce(s."killer2", '') <> '') IS DISTINCT FROM (d2.participant_id IS NOT NULL) OR coalesce(s."killer2", '') IS DISTINCT FROM coalesce(d2.killer_type, '') OR coalesce(s."killerCharacter2", '') IS DISTINCT FROM coalesce(d2.killer_name, '') OR coalesce(s."killerWeapon2", '') IS DISTINCT FROM coalesce(d2.killer_weapon, '') OR coalesce(s."causeOfDeath2", '') IS DISTINCT FROM coalesce(d2.cause_of_death, '') OR coalesce(s."placeOfDeath2", '') IS DISTINCT FROM coalesce(d2.place_of_death::text, '')) AS "death2",
        count(*) FILTER (WHERE (coalesce(s."killer3", '') <> '') IS DISTINCT FROM (d3.participant_id IS NOT NULL) OR coalesce(s."killer3", '') IS DISTINCT FROM coalesce(d3.killer_type, '') OR coalesce(s."killerCharacter3", '') IS DISTINCT FROM coalesce(d3.killer_name, '') OR coalesce(s."killerWeapon3", '') IS DISTINCT FROM coalesce(d3.killer_weapon, '') OR coalesce(s."causeOfDeath3", '') IS DISTINCT FROM coalesce(d3.cause_of_death, '') OR coalesce(s."placeOfDeath3", '') IS DISTINCT FROM coalesce(d3.place_of_death::text, '')) AS "death3"
        FROM src s
        LEFT JOIN eq USING (participant_id)
        LEFT JOIN tr USING (participant_id)
        LEFT JOIN ma USING (participant_id)
        LEFT JOIN mu USING (participant_id)
        LEFT JOIN participant_deaths d1 ON d1.participant_id = s.participant_id AND d1.death_seq = 1
        LEFT JOIN participant_deaths d2 ON d2.participant_id = s.participant_id AND d2.death_seq = 2
        LEFT JOIN participant_deaths d3 ON d3.participant_id = s.participant_id AND d3.death_seq = 3
    ) c
) x, jsonb_each_text(x.j) e
WHERE e.value::bigint > 0;

DO $$
DECLARE
    report text;
BEGIN
    SELECT string_agg(what || '=' || rows_differing, ', ' ORDER BY what) INTO report FROM v4_mismatch;
    IF report IS NOT NULL THEN
        RAISE EXCEPTION 'V4 verification failed, rows differing per key: %', report;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 6. Remove the moved keys from raw.
-- ---------------------------------------------------------------------------
UPDATE participants p
SET raw = p.raw - ARRAY[
        'gameId', 'nickname', 'placeOfStart', 'startDtm', 'serverName', 'teamNumber',
        'characterNum', 'bestWeapon', 'bestWeaponLevel', 'gameRank', 'playerKill', 'playerAssistant',
        'monsterKill', 'damageToPlayer', 'mmrBefore', 'mmrGain', 'mmrAfter', 'playTime',
        'victory', 'characterLevel', 'skinCode', 'playerDeaths', 'teamKill', 'totalFieldKill',
        'giveUp', 'escapeState', 'accountLevel', 'mmrGainInGame', 'mmrLossEntryCost', 'mmrGainGambit',
        'watchTime', 'totalTime', 'duration', 'useEmoticonCount', 'damageToPlayer_basic', 'damageToPlayer_skill',
        'damageToPlayer_itemSkill', 'damageToPlayer_direct', 'damageToMonster', 'damageFromPlayer', 'damageFromPlayer_basic', 'damageFromPlayer_skill',
        'damageFromPlayer_itemSkill', 'damageFromPlayer_direct', 'damageFromMonster', 'damageOffsetedByShield_Player', 'healAmount', 'teamRecover',
        'protectAbsorb', 'teamDown', 'teamElimination', 'terminateCount', 'clutchCount', 'killsPhaseOne',
        'killsPhaseTwo', 'killsPhaseThree', 'deathsPhaseOne', 'deathsPhaseTwo', 'deathsPhaseThree', 'creditRevivalCount',
        'creditRevivedOthersCount', 'maxHp', 'attackPower', 'defense', 'skillAmp', 'adaptiveForce',
        'totalGainVFCredit', 'totalUseVFCredit', 'craftUncommon', 'craftRare', 'craftEpic', 'craftLegend',
        'craftMythic', 'campFireCraftUncommon', 'campFireCraftRare', 'campFireCraftEpic', 'campFireCraftLegendary', 'killPlayerGainVFCredit',
        'killChickenGainVFCredit', 'killBoarGainVFCredit', 'killWildDogGainVFCredit', 'killWolfGainVFCredit', 'killBearGainVFCredit', 'killBatGainVFCredit',
        'killRavenGainVFCredit', 'killWicklineGainVFCredit', 'killAlphaGainVFCredit', 'killOmegaGainVFCredit', 'killGammaGainVFCredit', 'killDroneGainVFCredit',
        'killItemBountyGainVFCredit', 'addTelephotoCamera', 'removeTelephotoCamera', 'useHyperLoop', 'useSecurityConsole', 'useReconDrone',
        'useEmpDrone', 'tacticalSkillUseCount', 'enterDimensionRift', 'winFromDimensionRift', 'enterDimensionEmpoweredRift', 'winFromDimensionEmpoweredRift',
        'sumGetBuffCube', 'traitFirstCore', 'tacticalSkillGroup', 'tacticalSkillLevel', 'routeIdOfStart', 'ccTimeToPlayer',
        'attackSpeed', 'moveSpeed', 'coolDownReduction', 'criticalStrikeChance', 'lifeSteal', 'attackRange',
        'sightRange', 'gambit', 'kingsGambit', 'killGamma', 'totalVFCredits', 'usedVFCredits',
        'totalTKPerMin', 'foodCraftCount', 'itemTransferredDrone', 'itemTransferredConsole', 'skillLevelInfo', 'creditSource',
        'killMonsters', 'matchSize', 'mmrAvg', 'mainWeather', 'subWeather', 'seasonId',
        'matchingMode', 'matchingTeamMode', 'versionSeason', 'versionMajor', 'versionMinor', 'equipment',
        'equipmentGrade', 'equipFirstItemForLog', 'traitFirstSub', 'traitSecondSub', 'masteryLevel', 'killDetails',
        'deathDetails', 'skillOrderInfo', 'killer', 'killerCharacter', 'killerWeapon', 'causeOfDeath',
        'placeOfDeath', 'killer2', 'killerCharacter2', 'killerWeapon2', 'causeOfDeath2', 'placeOfDeath2',
        'killer3', 'killerCharacter3', 'killerWeapon3', 'causeOfDeath3', 'placeOfDeath3'
    ]
WHERE p.raw ? 'characterNum';

-- ---------------------------------------------------------------------------
-- 7. Privileges. agent_ro reads participants without nickname / raw / is_ranker and cannot
--    read rankers or users. Columns added to participants later must be granted explicitly.
--    020-roles.sh grants the same privileges on a fresh volume.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    agent_columns text;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'collector') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            users, tiers, participant_equipment, participant_traits, participant_mastery,
            participant_matchups, participant_deaths
        TO collector;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO collector;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_ro') THEN
        REVOKE ALL ON participants, rankers, users FROM agent_ro;
        SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
        INTO agent_columns
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'participants'
          AND column_name NOT IN ('nickname', 'raw', 'is_ranker');
        EXECUTE format('GRANT SELECT (%s) ON participants TO agent_ro', agent_columns);
        GRANT SELECT ON
            games, characters, weapon_types, tiers, participant_equipment, participant_traits,
            participant_mastery, participant_matchups, participant_deaths
        TO agent_ro;
    END IF;
END
$$;

COMMIT;
