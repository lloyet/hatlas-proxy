-- Tables of the web schema that hatlas-proxy writes (Open Question 8).
-- The website (hatlas-webapp) owns the web schema: its migrations create these tables with this
-- exact layout and grant the proxy's role access. This file is only a FALLBACK (development, or a
-- role that may create objects): on start the plugin looks each object up (to_regnamespace /
-- to_regclass) and runs only the statements of the missing ones, because PostgreSQL checks the
-- CREATE privilege even for IF NOT EXISTS. Any layout change must be agreed with hatlas-webapp first.
-- Statements are grouped by the {schema}.<table> they name; the one naming no table is the schema.
-- {schema} is replaced by database.schema from config.yml (checked to be a plain identifier).

CREATE SCHEMA IF NOT EXISTS {schema};

-- /link: one live code per player. The website reads it when a logged-in player types the code,
-- records the link, then deletes the row (single use). Expired rows are deleted by the plugin.
CREATE TABLE IF NOT EXISTS {schema}.link_codes (
    code       text        PRIMARY KEY,
    uuid       uuid        NOT NULL UNIQUE,
    username   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS link_codes_expires_at ON {schema}.link_codes (expires_at);

-- Who is online and on which backend (Velocity server name). Written only by the proxy (upsert on
-- join and server switch, delete on quit, emptied when the proxy starts); the website only reads it.
CREATE TABLE IF NOT EXISTS {schema}.presence (
    uuid     uuid        PRIMARY KEY,
    username text        NOT NULL,
    server   text        NOT NULL,
    since    timestamptz NOT NULL DEFAULT now()
);
