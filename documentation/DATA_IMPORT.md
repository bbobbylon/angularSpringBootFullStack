# Importing real data into the Docker MySQL container

This doc covers the one-time operational step of copying user/customer/invoice
rows from your **native MySQL** install (on Windows host, port 3306) into the
**Docker MySQL** container (`securecapita-mysql`, mapped to host port 3307).

Schema is *not* in scope here — Flyway manages that automatically via
`src/main/resources/db/migration/V1__baseline_schema.sql`. The container will
already have all the right tables before you import.

---

## Mental model

| Layer | Purpose | Lives in |
|---|---|---|
| Schema (DDL) | `CREATE TABLE`, indexes, constraints, reference data (roles, event types) | Flyway migrations in source control |
| Real data (DML) | User accounts, customers, invoices, audit log entries | Each environment's database — **never** in source control |

The native MySQL on your host is essentially "an environment" — it just happens
to be one running on your developer machine. Treating its data as something to
import is the same conceptual operation as restoring a production backup into
a fresh staging DB.

---

## Step 0 (one-time) — Align native DB column names with V1

The Flyway baseline (`V1__baseline_schema.sql`) standardises every column to
snake_case, matching Spring Boot's default `SpringPhysicalNamingStrategy` and
the existing `users` / `roles` / `events` tables. Older native-MySQL databases
created when `hibernate.globally_quoted_identifiers: true` was on will carry
camelCase column names (`createdAt`, `imageUrl`, `customerId`, etc.) and will
not import cleanly into the V1 schema.

Run these `ALTER TABLE`s against your **native** MySQL `db2` once, before
the dump, so the dumped INSERT statements reference the correct column names:

```sql
USE db2;

-- customer ────────────────────────────────────────────────────
ALTER TABLE customer
  CHANGE COLUMN createdAt   created_at   DATETIME(6),
  CHANGE COLUMN imageUrl    image_url    VARCHAR(255),
  CHANGE COLUMN phoneNumber phone_number VARCHAR(255);

-- invoice ────────────────────────────────────────────────────
ALTER TABLE invoice
  CHANGE COLUMN invoiceNumber invoice_number VARCHAR(255),
  CHANGE COLUMN customerId    customer_id    BIGINT,
  CHANGE COLUMN invoiceDate   invoice_date   DATETIME(6),
  CHANGE COLUMN totalAmount   total_amount   DOUBLE,
  DROP COLUMN IF EXISTS service,
  DROP COLUMN IF EXISTS services;
```

> The two `DROP COLUMN`s remove legacy columns left over from older versions
> of the `Invoice` entity. They no longer have a Java mapping; their data
> (if any) is unused by the current application.

Verify with `DESCRIBE customer;` and `DESCRIBE invoice;` — every column
should be snake_case before you proceed.

---

## Step 1 — Dump the native DB on the Windows host

Run this in PowerShell (or cmd) from any directory. `mysqldump` ships with the
native MySQL install at `C:\Program Files\MySQL\MySQL Server 8.x\bin\mysqldump.exe`
— add that to PATH if it isn't already.

```powershell
# Dump DATA ONLY for the JPA-managed business tables and the user/auth tables.
# --no-create-info  -> skip CREATE TABLE statements (Flyway already built the schema)
# --skip-triggers   -> we don't use triggers; skipping avoids privilege issues on restore
# --single-transaction -> consistent snapshot of InnoDB tables, no table-locking
# --complete-insert -> emits column names in every INSERT; safer when column order differs
# --no-tablespaces  -> avoids needing PROCESS privilege; harmless for typical schemas
mysqldump `
  -u root -p `
  --no-create-info `
  --skip-triggers `
  --single-transaction `
  --complete-insert `
  --no-tablespaces `
  db2 `
  users roles userroles events userevents `
  accountverifications resetpasswordverifications twofactorverifications `
  customer invoice services invoiceserviceitems `
  > seed-data.sql
```

The output file `seed-data.sql` will contain only `INSERT INTO ... VALUES (...)`
statements — no schema. This file is **gitignored** by convention; do not commit it.

> **Heads-up about old column drift**
> If your native `invoice` table still has the legacy `service` and `services` columns
> that the current entity no longer maps, the dump will reference them and the
> import will fail with `Unknown column`. Two options:
> 1. **Recommended:** drop those columns on the native DB first
>    (`ALTER TABLE invoice DROP COLUMN service, DROP COLUMN services;`)
>    then re-dump. This brings the native schema in line with V1.
> 2. **Quick fix:** open `seed-data.sql` and delete the `service` / `services`
>    entries from the `INSERT INTO invoice (...) VALUES (...)` lines.

> **`ResetPasswordVerifications` table-name drift**
> V1 normalises the table to all-lowercase (`resetpasswordverifications`) for
> Linux-MySQL portability. If your native dump references the PascalCase form,
> `sed -i 's/ResetPasswordVerifications/resetpasswordverifications/g' seed-data.sql`
> before importing.

---

## Step 2 — Import into the running Docker MySQL

With `docker compose up -d` already running and the schema established by Flyway:

```powershell
# -T disables TTY allocation, letting PowerShell pipe the file into stdin.
# $env:MYSQL_ROOT_PASSWORD is set by your .env file — read it from there.
Get-Content seed-data.sql | docker exec -i securecapita-mysql `
  sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" db2'
```

Or, equivalently, by copying the file into the container first:

```powershell
docker cp seed-data.sql securecapita-mysql:/tmp/seed-data.sql
docker exec securecapita-mysql sh -c `
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" db2 < /tmp/seed-data.sql'
```

---

## Step 3 — Verify

```powershell
docker exec securecapita-mysql sh -c `
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" db2 -e "
     SELECT (SELECT COUNT(*) FROM users) AS users,
            (SELECT COUNT(*) FROM customer) AS customers,
            (SELECT COUNT(*) FROM invoice) AS invoices;"'
```

Hit <http://localhost:8081> (Adminer) — log in with server `mysql`, user
`root`, password from your `.env` — and confirm rows are visible across all
the expected tables.

---

## When NOT to use this procedure

- **In production.** Production data comes from the production DB; you don't
  dump-and-restore between environments without a formal data-migration plan.
- **For dev fixtures.** If you want a known, repeatable dev seed (e.g. "ten
  test customers with three invoices each"), add a `R__seed_dev_data.sql`
  repeatable migration in `src/main/resources/db/migration/dev/` and load it
  via a `dev`-profile-only Flyway location. That keeps it under source control
  and applies automatically — no manual import step required.
