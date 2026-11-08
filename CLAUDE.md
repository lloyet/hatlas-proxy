# CLAUDE.md

This file guides Claude Code when working in this repository.

> **Working agreement for Claude**
> 1. **Read this file fully at the start of every session.** It is the source of truth for goals, decisions and open questions.
> 2. **Every time you modify the project, append an entry to [`docs/claude-changelog.md`](docs/claude-changelog.md)** (date, branch, what changed, files) and update any section that is now out of date.
> 3. **When a request is vague or a design choice isn't settled, add it to [Open Questions](#open-questions)** and ask the user instead of guessing.
> 4. Each feature on its own `feat/` branch. Commit only when the user asks. **Never push on its own: a push is always done to open a PR** (push the branch + `gh pr create`, which runs the in-game gate), and only when the user asks for the PR (✔ 2026-11-07).

---

## 1. Project overview

`hatlas.proxy` (plugin id `hatlas-proxy`) is the **Velocity proxy plugin** of the Hatlas network. It sits in front of two Paper 1.21.11 servers:

```
player ─▶ Velocity proxy (online-mode, the only public port)
            ├─ hub (default server, no Hatlas) ── /link
            └─ faction server (Hatlas plugin, bound to localhost, Velocity modern forwarding)
SvelteKit SSR ─▶ PostgreSQL ◀─ Hatlas (schema hatlas) / hatlas-proxy (schema web)
Stripe ─webhook▶ SvelteKit (writes web.tickets)
```

It is milestone **M2** of the sibling repository [`../hatlas`](../hatlas) (Hatlas, the faction plugin). Its CLAUDE.md, section "M2 — Website, proxy and paid access" and Open Questions 21–25, holds the decisions. Summary:

- **`/link`** (on the hub): writes a single-use code (valid 5–10 min) to `web.link_codes` (uuid, code, expiry). The player, already logged in on the website, types the code; **SvelteKit** checks it, records the link and deletes the row. The plugin never receives writes from the website. **Built** (`feat/link-command`): one live code per player (a new `/link` replaces the old one), random from `link.code-alphabet` (no look-alikes), `link.cooldown-seconds` between two uses, expiry computed by PostgreSQL `now()`, expired rows deleted on each issue.
- **Ticket gate:** moving hub → faction server needs a valid row in `web.tickets` (**tied to the Minecraft UUID**, `valid_until`). Without it the player stays on the hub with "Your access expired, renew at <url>". **Built** (`feat/ticket-gate`, `access/TicketGate`): `ServerPreConnectEvent` (async `EventTask`) on the faction server; a failed query refuses the move with `service-unavailable` (**fails closed**); a player joining the network straight at the faction server is redirected to the hub instead of being disconnected; permission `hatlas.proxy.access.bypass` skips the gate (needs a permission plugin on the proxy).
- **Ticket contract** (same rule as Hatlas, source of truth `../hatlas/docs/access.md`, received 2026-11-07): `web.tickets` has at least `uuid uuid` and `valid_until timestamptz`, any number of rows per player. **A player has access while `max(valid_until) > now()`**, with the **database** clock: `SELECT max(valid_until), now() FROM web.tickets WHERE uuid = ?`. A refund sets `valid_until` to now or deletes the row. The website owns and writes the table; the proxy and Hatlas only read it (so the plugin does **not** create it, unlike `link_codes`, OQ 8; the test stack seeds it). Hatlas's own login check (`access.require_ticket`, off by default) **fails closed** (DB down or table missing → login refused) and lets operators in (`ops_bypass`).
- **Mid-session expiry:** warn (e.g. 10 min before), then **send the player back to the hub**. **Built** (`feat/ticket-gate`): every `access.check-interval-seconds`, one query (`unnest(uuid[])`) for all players on the faction server; one warning per `access.warn-before-minutes` mark (`access/ExpiryWarnings`; joining late gives one warning, a renewed ticket starts over); expired → sent to the hub (`sent-to-hub`), disconnected if the hub is unreachable. A failed periodic query only logs (a database blip must not kick everyone; ✔ decided 2026-11-08).
- **Presence:** a table updated on join, quit and server switch (who is online, on which server), read by the website (`LISTEN/NOTIFY` for live updates if needed). **Built** (`feat/presence`, layout agreed with hatlas-webapp, its Decision 7, 2026-11-08): `web.presence (uuid uuid PK, username text, server text, since timestamptz DEFAULT now())`. Upsert `(uuid, username, server = Velocity backend name, since = now())` on `ServerConnectedEvent` (first join + every switch), `DELETE` on `DisconnectEvent`, table emptied when the proxy starts (more exactly: each time the table becomes usable, then rewritten from the players online) and on clean shutdown (assumes a **single proxy**). Writes are queued on one writer thread (`presence/PresenceTracker`, event order kept), never delay a player; a failure is logged once per streak. **The website only reads it; any layout change must be agreed with hatlas-webapp first.**
- **No HTTP API** in the plugin for now (Open Question 22 of Hatlas).
- Payments (Stripe Checkout + webhook), accounts and links are owned by the **website**, never by this plugin. A payment must never depend on the game servers being up.

## 2. Build, run, tooling

| | |
|---|---|
| Java | **25** (required by Velocity 4.x). Gradle toolchain; JDK found in `~/.jdks` (IntelliJ) or downloaded by the foojay resolver. |
| Velocity | **4.3.1** (API `com.velocitypowered:velocity-api:4.3.1`, PaperMC repo). Adventure **5.x** (not 4.x: older examples may not compile). |
| Gradle | **9.9.0** wrapper (same as Hatlas), Kotlin DSL, `java` + `xyz.jpenilla.run-velocity` 3.0.2 |
| Group / version | `org.minecraft` / `1.0-SNAPSHOT`, root package `org.minecraft.hatlas.proxy` |

```bash
./gradlew build        # build/libs/hatlas.proxy-1.0-SNAPSHOT.jar
./gradlew runVelocity  # local Velocity 4.3.1 in ./run (git-ignored), bind 0.0.0.0:25577
```

- **Plugin metadata:** `velocity-plugin.json` is generated by the API's annotation processor from `@Plugin` on `HatlasProxy` (id, name, version, description, authors). There is no hand-written descriptor.
- **Local run port:** `run/velocity.toml` binds **25577** because the Hatlas test server uses 25565 on this machine. `runVelocity` and the test stack share 25577, so run only one of them at a time.
- **JetBrains "Minecraft Development" wizard** only offers Velocity 3.x templates; this project was set up by hand instead. Open the folder in IntelliJ as a Gradle project and set the project SDK to a JDK 25 (e.g. `~/.jdks/ms-25.0.3`); the Minecraft plugin's inspections still work.
- Entry point: `org.minecraft.hatlas.proxy.HatlasProxy` (Guice `@Inject` constructor with `ProxyServer`, SLF4J `Logger`, `@DataDirectory Path`; `@Subscribe` on `ProxyInitializeEvent` / `ProxyShutdownEvent`).
- **Config (YAML):** `src/main/resources/config.yml` (commented default) is copied to `plugins/hatlas-proxy/config.yml` on first start. `config/ConfigLoader` reads it with Configurate YAML (`configurate-yaml` 4.2.0, shipped by the Velocity API: no extra dependency). Absent keys fall back to the jar's default; invalid values are all listed in one `ConfigException`, and the plugin then stays loaded but inactive. Typed view: `config/HatlasConfig` (sections `database`, `servers`, `access`, `link`, `messages`; `config-version: 1`). Messages are MiniMessage strings. The DB password is never in the file: `database.password-env` names the environment variable (default `HATLAS_PROXY_DB_PASSWORD`).
- **Production database role** (hatlas-webapp Decision 6, 2026-11-08): the proxy connects as **`hatlas_proxy`** (default `database.user`), created by `../hatlas/docker/postgres/init`. Rights, granted by the website's migrations: `USAGE` on schema `web`, `SELECT` on `web.tickets`, `SELECT/INSERT/UPDATE/DELETE` on `web.link_codes` and `web.presence`, **no `CREATE`** on `web`. Password from `HATLAS_PROXY_DB_PASSWORD`. The test stack keeps `hatlas` (may create objects) via `scripts/test-stack/hatlas-proxy-config.yml`.
- **Database:** `db/Database` = HikariCP pool over a `PGSimpleDataSource` (driver created directly, so relocation can't break it) + a virtual-thread executor; every query goes through `Database.supply(...)`, never on a Velocity/netty thread. HikariCP and the driver are bundled by **shadow** (`com.gradleup.shadow` 9.6.1), relocated to `org.minecraft.hatlas.proxy.lib.*`; SLF4J comes from Velocity. The thin jar is `-plain` (not a plugin). The pool starts even if PostgreSQL is down (`initializationFailTimeout = -1`). **Web tables** (`db/WebTables`, Open Question 8): on start, then every `database.recheck-seconds` (60; 5 on the stack) until all are usable, each table the plugin writes is looked up (`to_regclass`, plus `USAGE`/`SELECT`/`INSERT`/`UPDATE`/`DELETE` checks). A missing one gets **only its own** statements of `db/web-schema.sql` (fallback; the schema statement only if `to_regnamespace` is null), because PostgreSQL checks `CREATE` even for `IF NOT EXISTS`. `42501`, missing table, missing grants or DB down → **one** warning per table, `/link` (answers `service-unavailable`) or presence stays off, and turns on by itself once the table is usable, no restart. Readiness is read lock-free from event threads. The ticket gate is independent and stays fail-closed. Missing password variable → plugin loaded but inactive.
- **Messages:** `message/Messages` renders the MiniMessage templates; `<link_url>` / `<renew_url>` are inserted as text before parsing (they appear inside `click:open_url` arguments), other values (`<code>`, `<minutes>`, `<seconds>`) are unparsed placeholders.
- **Unit tests:** JUnit 5 (`src/test/java`), run by `./gradlew build` and by CI (`.github/workflows/build.yml`, Temurin 25).
- **MCP** (`.mcp.json`, same as Hatlas): `vitaminmcp` (`npx -y vitaminmcp@3.2.2`) and `papermc-docs` (GitMCP view of the PaperMC docs, Velocity included).

### Local test stack (in-game tests through the proxy)
- **Script:** `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-stack.ps1 <install|start|stop|restart|status|deploy|reset|db-up|console>` (targets `all|proxy|hub|faction`). Usage notes are in the script header.
- **Folder:** `TEST_STACK_DIR` (default `C:\Users\lloyet\Documents\Projects\servers\hatlas-proxy-stack`, outside the repo) holding `proxy/`, `hub/`, `faction/`, `cache/` (jars from the PaperMC Fill API, SHA-256 checked). Disposable: no real data.
- **Ports (all on 127.0.0.1):** proxy **25577** (Velocity 4.3.1, `online-mode = false` for the bots, `modern` forwarding, template `scripts/test-stack/velocity.toml`); hub **25601** (RCON 25611, VitaminMCP 25621); faction **25602** (RCON 25612, VitaminMCP 25622). Backends: Paper 1.21.11, superflat, peaceful, Velocity forwarding enabled in `config/paper-global.yml`.
- **Database:** `hatlas_proxy_dev`, created inside the Hatlas PostgreSQL container `hatlas-postgres-postgres-1` (from `../hatlas/docker/postgres`). The script only creates or drops that database. It never starts, stops or recreates the container, and refuses `hatlas` / `hatlas_test`. Plugin config for the stack: `scripts/test-stack/hatlas-proxy-config.yml`, with short delays.
- **Isolation:** the script never touches the shared Hatlas test server (25565 / 25575 / 25585); it refuses those ports. The user freed that server on 2026-11-07, but proxy tests still run on this stack.
- **Bots:** `session_start {"name":"hub","port":25577,"mcpPort":25621}` (and `"faction"` / `25622`): bots join through the proxy, one session per backend. The proxy has no VitaminMCP agent: read `proxy/logs/latest.log`.
- **Gate (as in Hatlas):** `.claude/settings.json` → PreToolUse `gh pr create` runs `.claude/hooks/ingame-gate.mjs` (copied as is from Hatlas, with `affected-features.mjs`). Feature map `src/test/ingame/features.yml`, suites `src/test/ingame/<feature>/*.json`. Agent `.claude/agents/ingame-tester.md` (reset, deploy, sessions, verdict `.claude/state/ingame/<sha>.json`). Skill `.claude/skills/paper-ingame-test` (Hatlas's generic part + a "hatlas-proxy" section). No wiki for this plugin.
- **Full chain mode** (`test-stack.ps1 hatlas on`): the real Hatlas on the faction backend, Hatlas's login ticket check on; run every suite in that mode, bots non-op. `hatlas off` returns to plain Paper.
- **Shut the stack down after every in-game campaign** (✔ 2026-11-07), whatever the verdict: `session_reset`, then `test-stack.ps1 stop`, then `status` shows everything `stopped`. The `ingame-tester` agent does this as its last step, and so does any manual campaign. The next campaign starts with `reset`. The shared Hatlas server (25565) is never stopped from this project.
- Stopping the proxy ends the process (Velocity has no RCON), so `ProxyShutdownEvent` does not run there. Backends stop cleanly over RCON.

## 3. Conventions
- Adventure API only (`Component`, `NamedTextColor`), no legacy colour codes.
- Database access off the proxy's event threads where blocking (Velocity events support async results); never block the netty threads.
- Secrets (DB password) from environment variables, never committed (`.env` is git-ignored).

## 4. Status
- 2026-11-07: skeleton only (`@Plugin`, init/shutdown log). Verified: `./gradlew build` produces Java 25 classes and `velocity-plugin.json`; `runVelocity` boots Velocity 4.3.1, loads `hatlas-proxy 1.0-SNAPSHOT`, runs the init handler, listens on 25577.
- 2026-11-07 (`feat/yaml-config-test-stack`): YAML config loaded and validated (4 JUnit tests pass, CI workflow added). Local test stack installed and verified: `install`, `start`, `deploy`, `reset`, `console hub "list"` and `status` all work; the proxy loads the plugin against `hatlas_proxy_dev`; hub and faction are reachable only through the proxy; both VitaminMCP agents listen (25621/25622). First bot run through VitaminMCP (after the Claude Code restart): `routing/join-lands-on-hub.json` **PASS** (bot joins via proxy 25577, proxy log shows `Tester1 -> hub`), no exceptions; stack stopped afterwards.

- 2026-11-08 (`feat/ticket-gate`): ticket gate + mid-session expiry built (§1). 22 JUnit tests pass (new `TicketTest`, `ExpiryWarningsTest`). Stack: `web.tickets` fixture loaded on `reset`; suites `access/gate.json`, `access/expiry-back-to-hub.json` (+ README with the manual fail-closed check); `link/link-off-hub-refused.json` now uses Tester1 (it needs a ticket to reach the faction server).
- 2026-11-07 (`feat/link-command`): `/link` built (§1). 14 JUnit tests pass (config, code generator, cooldown, messages, schema file). In game on the stack: plugin connects to `hatlas_proxy_dev` and creates `web.link_codes`; suites `link/link-on-hub-gives-code.json` (code issued, second `/link` hits the cooldown; row checked in the DB: 6 chars, offline UUID, 5 min) and `link/link-off-hub-refused.json` PASS, `routing` still PASS, no exceptions. Not tested in game: the "service unavailable" answer when PostgreSQL goes down mid-run.

- 2026-11-08 (`feat/presence`): presence + `hatlas_proxy` role defaults + web tables as a fallback (§1, §2, OQ 8). 35 JUnit tests pass (new `WebTablesTest` ×7: website-created tables, no `CREATE` → one warning then enabled without restart, fallback per object, schema only if missing, no grants, DB down; `PresenceTrackerTest` ×6). In game: proxy log `created web.presence from the plugin's fallback schema` + `presence enabled`; suite `presence/1-join`, `2-switch`, `3-quit` PASS with DB checks (row `Tester1|hub` → `Tester1|faction`, same uuid, later `since` → no row); routing, link ×2, access gate + expiry still PASS; no exceptions. `link-on-hub-gives-code.json` got a 20-tick pause between its two `/link` (Velocity command rate limit, see `access/README.md`). Not run in game: the no-`CREATE` path (the stack role is a superuser; covered by `WebTablesTest`).

## Open Questions
1. ~~**Database access**~~ — ✔ answered 2026-11-07: plain **JDBC + HikariCP**, with the **PostgreSQL driver bundled in the jar** (shadow, relocated).
2. ~~**Config**~~ — ✔ answered 2026-11-07: **every value an admin could want to change goes to the config file** (DB connection, server names, renew URL, warning delays, code lifetime, messages…); nothing tunable is hard-coded. Format: **YAML** (✔ 2026-11-07, same as Hatlas, easier for admins; HOCON rejected), see §2.
3. ~~**Shared code with Hatlas**~~ — ✔ answered 2026-11-07: **duplicate** the small ticket SQL query in Hatlas (its login check behind `access.require_ticket`, "defence in depth"); no shared module.
4. ~~**Repository remote**~~ — ✔ 2026-11-07: `https://github.com/lloyet/hatlas-proxy.git` (created by the user).
5. ~~**Tests**~~ — ✔ answered 2026-11-07: **JUnit unit tests run in CI** (ticket/link/expiry logic), and **in-game tests through a local proxy** with the same tooling as Hatlas: `.mcp.json` (VitaminMCP 3.2.2 + PaperMC docs) is in place; the `paper-ingame-test` skill, the `ingame-tester` agent and the gate hook are adapted to the proxy (§2). **No wiki** for this plugin (✔ 2026-11-07).
6. ~~**Local in-game test stack**~~ — ✔ answered 2026-11-07: separate local stack (Velocity 25577 offline, hub and faction Paper backends on their own ports, DB `hatlas_proxy_dev`, `scripts/test-stack.ps1`, agent and gate adapted from Hatlas). Built, see §2.
7. ~~**What runs on the test stack's `faction` backend?**~~ — ✔ answered 2026-11-07: **(a) plain Paper for now** (enough for routing, the ticket gate, the mid-session warning and the return to the hub). **(b) the Hatlas jar is required later**, because the full production chain must be tested (Hatlas's own valid-ticket check at login): tracked as task 1 of [§5 Next tasks](#5-next-tasks).

8. ~~**Who creates the `web` tables the plugin writes?**~~ — ✔ answered 2026-11-07, **revised 2026-11-08** (hatlas-webapp Decisions 6–7): **the website's migrations create** `web.link_codes`, `web.presence` and `web.tickets` (as `hatlas_webapp_owner`) and grant `hatlas_proxy` access; the proxy has no `CREATE`. The plugin's `db/web-schema.sql` is only a **fallback** for a role that may create objects (dev, test stack), run per missing object; otherwise the feature stays off and is re-checked until the tables appear (§2 "Database"). Layouts are fixed on both sides: `link_codes (code text PK, uuid uuid UNIQUE, username text, created_at, expires_at timestamptz)` + index on `expires_at`; `presence (uuid uuid PK, username text, server text, since timestamptz DEFAULT now())`. Changing them needs hatlas-webapp's agreement.

## 5. Next tasks
1. ~~`test-stack.ps1 install faction --with-hatlas`~~ — built as **`test-stack.ps1 hatlas on|off|status`** (`feat/full-chain`, 2026-11-08): builds `../hatlas`, installs its jar + packetevents on the faction backend with `scripts/test-stack/hatlas-config.yml` (storage in the stack database, schema `hatlas`; `access.require_ticket: true`); the mode survives `reset`. Suite `chain/` (+ README with the scripted manual steps: link redemption simulated with psql, Hatlas refusing a player the proxy let through, expiry with Hatlas present). This is Hatlas's M2 task 6.
2. ~~Ticket gate on hub → faction + mid-session expiry~~ — built on `feat/ticket-gate` (2026-11-08), see §1.
3. ~~**Presence table**~~ — built on `feat/presence` (2026-11-08) with the layout agreed with hatlas-webapp (its Decision 7), see §1; same branch: dedicated role `hatlas_proxy` and table creation as a fallback (OQ 8).

## Changelog
See [`docs/claude-changelog.md`](docs/claude-changelog.md).
