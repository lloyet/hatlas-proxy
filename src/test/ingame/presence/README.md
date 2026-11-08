# presence — `web.presence` rows on join, server switch and quit

A scenario cannot read the database, so the suite is three slices run **in order on the `hub`
session** (the bot stays connected between them), with a query after each:

```bash
docker exec hatlas-postgres-postgres-1 psql -U hatlas -d hatlas_proxy_dev -tAc \
  "SELECT username, server, uuid FROM web.presence"
```

| Slice | Expected rows after it |
|---|---|
| (before, right after `reset`) | none: the table is emptied when the proxy starts |
| `1-join.json` — Tester1 joins, lands on the hub | `Tester1 \| hub \| 5b30590c-7361-31a2-a5a0-ee3cb0717365` |
| `2-switch.json` — `/server faction` (Tester1 has a valid ticket in the fixture) | `Tester1 \| faction \| …` (same uuid, `since` later than before) |
| `3-quit.json` — Tester1 leaves | none |

Also check the proxy log: `web.presence is ready: presence enabled` at start (on the stack the role may
create objects, so it may first say `created web.presence from the plugin's fallback schema`), and no
`presence: write failed`.

**Fallback path** (checked by unit tests, `WebTablesTest`; by hand only when `WebTables` changes):
with a role that has no `CREATE` on `web` and the tables missing, the log shows one
`presence disabled: … may not create it` warning per table; creating and granting the tables then
enables them within `database.recheck-seconds` (5 s on the stack) without a restart.
