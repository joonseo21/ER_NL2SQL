# ER analytics query schema

Only the following PostgreSQL tables may be queried.

- `games`: one row per match.
  - `game_id`: match identifier.
  - `season_id`: ranked season identifier.
  - `matching_mode`: 3 means ranked.
  - `matching_team_mode`: 3 means squad.
  - `version_major`, `version_minor`: client/patch version fields.
  - `start_dtm`: match start time, a `timestamptz` column (time-zone-aware absolute instant; the
    source is KST +09:00). NULL when the source value was missing or invalid. Do not rely on the
    session time zone: convert with `start_dtm AT TIME ZONE 'Asia/Seoul'` before extracting a date or hour.
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
- `characters`: character code to Korean/English display name (`character_code`, `name_ko`, `name_en`).
- `weapon_types`: weapon code to Korean display name (`code`, `name_ko`).

Tier definitions:

- Determine tier from each participant's `mmr_before` at match time, never from current MMR.
- Platinum: 3600 <= MMR < 5000; Diamond: 5000 <= MMR < 6400;
  Meteorite: 6400 <= MMR < 7600; Mithril or above: MMR >= 7600.
- Subdivision lower bounds, from division 4 to division 1:
  Platinum 3600, 3950, 4300, 4650; Diamond 5000, 5350, 5700, 6050;
  Meteorite 6400, 6700, 7000, 7300. Each subdivision ends at the next lower bound.
- Do not distinguish Demigod and Eternity: rank-based tier boundaries are unavailable.
- MMR below 3600 is below the collection target; NULL MMR has unknown tier.
  Never label either as Platinum. The policy for including these rows in default
  aggregates is pending; no default exclusion policy is established here yet.

Rules:

1. Return exactly one PostgreSQL `SELECT` statement in one SQL code block and no prose.
2. Query only the listed tables and columns. Never use system catalogs.
3. Use `NULLIF` where division by zero is possible.
4. Prefer character names by joining `characters`; keep codes when metadata is absent.
5. Limit detail queries. Aggregate queries may return fewer rows naturally.
6. For "today", a calendar date, or hour of day based on `games.start_dtm`, use Korea time:
   `(games.start_dtm AT TIME ZONE 'Asia/Seoul')::date` or `EXTRACT(HOUR FROM games.start_dtm AT TIME ZONE 'Asia/Seoul')`.
   Never cast `games.start_dtm` to `date` directly.
