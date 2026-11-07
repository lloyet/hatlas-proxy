# Claude changelog

Every change Claude makes to this project is logged here, newest first.
Format: `YYYY-MM-DD — branch — summary (files)`.

- **2026-11-07 — main —** Gradle wrapper 9.4.1 → **9.9.0** (current release; Atlas follows). Remote `origin` = `github.com/lloyet/hatlas-proxy`. Recorded the user's answers: JDBC + HikariCP with the PostgreSQL driver bundled; every admin-tunable value in the config file. (`gradle/wrapper/*`, `gradlew*`, `CLAUDE.md`)

- **2026-11-07 — main —** Project skeleton for the Velocity plugin of milestone M2 (see `../play.mc.atlas` CLAUDE.md): Gradle 9.4.1 wrapper (Kotlin DSL), Java 25 toolchain (foojay resolver), `velocity-api:4.3.1` compile-only + annotation processor, `run-velocity` 3.0.2; `HatlasProxy` main class with `@Plugin(id = "hatlas-proxy")`, init/shutdown logs. Verified: build gives Java 25 classes (major 69) and a generated `velocity-plugin.json`; `runVelocity` boots Velocity 4.3.1, loads the plugin, listens on 25577 (25565 is taken by the Atlas test server). (`build.gradle.kts`, `settings.gradle.kts`, `gradlew*`, `gradle/wrapper/*`, `src/main/java/org/minecraft/hatlas/proxy/HatlasProxy.java`, `.gitignore`, `CLAUDE.md`)
