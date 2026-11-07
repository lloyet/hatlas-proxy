# `link` suites

| Scenario | Checks |
|---|---|
| `link-on-hub-gives-code.json` | `/link` on the hub answers with a code; a second `/link` within `link.cooldown-seconds` (30 s by default) is refused. |
| `link-off-hub-refused.json` | After `/server faction`, `/link` answers "Use /link on the hub". |

**After `link-on-hub-gives-code.json`, check the row** (the scenario cannot read the database):

```bash
docker exec hatlas-postgres-postgres-1 psql -U hatlas -d hatlas_proxy_dev -tAc \
  "SELECT username, length(code), uuid, expires_at - created_at FROM web.link_codes"
```

Expected: one row `Tester1 | 6 | <offline UUID of Tester1> | 00:05:00` (the stack config sets `link.code-lifetime-minutes: 5`), and the code matches the one in the bot's chat. The proxy log shows `/link code issued to Tester1`, and no `ERROR` or exception.
