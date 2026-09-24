create table rankers
(
    uid        text                                   not null
        primary key,
    nickname   text                                   not null,
    rank       integer                                not null
        constraint rankers_rank_check
            check (rank > 0),
    mmr        integer,
    season_id  integer                                not null,
    fetched_at timestamp with time zone default now() not null
);

alter table rankers
    owner to postgres;

grant delete, insert, select, update on rankers to collector;

grant select on rankers to agent_ro;

create table collect_queue
(
    id         bigint generated always as identity
        primary key,
    job_type   text                                             not null
        constraint collect_queue_job_type_check
            check (job_type = ANY (ARRAY ['USER'::text, 'GAME'::text])),
    target_key text                                             not null,
    status     text                     default 'PENDING'::text not null
        constraint collect_queue_status_check
            check (status = ANY (ARRAY ['PENDING'::text, 'DONE'::text, 'RETRY'::text, 'FAILED'::text])),
    attempts   integer                  default 0               not null
        constraint collect_queue_attempts_check
            check (attempts >= 0),
    last_error text,
    created_at timestamp with time zone default now()           not null,
    updated_at timestamp with time zone default now()           not null,
    unique (job_type, target_key)
);

alter table collect_queue
    owner to postgres;

grant select, usage on sequence collect_queue_id_seq to collector;

create index idx_collect_queue_work
    on collect_queue (status, job_type, created_at);

grant delete, insert, select, update on collect_queue to collector;

create table games
(
    game_id            bigint                                 not null
        primary key,
    season_id          integer,
    matching_mode      integer,
    matching_team_mode integer,
    version_season     integer,
    version_major      integer,
    version_minor      integer,
    start_dtm          timestamp,
    server_name        text,
    fetched_at         timestamp with time zone default now() not null
);

alter table games
    owner to postgres;

create index idx_games_version
    on games (version_major, version_minor);

grant delete, insert, select, update on games to collector;

grant select on games to agent_ro;

create table participants
(
    game_id           bigint                not null
        references games
            on delete cascade,
    nickname          text                  not null,
    team_number       integer,
    character_num     integer,
    best_weapon       integer,
    best_weapon_level integer,
    game_rank         integer,
    player_kill       integer,
    player_assistant  integer,
    monster_kill      integer,
    damage_to_player  integer,
    mmr_before        integer,
    mmr_gain          integer,
    mmr_after         integer,
    play_time         integer,
    is_ranker         boolean default false not null,
    raw               jsonb                 not null,
    primary key (game_id, nickname)
);

alter table participants
    owner to postgres;

create index idx_participants_character
    on participants (character_num);

create index idx_participants_ranker
    on participants (is_ranker);

grant delete, insert, select, update on participants to collector;

grant select on participants to agent_ro;

create table characters
(
    character_code integer not null
        primary key,
    name_ko        text,
    name_en        text
);

alter table characters
    owner to postgres;

grant delete, insert, select, update on characters to collector;

grant select on characters to agent_ro;

create table weapon_types
(
    code    integer not null
        primary key,
    name_ko text
);

alter table weapon_types
    owner to postgres;

grant delete, insert, select, update on weapon_types to collector;

grant select on weapon_types to agent_ro;

create table api_call_log
(
    id          bigint generated always as identity
        primary key,
    endpoint    text                                   not null,
    status_code integer                                not null,
    latency_ms  bigint                                 not null
        constraint api_call_log_latency_ms_check
            check (latency_ms >= 0),
    called_at   timestamp with time zone default now() not null
);

alter table api_call_log
    owner to postgres;

grant select, usage on sequence api_call_log_id_seq to collector;

grant delete, insert, select, update on api_call_log to collector;

