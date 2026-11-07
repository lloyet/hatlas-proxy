# access — ticket gate and mid-session expiry

Both suites use the ticket fixture loaded by `scripts/test-stack.ps1 reset`
(`scripts/test-stack/web-tickets.sql`): Tester1 valid, Tester2 expired, Tester3 no ticket,
Tester4 valid for 30 days. The stack config uses `warn-before-minutes: [1]` and
`check-interval-seconds: 5`.

- `gate.json`: Tester2 (expired) and Tester3 (none) stay on the hub with `access-expired` (proven by
  `/link` still working); Tester1 (valid) reaches the faction server (proven by `/link` answering
  "Use /link on the hub").
- `expiry-back-to-hub.json`: **right before running it**, shorten Tester4's ticket to 75 seconds:
  ```sql
  -- psql on hatlas_proxy_dev (docker exec -i hatlas-postgres-postgres-1 psql -U hatlas -d hatlas_proxy_dev)
  UPDATE web.tickets SET valid_until = now() + interval '75 seconds'
   WHERE uuid = '7bc359c4-5ca1-36d9-9ce3-742820be356b';
  ```
  Tester4 joins the faction server, gets "Your access expires in 1 min", then is sent back to the
  hub ("you were sent back to the hub") within ~5 s of the expiry, and can no longer go back.

Checked by hand when the gate changes:
- **Fails closed:** `ALTER TABLE web.tickets RENAME TO tickets_off;` → Tester1's `/server faction` is
  refused with "The service is unavailable" and the proxy log has `Ticket check failed … refused`;
  a player already on the faction server stays there (the periodic check only logs). Rename back.
- **Joining straight at the faction server** (if a forced host or the try list points there): a
  player without a ticket lands on the hub instead of being disconnected.

Pitfall: Velocity's `command-rate-limit = 50` (ms) with `forward-commands-if-rate-limited = true`
forwards a command sent less than 50 ms after the previous one to the backend ("Unknown or incomplete
command"). The plugin's answers come back faster than that, so a scenario must `wait_for` a few ticks
between a command's reply and the next command.
