-- Hierarchical code sets shared by the GoO domain: energy sources, technologies, labels (independent criteria
-- schemes), support schemes and region sets. Paths use ltree so "descendant-of" predicates compile to a GiST
-- indexed `path <@ ancestor` condition.

CREATE TABLE taxonomy_node (
    id             UUID         NOT NULL PRIMARY KEY,
    scheme         TEXT         NOT NULL CHECK (scheme IN ('ENERGY_SOURCE', 'TECHNOLOGY', 'LABEL', 'REGION', 'SUPPORT_SCHEME')),
    code           TEXT         NOT NULL CHECK (code ~ '^[A-Z0-9_]{1,60}$'),
    parent_id      UUID         REFERENCES taxonomy_node (id),
    path           public.ltree NOT NULL,
    depth          INT          NOT NULL CHECK (depth >= 0),
    name           TEXT         NOT NULL,
    description    TEXT,
    external_codes JSONB        NOT NULL DEFAULT '{}'::jsonb,
    valid_from     DATE         NOT NULL,
    valid_to       DATE,
    version        INT          NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT taxonomy_node_scheme_code_uq UNIQUE (scheme, code),
    CONSTRAINT taxonomy_node_scheme_path_uq UNIQUE (scheme, path),
    CONSTRAINT taxonomy_node_valid_ck CHECK (valid_to IS NULL OR valid_from < valid_to)
);

CREATE INDEX taxonomy_node_path_gist_idx ON taxonomy_node USING gist (path);
CREATE INDEX taxonomy_node_parent_idx ON taxonomy_node (parent_id);
CREATE INDEX taxonomy_node_external_codes_idx ON taxonomy_node USING gin (external_codes jsonb_path_ops);

-- Region sets are enumerated over ISO 3166-1 alpha-2 countries.
CREATE TABLE region_member (
    region_node_id UUID    NOT NULL REFERENCES taxonomy_node (id) ON DELETE CASCADE,
    country        CHAR(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    PRIMARY KEY (region_node_id, country)
);
CREATE INDEX region_member_country_idx ON region_member (country);
