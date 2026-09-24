# ER analytics query schema

Only the following PostgreSQL tables may be queried.

- `games`: one row per match.
  - `game_id`: match identifier.
  - `season_id`: ranked season identifier.
  - `matching_mode`: 3 means ranked.
  - `matching_team_mode`: 3 means squad.
  - `version_major`, `version_minor`: client/patch version fields.
  - `start_dtm`: match start time.
  - `server_name`: server name.
- `participants`: one row per player in a match.
  - `game_id`: joins to `games.game_id`.
  - `nickname`: player nickname. Prompt samples are always masked.
  - `team_number`: players with the same value were teammates in that match.
  - `character_num`: joins to `characters.character_code`.
  - `best_weapon`: joins to `weapon_types.code` when metadata exists.
  - `game_rank`: final team rank; 1 means a win.
  - `player_kill`, `player_assistant`, `monster_kill`, `damage_to_player`: match metrics.
  - `mmr_before`, `mmr_gain`, `mmr_after`: nullable MMR fields.
  - `play_time`: play time in seconds.
  - `is_ranker`: whether nickname matched the collected ranker snapshot; this can be inaccurate after nickname changes.
- `rankers`: latest collected top-ranker snapshot (`uid`, `nickname`, `rank`, `mmr`, `season_id`).
- `characters`: character code to Korean/English display name (`character_code`, `name_ko`, `name_en`).
- `weapon_types`: weapon code to Korean display name (`code`, `name_ko`).

Rules:

1. Return exactly one PostgreSQL `SELECT` statement in one SQL code block and no prose.
2. Query only the listed tables and columns. Never use system catalogs.
3. Use `NULLIF` where division by zero is possible.
4. Prefer character names by joining `characters`; keep codes when metadata is absent.
5. Limit detail queries. Aggregate queries may return fewer rows naturally.
