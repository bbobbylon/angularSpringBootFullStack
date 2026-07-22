# Developer Workflow Guide

The day-to-day loop for working *in* TesseraApp: setting up IntelliJ IDEA or VS Code, the `start.sh` dev loop (`ENV`/`DB` switches), hot-reload and breakpoint debugging on both tiers, where `schema.sql` fits when you change the data model, the feature-branch workflow, the build/test/verify gates, and the two planned tooling improvements (Maven Wrapper, MySQL port expose) that are not yet applied.

> **Audience:** contributors who will edit code, not just run the app once.
> **See also:** [getting-started.md](getting-started.md) (fastest path to a running instance) · [configuration.md](configuration.md) (every env var, profiles, `application.yml`) · [developer-guide.md](developer-guide.md) (end-to-end request trace + extension recipes) · [database.md](database.md) (the schema `schema.sql` owns) · [deployment.md](deployment.md) (Docker/cloud).

---

## Table of contents

1. [IDE setup (IntelliJ IDEA & VS Code)](#1-ide-setup-intellij-idea--vs-code)
2. [The `start.sh` dev loop](#2-the-startsh-dev-loop)
3. [Backend debugging](#3-backend-debugging)
4. [Frontend debugging](#4-frontend-debugging)
5. [Where `schema.sql` fits](#5-where-schemasql-fits)
6. [Feature-branch workflow](#6-feature-branch-workflow)
7. [Build, test & verify](#7-build-test--verify)
8. [Known limitations & planned improvements](#8-known-limitations--planned-improvements)

---

## 1. IDE setup (IntelliJ IDEA & VS Code)

Two editors are supported; pick either. The backend is a standard Maven + Java 21 + Lombok project, the frontend a standard Angular 21 workspace under `tesseraapp/`.

### IntelliJ IDEA

| Step | What to do | Why |
|------|------------|-----|
| Open as Maven project | **File ▸ Open** → select the root `pom.xml` → *Open as Project* | IntelliJ imports the module + dependencies from `pom.xml`. |
| Set the JDK | **File ▸ Project Structure ▸ Project** → SDK = **JDK 21**, language level 21 | The project targets Java 21; an older SDK fails to compile records/patterns. |
| Enable annotation processing | **Settings ▸ Build, Execution, Deployment ▸ Compiler ▸ Annotation Processors** → *Enable* | **Lombok** generates getters/builders/constructors at compile time; without this, `User.builder()` and `@RequiredArgsConstructor` fields show as unresolved. |
| Point the run config at `.env` | **Run ▸ Edit Configurations ▸ Spring Boot ▸ Environment file** → `.env` | Spring does **not** read `.env` itself — `start.sh` sources it into the shell; the IDE needs it wired in explicitly (see [configuration.md §2](configuration.md#2-where-configuration-comes-from-and-precedence)). |

> **Gotcha:** if you launch the backend from the IDE *without* the `.env` wired in, it still boots — the `dev` profile ships literal fallbacks (`application-dev.yml`) — but you must have a MySQL reachable at `127.0.0.1:3306` (see [§5](#5-where-schemasql-fits) and [configuration.md §8](configuration.md#8-configuration-gotchas-read-this)).

For the Angular side, open `tesseraapp/` as a separate window (or as a module) and use the bundled npm/Angular tooling; run scripts from the npm tool window.

### VS Code

| Area | Extensions / settings |
|------|------------------------|
| Backend (Java/Spring) | **Extension Pack for Java** + **Spring Boot Extension Pack** (Spring Boot Dashboard runs/debugs the app; the language server reads `pom.xml`). Lombok support ships with the Java pack. |
| Frontend (Angular) | **Angular Language Service** (template type-checking + go-to-definition), **ESLint**, **Prettier**. |
| Env vars | The Spring Boot Dashboard / `launch.json` `"envFile": "${workspaceFolder}/.env"` so the backend sees the same variables `start.sh` would export. |

> **Note (editor of record):** this project is developed in **IntelliJ IDEA or VS Code only**. Don't add or document any other Spring IDE tooling.

---

## 2. The `start.sh` dev loop

`start.sh` is the one entry point that orchestrates the backend, the frontend, and (optionally) a MySQL container. You configure it by editing two variables at the top of the file, then running it — there are no CLI flags.

```bash
ENV=local     # local | docker     (start.sh:26)
DB=local      # local | aiven      (start.sh:27 — only consulted when ENV=local)
```

```bash
chmod +x start.sh
./start.sh
```

### The switch matrix

| `ENV` | `DB` | What `start.sh` does | Hot-reload? | Open at |
|-------|------|----------------------|-------------|---------|
| `local` | `local` | Brings up a **MySQL container** (`docker compose up -d mysql`, start.sh:116) and waits for health, then runs Spring Boot via `mvn spring-boot:run` (start.sh:153) **and** Angular via `npm run start` (start.sh:146), both backgrounded | **Yes** — both tiers | http://localhost:4200 |
| `local` | `aiven` | Skips the container; exports `SPRING_DATASOURCE_*` pointing at **Aiven cloud MySQL** (start.sh:132–135), then runs backend + frontend natively | **Yes** — both tiers | http://localhost:4200 |
| `docker` | *(n/a)* | `docker compose up --build` (start.sh:214) — multi-stage build compiles Angular **into** the Spring Boot JAR; app + MySQL run as containers | **No** | http://localhost:8090 (`APP_PORT`) |

### What local mode wires up for you

- **Loads `.env`** with `set -a; source .env; set +a` (start.sh:104–108) so every variable becomes a real OS env var Spring can read.
- **Pins the backend port to 8080** — `export CONTAINER_PORT=8080` (start.sh:111) — because the Angular dev server's API base is `http://localhost:8080` (see [§4](#4-frontend-debugging)); whatever `CONTAINER_PORT` is in `.env` is overridden here.
- **Auto-installs frontend deps** if `tesseraapp/node_modules` is missing (start.sh:139–142).
- **Auto-opens the browser** once the app responds (`OPEN_BROWSER=true`, timeout `OPEN_BROWSER_TIMEOUT`, start.sh:32–33); set `OPEN_BROWSER=false` to suppress.
- **Clean teardown on Ctrl+C** — the `cleanup` trap kills the Spring/Angular/browser PIDs and `docker compose stop mysql` when `DB=local` (start.sh:174–186).

> **Why a single script:** the frontend hard-expects the API on `:8080`, the backend needs the DB up first, and Docker-vs-native flips several env vars. `start.sh` encodes that ordering so the common case is one command. For attaching a debugger you'll instead run the tiers individually — see [§3](#3-backend-debugging).

> **Heads-up (`DB=local`):** the `mysql` service in `docker-compose.yml` does **not** publish 3306 to the host today, so the started container is only reachable on the compose network. The host-native backend talks to `127.0.0.1:3306`, so this path currently relies on a host MySQL on 3306 — the [planned 3306 expose](#8-known-limitations--planned-improvements) closes that gap. If a diagram or doc disagrees with the script, **the code wins** — `start.sh` and `docker-compose.yml` are authoritative.

---

## 3. Backend debugging

### DevTools hot reload

`spring-boot-devtools` is on the classpath (pom.xml:145). Under `mvn spring-boot:run` (what `start.sh` uses in local mode), DevTools watches the classpath and **automatically restarts** the application context whenever compiled classes change:

- **IntelliJ:** recompile the changed file (**Build ▸ Recompile**, or *Build project*). DevTools sees the new `.class` and restarts in ~1–2 s — far faster than a cold boot because the restart classloader only reloads your code, not the dependencies.
- **VS Code:** the Java language server compiles on save; the restart triggers the same way.

DevTools restart preserves the dev profile and (because of the LiveReload server it embeds) can refresh a connected browser. It is excluded from the production jar by the Spring Boot Maven plugin, so this is a dev-only convenience.

### Breakpoints

Breakpoints need the JVM under the IDE's debugger, which means **running the backend yourself** rather than through `start.sh` (the script backgrounds `mvn` and owns the process):

```bash
# Terminal A — backend under the debugger:
#   IntelliJ: Debug the Spring Boot run config (with .env wired in, §1)
#   VS Code:  Spring Boot Dashboard ▸ Debug
# Terminal B — frontend only (from tesseraapp/):
npm install      # first time
npm start        # ng serve on :4200, calls the API on :8080
```

This gives you the same running system as `start.sh ENV=local`, but with the backend stoppable at breakpoints. You still need MySQL up — either keep a host MySQL running, or start just the DB container first (`docker compose up -d mysql`).

> **Remote/attach debugging:** to debug the `mvn spring-boot:run` process that `start.sh` launches, add a JDWP agent via `MAVEN_OPTS` (e.g. `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`) and attach the IDE to port 5005. The integrated loop keeps running; you just get breakpoints.

### The dev profile

`dev` is the default profile (`spring.profiles.active: ${SPRING_ACTIVE_PROFILES:dev}`). It ships literal fallbacks for DB/secret/mail vars, runs the **demo-data seeder** (one user per role, password `TesseraDemo@1`), and leaves `show-sql` off unless you set `SHOW_SQL=true`. To see the SQL Hibernate emits for the JPA tables while debugging, launch with `SHOW_SQL=true`. Profiles, fallbacks, and the seeder are detailed in [configuration.md §5](configuration.md#5-spring-profiles).

---

## 4. Frontend debugging

The Angular dev server (`ng serve` via `npm start`, package.json `scripts.start`) runs on **http://localhost:4200** with hot module reload — saving a `.ts`/`.html`/`.css` file recompiles and refreshes automatically.

### How the SPA reaches the API

Every Angular service uses `environment.apiUrl` as its base:

| Build | `apiUrl` | Effect |
|-------|----------|--------|
| dev (`ng serve`) | `http://localhost:8080` (environment.ts:11) | Calls go **cross-origin** to the backend; the backend's CORS config whitelists `UI_APP_URL` (`http://localhost:4200`). This is **not** an Angular proxy — there is no `proxy.conf.json`. |
| production | `''` (environment.production.ts:12) | Empty base = same-origin **relative** URLs, for a reverse-proxy/single-origin deploy. |

The file swap is driven by `angular.json` `fileReplacements`, so components import one symbol with no runtime branching.

### Debugging techniques

- **Browser DevTools:** `ng serve` emits source maps in development, so you set breakpoints directly on your TypeScript in the Sources panel. The **Network** tab shows each request — confirm the `Authorization: Bearer …` header is present on protected calls (attached by `tokenInterceptor`) and absent on public ones.
- **VS Code:** a `launch.json` Chrome/Edge config (`"url": "http://localhost:4200"`) attaches the editor's debugger to the running dev server.
- **Angular DevTools** browser extension for the component tree, signals, and change-detection profiling.
- **Toasts:** errors surface through `NotificationsService` (ngx-toastr); the user-facing message is the backend envelope's `reason` field, read in each service's `handleError`.
- **Cache surprises:** GET responses are cached in memory by `cacheInterceptor` and invalidated wholesale on any mutation or logout. If a stale list won't refresh, that's the cache — call `httpCache.logCache()` from the console to inspect it.

### Frontend quality commands (from `tesseraapp/`)

```bash
npm test            # ng test (Vitest + jsdom)        package.json:9
npm run lint        # ESLint                            package.json:10
npm run format      # Prettier --write                  package.json:12
npm run build       # production build                   package.json:7
```

---

## 5. Where `schema.sql` fits

The data model lives in **two** places, and which one you touch depends on the kind of table:

| Table family | Owner | How it's created | You edit |
|--------------|-------|------------------|----------|
| Identity / auth / audit (`users`, `roles`, `userevents`, `refreshsessions`, TOTP, orgs, …) | **`src/main/resources/schema.sql`** (idempotent, hand-written DDL) accessed via `JdbcTemplate` | Applied **by hand** — `sql.init.mode: never` means it never auto-runs | `schema.sql` |
| Business (`customer`, `invoice`, `services`, `invoiceserviceitems`) | **Hibernate / JPA** `@Entity` classes | Auto-created/updated by `ddl-auto: update` on boot | the `@Entity` class |

### The loop when you change identity schema

```bash
# 1. edit src/main/resources/schema.sql  (CREATE TABLE IF NOT EXISTS … — no DROPs; FK to users ON DELETE CASCADE where apt)
# 2. re-apply it to your dev database (idempotent, safe to re-run):
mysql -u root -p db2 < src/main/resources/schema.sql
# 3. add/adjust the model POJO + rowmapper + query + repo/impl (see developer-guide.md §5.2)
```

`schema.sql` is **idempotent** (`CREATE TABLE IF NOT EXISTS`, `INSERT … ON DUPLICATE KEY UPDATE`), so re-running it never destroys data. There is **no Flyway/Liquibase** — that was removed on purpose; do not reintroduce it. For business tables you add a JPA `@Entity` instead and let `ddl-auto: update` create it — but remember the `@Column(name="snake_case")` rule, because `globally_quoted_identifiers: true` otherwise produces literal camelCase column names (see [configuration.md §8](configuration.md#8-configuration-gotchas-read-this) gotcha #3).

> **First-run reminder:** a fresh database needs `schema.sql` applied once, or the app fails with errors like `Column 'using_totp' not found` / missing roles. Full table-by-table reference: [database.md](database.md).

---

## 6. Feature-branch workflow

The default integration branch is **`master`**; feature work happens on topic branches and merges back via PR.

```bash
git checkout master && git pull          # start from current master
git checkout -b feat/<short-topic>       # branch first — never commit straight to master
# … edit, build, test (see §7) …
git add -p && git commit                 # small, descriptive commits
git push -u origin feat/<short-topic>
gh pr create                             # open the PR against master
```

Conventions that apply to every branch:

- **Branch before you commit.** If you're on `master`, create a topic branch first.
- **Verify before you push.** Run the build/test gates in [§7](#7-build-test--verify) and smoke-test via `./start.sh` (see [§2](#2-the-startsh-dev-loop)).
- **Keep the public-route lists in lockstep.** If a change adds a public endpoint, update **both** `Constants.PUBLIC_URLS` (filter-chain `permitAll`) and `Constants.PUBLIC_ROUTES` (filter skip) — a route public in one but not the other breaks on a stale `Bearer` header ([security.md §12](security.md#12-public-endpoints)).
- **Update the docs in the same change.** If a diagram/doc and the code disagree, **the code wins** and the doc should be fixed in the same PR.
- **Commit/push only when asked**, and don't bypass hooks or signing.

---

## 7. Build, test & verify

| Gate | Command | Notes |
|------|---------|-------|
| Backend compile + unit tests | `mvn test` | 6 test suites / 14 tests today: full context load, `CustomerServiceImpl`, global exception handler, login anti-enumeration regression, and an offline JPA↔`schema.sql` drift guard (`JpaSchemaSyncTest`). Security-critical-path tests (rotation/reuse, TOTP challenge binding, org scope) and frontend specs remain the priority gap. |
| Backend package | `mvn package` | Builds the runnable jar (Angular is *not* bundled here — that happens only in the Docker multi-stage build). |
| Dependency CVE scan | `mvn verify` (OWASP `dependency-check-maven`, pom.xml:224) | `failBuildOnCVSS=7` — fails the build on a high-severity CVE. |
| Frontend tests / lint / format | `npm test` · `npm run lint` · `npm run format` (from `tesseraapp/`) | Vitest + ESLint + Prettier. |
| End-to-end smoke | `./start.sh` (`ENV=local`), then log in as `eve.admin@tessera.dev` / `TesseraDemo@1` | The fastest "does the whole thing still work" check. |

> **Verifying a change:** prefer running the real app via `./start.sh` (foreground) over asserting from tests alone — confirm the actual screen/endpoint behaves. Ask before any destructive DB operation.

---

## 8. Known limitations & planned improvements

Status legend: ✅ done · ⚠️ works but rough · ❌ planned / not yet applied.

| Item | Status | Detail |
|------|--------|--------|
| Maven Wrapper not used by the loop | ❌ | The repo **ships** `mvnw` + `mvnw.cmd` in the project root, but `start.sh:153` and every doc command invoke bare **`mvn`** — i.e. whatever Maven is on the contributor's `PATH`. **Recommended (not yet applied):** switch `start.sh` and the documented commands to **`./mvnw`** (and `mvnw.cmd` on Windows) so the build pins a known, repo-controlled Maven version and works without a system Maven install. This is a tooling change only — no application code is affected. |
| MySQL 3306 not published in Compose | ❌ | The `mysql` service in `docker-compose.yml` has **no `ports:` mapping** — 3306 is reachable only on the internal compose network (which is fine for `ENV=docker`, where the `app` container talks to `mysql:3306`). In `ENV=local DB=local`, however, the host-native backend points at `127.0.0.1:3306` and cannot reach the container. **Recommended (not yet applied):** add `ports: ["3306:3306"]` to the `mysql` service so the started container is exactly what the native backend (and any SQL GUI) connects to. |
| Breakpoint debugging vs `start.sh` | ⚠️ | `start.sh` backgrounds `mvn`, so IDE breakpoints require running the backend from the IDE instead (or attaching via JDWP) — see [§3](#3-backend-debugging). |
| Profile-image storage is local filesystem | ⚠️ | Uploaded images are written to a local path (`IMAGE_STORAGE_PATH`, defaulted to a Docker volume in Compose, docker-compose.yml:30) — not object storage. Fine for dev; revisit for multi-instance prod. |
| Test coverage | ⚠️ | Real but modest (14 tests / 6 suites). No frontend specs yet; no dedicated tests for the security-critical paths. |

These two ❌ items are the concrete next tooling fixes; until they land, use `mvn` (with a system Maven) and, for `ENV=local DB=local`, keep a host MySQL on 3306.

---

> **History:** this guide was added to consolidate the developer-loop knowledge that was previously scattered across `getting-started.md` (run-it-once), `configuration.md` (env/profiles), and `developer-guide.md` (extension recipes). For the *why* behind any setting, follow the cross-links rather than duplicating detail here.
