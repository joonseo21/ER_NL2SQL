# ER analytics query schema (V5)

Only the tables and columns below may be used. SQL runs as agent_ro with a 20-second statement timeout and at most 200 returned rows.

Player nicknames, the raw response, ranker flags, users, rankers, collection/ API logs, migration history and system catalogs are inaccessible. Do not query them or try whole-row/JSON conversion to recover them.

## `games`

One row per match. game_id is the key. season_id is the ranked season; matching_mode=3 and matching_team_mode=3 mean ranked squad. match_size is the reported player count; team_count is COUNT(DISTINCT team_number), not a fixed eight. version_major/version_minor describe patches within a season. start_dtm is timestamptz; use AT TIME ZONE 'Asia/Seoul' for calendar dates and hours. fetched_at is ingestion time, not match start time.

Allowed columns:
- `game_id`, `season_id`, `matching_mode`, `matching_team_mode`, `version_season`, `version_major`
- `version_minor`, `start_dtm`, `server_name`, `match_size`, `team_count`, `mmr_avg`
- `main_weather`, `sub_weather`, `fetched_at`

## `participants`

One row per player per match. participant_id is the numeric key for child joins; game_id joins games. user_id is an anonymous identifier; never guess a nickname from it or answer individual-player-history questions. team_number identifies teammates only within the same game. character_num joins characters.character_code; best_weapon joins weapon_types.code when metadata is available. tier/tier_division are snapshots from mmr_before at match time; version_major/version_minor are copied from games. game_rank=1 means a win. play_time/watch_time/total_time/duration are participant values in seconds; duration may differ between players. Integer 0/1 values remain integers; gambit, kings_gambit and kill_gamma are booleans. The *_credits and other array columns keep ordered values; arrays are 1-indexed in PostgreSQL. skill_order lists skill codes in level-up order. skill_level_info, credit_source and kill_monsters are JSONB maps. kill_monsters maps a wild-animal code (text keys '1'..'12') to kills; no name table exists, and the codes are given in metrics.md. trait_first_core is the core trait. Missing values are NULL; do not invent values. Missing values are NULL; do not invent values.

Allowed columns:
- `game_id`, `team_number`, `character_num`, `best_weapon`, `best_weapon_level`, `game_rank`
- `player_kill`, `player_assistant`, `monster_kill`, `damage_to_player`, `mmr_before`, `mmr_gain`
- `mmr_after`, `play_time`, `participant_id`, `user_id`, `version_major`, `version_minor`
- `tier`, `tier_division`, `victory`, `character_level`, `skin_code`, `player_deaths`
- `team_kill`, `total_field_kill`, `give_up`, `escape_state`, `account_level`, `mmr_gain_in_game`
- `mmr_loss_entry_cost`, `gambit`, `kings_gambit`, `mmr_gain_gambit`, `watch_time`, `total_time`
- `duration`, `use_emoticon_count`, `damage_to_player_basic`, `damage_to_player_skill`, `damage_to_player_item_skill`, `damage_to_player_direct`
- `damage_to_monster`, `damage_from_player`, `damage_from_player_basic`, `damage_from_player_skill`, `damage_from_player_item_skill`, `damage_from_player_direct`
- `damage_from_monster`, `damage_offseted_by_shield_player`, `heal_amount`, `team_recover`, `protect_absorb`, `cc_time_to_player`
- `team_down`, `team_elimination`, `terminate_count`, `clutch_count`, `kills_phase_one`, `kills_phase_two`
- `kills_phase_three`, `deaths_phase_one`, `deaths_phase_two`, `deaths_phase_three`, `credit_revival_count`, `credit_revived_others_count`
- `max_hp`, `attack_power`, `defense`, `attack_speed`, `move_speed`, `skill_amp`
- `adaptive_force`, `cool_down_reduction`, `critical_strike_chance`, `life_steal`, `attack_range`, `sight_range`
- `total_gain_vf_credit`, `total_use_vf_credit`, `craft_uncommon`, `craft_rare`, `craft_epic`, `craft_legend`
- `craft_mythic`, `camp_fire_craft_uncommon`, `camp_fire_craft_rare`, `camp_fire_craft_epic`, `camp_fire_craft_legendary`, `kill_player_gain_vf_credit`
- `kill_chicken_gain_vf_credit`, `kill_boar_gain_vf_credit`, `kill_wild_dog_gain_vf_credit`, `kill_wolf_gain_vf_credit`, `kill_bear_gain_vf_credit`, `kill_bat_gain_vf_credit`
- `kill_raven_gain_vf_credit`, `kill_wickline_gain_vf_credit`, `kill_alpha_gain_vf_credit`, `kill_omega_gain_vf_credit`, `kill_gamma_gain_vf_credit`, `kill_drone_gain_vf_credit`
- `kill_item_bounty_gain_vf_credit`, `add_telephoto_camera`, `remove_telephoto_camera`, `use_hyper_loop`, `use_security_console`, `use_recon_drone`
- `use_emp_drone`, `tactical_skill_use_count`, `kill_gamma`, `enter_dimension_rift`, `win_from_dimension_rift`, `enter_dimension_empowered_rift`
- `win_from_dimension_empowered_rift`, `sum_get_buff_cube`, `trait_first_core`, `tactical_skill_group`, `tactical_skill_level`, `route_id_of_start`
- `place_of_start`, `skill_order`, `total_vf_credits`, `used_vf_credits`, `total_tk_per_min`, `food_craft_count`
- `item_transferred_drone`, `item_transferred_console`, `skill_level_info`, `credit_source`, `kill_monsters`

## `characters`

Character code to Korean/English official names. Join participants.character_num or opponent/killer character codes to character_code. Names may be NULL. All name tables hold the latest confirmed names; removed codes retain their last names. source and l10n_key identify name provenance; checked_at is the confirmation time, not match time. They do not guarantee names or stats at a historical patch.

Allowed columns:
- `character_code`, `name_ko`, `name_en`, `source`, `l10n_key`, `checked_at`

## `weapon_types`

Weapon mastery code to official Korean name. Join best_weapon to code; 16 is TwoHandSword, 12 is not a weapon. type_key is the API weapon type. Preserve codes when a name is unavailable. Other mastery codes include crafting, searching, movement, defense and hunting; do not label them as weapons.

Allowed columns:
- `code`, `name_ko`, `type_key`, `source`, `l10n_key`, `checked_at`

## `items`

Join participant_equipment.item_code to code. Includes current equipment and older/non-equipment item names. item_type is Weapon/Armor for current equipment; item_grade is its current metadata grade, and grade_name_ko is the official grade label. equipment_type is the weapon type or armor slot type. Attributes can be NULL for old or non-equipment codes. Historical FINAL grade remains participant_equipment.grade; do not replace it with the current item_grade. Use LEFT JOIN to preserve unnamed codes.

Allowed columns:
- `code`, `name_ko`, `source`, `l10n_key`, `checked_at`, `item_type`, `item_grade`, `grade_name_ko`, `equipment_type`

## `trait_names`

Join participant_traits.trait_code or participants.trait_first_core to code. trait_type (for example Core), trait_group (for example Havoc), and active describe current API metadata. Older names may lack attributes. Child joins can multiply participant counts.

Allowed columns:
- `code`, `name_ko`, `source`, `l10n_key`, `checked_at`, `trait_type`, `trait_group`, `active`

## `tactical_skills`

Join participants.tactical_skill_group to code. skill_group_code is the verified localization skill group for that tactical group. Some ambiguous/currently unnamed source groups are absent; preserve numeric codes through LEFT JOIN. Do not derive names from icon identifiers.

Allowed columns:
- `code`, `name_ko`, `source`, `l10n_key`, `checked_at`, `skill_group_code`

## `skill_groups`

Join an unnested participants.skill_order element to code exactly. No universal suffix trimming or code-to-name guessing is valid. Array order and repeated level-up entries are meaningful; count distinct participants when measuring skill usage.

Allowed columns:
- `code`, `name_ko`, `source`, `l10n_key`, `checked_at`

## `areas`

Join participants.place_of_start or participant_deaths.place_of_death to code. name_en is the API area enum; name_ko is the official localized name.

Allowed columns:
- `code`, `name_ko`, `source`, `l10n_key`, `checked_at`, `name_en`

## `weather_types`

Join games.main_weather or games.sub_weather to code. Zero/missing weather codes remain unknown; do not fabricate a name.

Allowed columns:
- `code`, `name_ko`, `source`, `l10n_key`, `checked_at`

## `tiers`

Tier lower boundaries per season. (season_id, tier, division) is the key; min_mmr is inclusive. The next higher min_mmr is the exclusive upper bound. division=0 means no subdivision. This is boundary metadata, not player counts.

Allowed columns:
- `season_id`, `tier`, `division`, `min_mmr`, `sort_order`

## `participant_equipment`

Join participant_id to participants.participant_id. kind=FINAL means equipment at match end; FIRST means first equipment records. slot: 0 weapon, 1 chest, 2 head, 3 arm, 4 leg. grade exists for FINAL records. Multiple item codes may occur for a slot in FIRST. This table does not represent every equipment change.

Allowed columns:
- `participant_id`, `kind`, `slot`, `item_code`, `grade`

## `participant_traits`

Join participant_id. slot_type is CORE, FIRST_SUB, SECOND_SUB. trait_code is the trait identifier. Joining traits directly multiplies participant rows; use EXISTS for filtering or COUNT(DISTINCT participant_id) for participant samples.

Allowed columns:
- `participant_id`, `slot_type`, `trait_code`

## `participant_mastery`

Join participant_id. One mastery_code with mastery_level per participant; mastery_code is not necessarily a weapon code.

Allowed columns:
- `participant_id`, `mastery_code`, `mastery_level`

## `participant_matchups`

Join participant_id. character_num is the participant character; opponent_character_num joins characters. kills/deaths are counts against that character, not links to individual opponent players. The death total need not match player_deaths.

Allowed columns:
- `participant_id`, `character_num`, `opponent_character_num`, `kills`, `deaths`

## `participant_deaths`

Join participant_id. death_seq is 1, 2 or 3. killer_type distinguishes player, wildAnimal, restrictedArea and other source values. killer_participant_id optionally joins participants.participant_id within the same match; NULL means unresolved or non-player. killer_character_num joins characters. killer_name is the original killerCharacter (character/animal/object name), never a player nickname. killer_weapon and cause_of_death are legacy source strings; place_of_death is a region code. Legacy fields are not a guaranteed complete event timeline.

Allowed columns:
- `participant_id`, `death_seq`, `killer_type`, `killer_participant_id`, `killer_character_num`, `killer_name`
- `killer_weapon`, `cause_of_death`, `place_of_death`

## Metric and terminology definitions

The prompt supplies metrics.md as the authoritative metric definitions. Follow its
pick-rate denominator, win/TOP 3 rates, tier boundaries, patch scope and sample rules.
This schema file describes storage and access; metric policy is maintained in metrics.md.

## SQL output rules

1. Return one PostgreSQL SELECT statement in one SQL code block, with no prose. Non-recursive WITH clauses containing SELECT only are allowed; recursive/data-changing CTEs are forbidden.
2. Use explicit allowed columns and qualified references for joins. SELECT * and whole-row references are forbidden; COUNT(*) is allowed.
3. Use NULLIF for division by zero. Use only ordinary analytics functions (aggregates, arithmetic, CASE, dates, strings, arrays and JSON from allowed columns). SQL-string execution, session, file and system functions are forbidden.
4. Display official names through the name tables below; preserve numeric codes when a name is absent. Metadata holds the latest confirmed name, not the name/stats at the match patch. Do not guess skill aliases or invent tables.
5. For a KST date/hour use (start_dtm AT TIME ZONE 'Asia/Seoul')::date or EXTRACT(HOUR FROM start_dtm AT TIME ZONE 'Asia/Seoul'). Never cast start_dtm directly to date.
6. Only data-backed aggregate questions are in scope. Individual player history and nickname lookup are out of scope. Samples are examples; never filter by their anonymous IDs.
7. Performance (participants holds over a million rows): do not reference a participants-scale CTE more than once, because PostgreSQL then materializes it and loses its indexes. Compute totals with window functions such as SUM(COUNT(*)) OVER (...), or declare the CTE AS NOT MATERIALIZED. Do not run a correlated EXISTS per participant row against participants; first collect the other side (for example the opponent character's (game_id, team_number) rows) in a CTE and JOIN to it. Read JSONB keys directly (kill_monsters ->> '1') instead of expanding every row with jsonb_each.
8. UNION/INTERSECT/EXCEPT and GROUPING SETS/ROLLUP/CUBE/GROUPING() are not allowed. Put alternatives side by side as columns with FILTER or CASE, or group by a CASE expression.
