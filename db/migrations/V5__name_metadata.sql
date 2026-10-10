-- Code-level latest names; removed source codes are retained by the loader.
ALTER TABLE characters ADD COLUMN source text;
ALTER TABLE characters ADD COLUMN l10n_key text;
ALTER TABLE characters ADD COLUMN checked_at timestamptz;
ALTER TABLE weapon_types ADD COLUMN type_key text;
ALTER TABLE weapon_types ADD COLUMN source text;
ALTER TABLE weapon_types ADD COLUMN l10n_key text;
ALTER TABLE weapon_types ADD COLUMN checked_at timestamptz;

CREATE TABLE items (
    code integer PRIMARY KEY CHECK (code > 0), name_ko text NOT NULL,
    source text NOT NULL, l10n_key text NOT NULL, checked_at timestamptz NOT NULL,
    item_type text, item_grade text, grade_name_ko text, equipment_type text
);
CREATE TABLE trait_names (
    code integer PRIMARY KEY CHECK (code > 0), name_ko text NOT NULL,
    source text NOT NULL, l10n_key text NOT NULL, checked_at timestamptz NOT NULL,
    trait_type text, trait_group text, active boolean
);
CREATE TABLE tactical_skills (
    code integer PRIMARY KEY CHECK (code > 0), name_ko text NOT NULL,
    source text NOT NULL, l10n_key text NOT NULL, checked_at timestamptz NOT NULL,
    skill_group_code integer NOT NULL
);
CREATE TABLE skill_groups (
    code integer PRIMARY KEY CHECK (code > 0), name_ko text NOT NULL,
    source text NOT NULL, l10n_key text NOT NULL, checked_at timestamptz NOT NULL
);
CREATE TABLE areas (
    code integer PRIMARY KEY CHECK (code > 0), name_ko text NOT NULL,
    source text NOT NULL, l10n_key text NOT NULL, checked_at timestamptz NOT NULL,
    name_en text
);
CREATE TABLE weather_types (
    code integer PRIMARY KEY CHECK (code > 0), name_ko text NOT NULL,
    source text NOT NULL, l10n_key text NOT NULL, checked_at timestamptz NOT NULL
);

-- Also grant here: a repeatable already applied at target V3/V4 need not rerun at V5.
GRANT SELECT, INSERT, UPDATE, DELETE ON items, trait_names, tactical_skills,
    skill_groups, areas, weather_types TO collector;
GRANT SELECT ON items, trait_names, tactical_skills, skill_groups, areas, weather_types TO agent_ro;

