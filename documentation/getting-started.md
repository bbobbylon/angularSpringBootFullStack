# Getting Started

The fastest path from a fresh clone to a running SecureCapita instance you can log into. For the *why* behind each setting, follow the cross-links into the deeper guides.

> **Time to first login:** ~10 minutes (local mode).
> **See also:** [configuration.md](configuration.md) (every setting) · [architecture.md](architecture.md) (how it fits together) · [deployment.md](deployment.md) (Docker/cloud).

---

## Table of contents

1. [Prerequisites](#1-prerequisites)
2. [Get the code](#2-get-the-code)
3. [Configure `.env`](#3-configure-env)
4. [Create & seed the database](#4-create--seed-the-database)
5. [Run it](#5-run-it)
6. [Log in](#6-log-in)
7. [Running the pieces individually](#7-running-the-pieces-individually)
8. [Common first-run problems](#8-common-first-run-problems)
9. [Next steps](#9-next-steps)

---

## 1. Prerequisites

| Tool | Version | Needed for |
|------|---------|------------|
| **JDK** | 21+ | the Spring Boot backend |
| **Maven** | 3.8+ | building/running the backend |
| **Node.js** | 22 LTS (or 20.19+) + npm | the Angular frontend |
| **MySQL** | 8.x | the database — *or* use Docker (below) |
| **Docker** | 24+ | optional: supplies MySQL in local mode, or runs the whole stack in docker mode |
| **Bash** | any | `start.sh` is a Bash script — on Windows use **Git Bash** or WSL |

You need **either** a local MySQL install **or** Docker (which can provide MySQL for you). You don't need both.

---

## 2. Get the code

```bash
git clone <repo-url>
cd angularSpringBootFullStack
```

---

## 3. Configure `.env`

All local settings live in one `.env` file at the project root.

```bash
cp .env.example .env            # Git Bash / macOS / Linux
Copy-Item .env.example .env     # PowerShell
```

Open `.env` and set, at minimum:

```dotenv
MYSQL_DATABASE=db2
MYSQL_USERNAME=root
MYSQL_PASSWORD=your-db-password
MYSQL_ROOT_PASSWORD=your-db-password    # used only by the Docker MySQL container
JWT_SECRET=<a-long-random-string>
```

Generate a strong `JWT_SECRET`:

```bash
openssl rand -base64 48                                          # Git Bash / macOS / Linux
[Convert]::ToBase64String((1..48 | % { Get-Random -Max 256 }))  # PowerShell
```

Email, OAuth, and Twilio variables are optional for a first run — see [configuration.md](configuration.md) for the complete reference. (Without mail settings, account-verification emails simply won't send; you can still seed/verify accounts directly.)

---

## 4. Create & seed the database

> Skip the manual MySQL steps entirely if you'll use **Docker** in local mode — `start.sh` brings up a MySQL container for you. (You'd still apply `schema.sql` once, into that container.)

Against your MySQL server:

```bash
# create the schema
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS db2;"

# create the identity/auth tables + seed roles, events, demo orgs
mysql -u root -p db2 < src/main/resources/schema.sql
```

- `schema.sql` is **idempotent** — safe to re-run.
- The `customer` / `invoice` / `services` tables are created automatically by Hibernate the first time the app boots; you don't run anything for them.

Details and the full table map: [database.md](database.md).

---

## 5. Run it

Everything is driven by **`start.sh`**. Open it and set the two switches at the top:

```bash
ENV=local     # local | docker
DB=local      # local | aiven   (only relevant when ENV=local)
```

Then:

```bash
chmod +x start.sh
./start.sh
```

### Local mode (`ENV=local`) — recommended for development
Spring Boot runs natively (hot-restart via DevTools) and Angular runs via `ng serve` (hot-reload).

| `DB` | What happens | Requires |
|------|--------------|----------|
| `local` | Starts a **MySQL Docker container** and waits for health | Docker running |
| `aiven` | Skips Docker; connects directly to **Aiven cloud MySQL** | `AIVEN_DB_*` set in `.env` |

➡ **Open http://localhost:4200**

### Docker mode (`ENV=docker`) — production-like
Builds the multi-stage image (Angular compiled into the Spring Boot JAR) and runs app + MySQL via Docker Compose. No hot-reload.

➡ **Open http://localhost:8090** (or `APP_PORT` from `.env`)

Press **Ctrl+C** to stop everything cleanly.

---

## 6. Log in

On the `dev` profile a seeder creates one demo user per role — all with the password **`TesseraDemo@1`**:

| Email | Role | Good for testing |
|-------|------|------------------|
| `eve.admin@tessera.dev` | `ROLE_ADMIN` | the admin dashboard (`/users`, `/roles`) |
| `frank.app@tessera.dev` | `ROLE_APPLICATION_ADMIN` | full access incl. delete |
| `alice.guest@tessera.dev` | `ROLE_GUEST` | minimal read-only access |

Or register a brand-new account at **`/register`**. (New accounts need email verification to become `enabled`; in dev the verification link is logged to the server console.)

**You'll know it works when:** logging in as `eve.admin@tessera.dev` lands you on the dashboard with the **Users** and **Roles** admin links visible in the navbar.

---

## 7. Running the pieces individually

Sometimes you want just one half (e.g., to attach a debugger):

```bash
# Backend only — needs the env vars present (load .env or export them first)
mvn spring-boot:run

# Frontend only (from securecapitaapp/)
npm install
npm start            # dev server on http://localhost:4200, proxies API calls to :8080
```

> ℹ A bare `mvn spring-boot:run` boots with the dev profile's built-in literal defaults — **no `.env` required** (you do need a running MySQL on `127.0.0.1:3306`; see below). `start.sh` still exports the variables for you and remains the recommended path; setting any variable overrides the dev default.

---

## 8. Common first-run problems

| Symptom | Cause & fix |
|---------|-------------|
| `Could not resolve placeholder 'MYSQL_USERNAME'` (or `'JWT_SECRET'`, etc.) | A **required** env var is missing. Happens in **prod/CI**, not dev — dev ships literal defaults. Set the variable (run via `./start.sh`, load `.env`, or use the platform config), or launch the dev profile. See [configuration.md §8](configuration.md#8-configuration-gotchas-read-this). |
| `Communications link failure` / `No such host is known (mysql)` | `MYSQL_HOST=mysql` (Docker service name) used outside Docker. Set `MYSQL_HOST=127.0.0.1`. |
| `Column 'using_totp' not found` (or missing-role errors) | Database not initialised. Apply `schema.sql` (step 4). |
| Port 8080/4200/3306 already in use | Another process (or a previous run) holds the port. Stop it, or change the port. |
| MySQL container never becomes healthy | `docker compose logs mysql` — usually a wrong `MYSQL_ROOT_PASSWORD` or 3306 already bound locally. |

---

## 9. Next steps

- **Understand the system** → [architecture.md](architecture.md)
- **Call the API** → [api-reference.md](api-reference.md)
- **Auth internals (JWT, MFA, RBAC)** → [security.md](security.md)
- **Deploy it** → [deployment.md](deployment.md)
- **Go deep** → [developer-guide.md](developer-guide.md)
