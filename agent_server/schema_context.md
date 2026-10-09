# ER analytics query schema (V4)

Only the tables and columns below may be used. SQL runs as agent_ro with a 5-second statement timeout and at most 200 returned rows.

Player nicknames, the raw response, ranker flags, users, rankers, collection/ API logs, migration history and system catalogs are inaccessible. Do not query them or try whole-row/JSON conversion to recover them.

## `games`

One row per match. game_id is the key. season_id is the ranked season; matching_mode=3 and matching_team_mode=3 mean ranked squad. match_size is the reported player count; team_count is COUNT(DISTINCT team_number), not a fixed eight. version_major/version_minor describe patches within a season. start_dtm is timestamptz; use AT TIME ZONE 'Asia/Seoul' for calendar dates and hours. fetched_at is ingestion time, not match start time.

Allowed columns:
- `game_id`, `season_id`, `matching_mode`, `matching_team_mode`, `version_season`, `version_major`
- `version_minor`, `start_dtm`, `server_name`, `match_size`, `team_count`, `mmr_avg`
- `main_weather`, `sub_weather`, `fetched_at`

## `participants`

One row per player per match. participant_id is the numeric key for child joins; game_id joins games. user_id is an anonymous identifier; never guess a nickname from it or answer individual-player-history questions. team_number identifies teammates only within the same game. character_num joins characters.character_code; best_weapon joins weapon_types.code when metadata is available. tier/tier_division are snapshots from mmr_before at match time; version_major/version_minor are copied from games. game_rank=1 means a win. play_time/watch_time/total_time/duration are participant values in seconds; duration may differ between players. Integer 0/1 values remain integers; gambit, kings_gambit and kill_gamma are booleans. The *_credits and other array columns keep ordered values; arrays are 1-indexed in PostgreSQL. skill_order lists skill codes in level-up order. skill_level_info, credit_source and kill_monsters are JSONB maps. Missing values are NULL; do not invent values.

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

Character code to Korean/English official names. Join participants.character_num or opponent/killer character codes to character_code. Names may be NULL.

Allowed columns:
- `character_code`, `name_ko`, `name_en`

## `weapon_types`

Weapon mastery code to official Korean name. Metadata may be empty until step 6; preserve codes when a name is unavailable.

Allowed columns:
- `code`, `name_ko`

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

## Tier and metric rules

- Determine participant tier from mmr_before at match time, using the saved tier/tier_division or the season-specific tiers table. Do not use current/latest player MMR.
- Platinum: 3600 <= MMR < 5000; Diamond: 5000 <= MMR < 6400; Meteorite: 6400 <= MMR < 7600; Mithril or above: MMR >= 7600.
- Division 4 to 1 lower bounds: Platinum 3600/3950/4300/4650; Diamond 5000/5350/5700/6050; Meteorite 6400/6700/7000/7300. Demigod/Eternity cannot be separated by MMR.
- MMR below 3600 is stored as 플래티넘 미만. NULL MMR has unknown tier. Both remain in aggregates without a tier condition; do not silently exclude them.
- A win is game_rank=1; TOP 3 is game_rank<=3. Rates use participant appearances as the denominator for participant/character statistics. For team-level questions, first deduplicate (game_id, team_number) and disclose that counting unit.
- Average rank is unnormalized. When comparing average rank, also return win rate and TOP 3 rate; actual teams may number seven or eight.
- Include the sample size. Warn for fewer than 30 participant appearances. Avoid counting a participant repeatedly after a one-to-many child join.
- Patch means a version inside a ranked season, not season_id. Preserve version_major and version_minor when comparing patches.

## SQL output rules

1. Return one PostgreSQL SELECT statement in one SQL code block, with no prose. Non-recursive WITH clauses containing SELECT only are allowed; recursive/data-changing CTEs are forbidden.
2. Use explicit allowed columns and qualified references for joins. SELECT * and whole-row references are forbidden; COUNT(*) is allowed.
3. Use NULLIF for division by zero. Use only ordinary analytics functions (aggregates, arithmetic, CASE, dates, strings, arrays and JSON from allowed columns). SQL-string execution, session, file and system functions are forbidden.
4. Display official character names by joining characters; keep numeric codes when metadata is absent. Items/traits/skills/regions/weather name tables are not available yet; do not invent tables.
5. For a KST date/hour use (start_dtm AT TIME ZONE 'Asia/Seoul')::date or EXTRACT(HOUR FROM start_dtm AT TIME ZONE 'Asia/Seoul'). Never cast start_dtm directly to date.
6. Only data-backed aggregate questions are in scope. Individual player history and nickname lookup are out of scope. Samples are examples; never filter by their anonymous IDs.
