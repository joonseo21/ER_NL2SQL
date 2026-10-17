-- Route identity follows the season of the observed game, never v2SeasonId.
CREATE TABLE routes (
    season_id integer NOT NULL,
    route_id integer NOT NULL CHECK (route_id > 0),
    fetch_status text NOT NULL DEFAULT 'PENDING'
        CHECK (fetch_status IN ('PENDING','FETCHING','SUCCESS','INVALID_VERSION',
            'NOT_FOUND','ERROR','INVALID_RESPONSE','INTERRUPTED')),
    usage_count bigint NOT NULL DEFAULT 0 CHECK (usage_count >= 0),
    response_code integer,
    fetched_at timestamptz,
    v2_season_id integer,
    character_code integer,
    weapon_type integer,
    tactical_skill_group_code integer,
    weapon_codes integer[],
    trait_codes integer[],
    remote_transfer_item_codes integer[],
    late_game_item_codes jsonb CHECK (jsonb_typeof(late_game_item_codes) = 'object'),
    v2_win_rate double precision,
    v2_like bigint,
    v2_accumulate_win_rate double precision,
    v2_accumulate_like bigint,
    route_version integer,
    update_dtm timestamptz,
    version_season integer,
    version_major integer,
    version_minor integer,
    team_mode integer,
    share boolean,
    PRIMARY KEY (season_id, route_id),
    CHECK ((fetch_status = 'PENDING') = (fetched_at IS NULL)),
    CHECK ((version_season IS NULL AND version_major IS NULL AND version_minor IS NULL)
        OR (version_season >= 0 AND version_major >= 0 AND version_minor >= 0
            AND version_season IS NOT NULL AND version_major IS NOT NULL AND version_minor IS NOT NULL))
);
CREATE TABLE route_paths (
    season_id integer NOT NULL,
    route_id integer NOT NULL,
    seq integer NOT NULL CHECK (seq > 0),
    area_code integer NOT NULL CHECK (area_code > 0),
    PRIMARY KEY (season_id, route_id, seq),
    FOREIGN KEY (season_id, route_id) REFERENCES routes(season_id, route_id)
);
CREATE INDEX idx_routes_character_weapon_win_rate
    ON routes (season_id, character_code, weapon_type, v2_win_rate);
ALTER TABLE items ADD COLUMN make_material_1 integer CHECK (make_material_1 > 0);
ALTER TABLE items ADD COLUMN make_material_2 integer CHECK (make_material_2 > 0);
GRANT SELECT, INSERT, UPDATE, DELETE ON routes, route_paths TO collector;
GRANT SELECT ON routes, route_paths TO agent_ro;
-- items already has table SELECT; the added columns are included automatically.
ALTER ROLE agent_ro SET statement_timeout = '20s';
