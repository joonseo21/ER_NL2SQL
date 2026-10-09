BEGIN;

CREATE TABLE IF NOT EXISTS rankers (
    uid text PRIMARY KEY,
    nickname text NOT NULL,
    rank integer NOT NULL CHECK (rank > 0),
    mmr integer,
    season_id integer NOT NULL,
    fetched_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS collect_queue (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_type text NOT NULL CHECK (job_type IN ('USER', 'GAME')),
    target_key text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'DONE', 'RETRY', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_type, target_key)
);

CREATE TABLE IF NOT EXISTS games (
    game_id bigint PRIMARY KEY,
    season_id integer,
    matching_mode integer,
    matching_team_mode integer,
    version_season integer,
    version_major integer,
    version_minor integer,
    start_dtm timestamp,
    server_name text,
    fetched_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS participants (
    game_id bigint NOT NULL REFERENCES games(game_id) ON DELETE CASCADE,
    nickname text NOT NULL,
    team_number integer,
    character_num integer,
    best_weapon integer,
    best_weapon_level integer,
    game_rank integer,
    player_kill integer,
    player_assistant integer,
    monster_kill integer,
    damage_to_player integer,
    mmr_before integer,
    mmr_gain integer,
    mmr_after integer,
    play_time integer,
    is_ranker boolean NOT NULL DEFAULT false,
    raw jsonb NOT NULL,
    PRIMARY KEY (game_id, nickname)
);

CREATE TABLE IF NOT EXISTS characters (
    character_code integer PRIMARY KEY,
    name_ko text,
    name_en text
);

CREATE TABLE IF NOT EXISTS weapon_types (
    code integer PRIMARY KEY,
    name_ko text
);

CREATE TABLE IF NOT EXISTS api_call_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    endpoint text NOT NULL,
    status_code integer NOT NULL,
    latency_ms bigint NOT NULL CHECK (latency_ms >= 0),
    called_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_collect_queue_work
    ON collect_queue (status, job_type, created_at);
CREATE INDEX IF NOT EXISTS idx_participants_character
    ON participants (character_num);
CREATE INDEX IF NOT EXISTS idx_participants_ranker
    ON participants (is_ranker);
CREATE INDEX IF NOT EXISTS idx_games_version
    ON games (version_major, version_minor);

COMMIT;
