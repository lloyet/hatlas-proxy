-- Tables of the web schema that hatlas-proxy writes (Open Question 8).
-- Run on every start, so each statement must be safe to repeat (IF NOT EXISTS).
-- The website (SvelteKit) owns the web schema: its migrations must keep this exact layout.
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
