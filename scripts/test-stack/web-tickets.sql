-- Test fixture: the website's ticket table (contract: ../hatlas/docs/access.md) with rows for the
-- offline test bots. Loaded by scripts/test-stack.ps1 reset into the fresh test database, before
-- the proxy starts. The real table belongs to the website; the plugin never creates it.
-- Offline UUIDs = UUID.nameUUIDFromBytes("OfflinePlayer:" + name), same on the proxy and backends.
CREATE SCHEMA IF NOT EXISTS web;
CREATE TABLE web.tickets (
    id          bigserial   PRIMARY KEY,
    uuid        uuid        NOT NULL,
    valid_until timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tickets_uuid ON web.tickets (uuid);

-- Tester1: valid (an old expired row + a current one: the latest counts).
INSERT INTO web.tickets (uuid, valid_until) VALUES
    ('5b30590c-7361-31a2-a5a0-ee3cb0717365', now() - interval '40 days'),
    ('5b30590c-7361-31a2-a5a0-ee3cb0717365', now() + interval '30 days');
-- Tester2: expired yesterday.
INSERT INTO web.tickets (uuid, valid_until) VALUES
    ('e4c9cd18-ee96-3574-b06a-e9866b0fa22d', now() - interval '1 day');
-- Tester3 (362bd452-26d9-3b54-8335-8aa4abf72f39): no ticket.
-- Tester4: valid for 30 days; the expiry suite shortens it right before it runs (see its README).
INSERT INTO web.tickets (uuid, valid_until) VALUES
    ('7bc359c4-5ca1-36d9-9ce3-742820be356b', now() + interval '30 days');
