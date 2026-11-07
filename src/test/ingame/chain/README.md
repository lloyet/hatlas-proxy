# chain — the whole access chain (Hatlas M2 task 6)

Runs only in **full chain mode**: `scripts/test-stack.ps1 hatlas on` (the real Hatlas jar from
`../hatlas` on the faction backend, `access.require_ticket: true`, storage in the stack database),
then `reset`. In this mode run **every** suite (routing, link, access, chain): the proxy's gate and
Hatlas's login check must agree. Bots stay non-op (operators bypass Hatlas's check). Ticket
fixture: Tester1 valid, Tester2 expired, Tester3 none, Tester4 valid.

`full-chain.json`:
- Tester1 (valid): `/link` on the hub gives a code, `/server faction` passes the proxy gate **and**
  Hatlas's login check (Hatlas answers `/f balance` with "You are not in any faction."; `/link` is
  refused off the hub).
- Tester2 (expired): stopped by the proxy, stays on the hub (Hatlas's `/f` is unknown there).

## Checked by hand (scripted steps for the tester)

**1. Website link redemption, simulated with psql** (the real site will own these tests). After a
`/link` by Tester1 (`docker exec -i hatlas-postgres-postgres-1 psql -U hatlas -d hatlas_proxy_dev`):
```sql
SELECT code, uuid, username, expires_at > now() AS live FROM web.link_codes;  -- one live row, Tester1's offline UUID
-- what the site does when the logged-in user types the code: single use
DELETE FROM web.link_codes WHERE code = '<code>' AND expires_at > now() RETURNING uuid, username;  -- 1 row
DELETE FROM web.link_codes WHERE code = '<code>' AND expires_at > now() RETURNING uuid;            -- 0 rows (reused)
```
Expired code: `/link` again, `UPDATE web.link_codes SET expires_at = now() - interval '1 second';`,
then the same `DELETE … AND expires_at > now()` returns 0 rows; the next `/link` deletes the expired row.

**2. Hatlas refuses a player the proxy let through (defence in depth).** Make the proxy stop gating
the faction server: in `<stack>/proxy/plugins/hatlas-proxy/config.yml` set `servers.faction: nothing`,
`test-stack.ps1 restart proxy` (log: "config.yml names server 'nothing', which is not declared").
Then Tester3 (no ticket) `/server faction` → Velocity keeps them on the hub and shows Hatlas's kick
reason "You have no access ticket for this server." (+ "Renew it at http://localhost:5173/renew");
the faction log has no Hatlas exception. Tester2 (expired) → "Your access expired on …". Tester1
(valid) still joins. `reset` restores the proxy config.

**3. Mid-session expiry with Hatlas present:** `access/expiry-back-to-hub.json` (it runs in this mode too).
