---
type: Guide
title: Link analysis PostgreSQL store - admin guide
description: Install, configure, secure, back up and troubleshoot the PostgreSQL store for Investigations.
resource: la/inspecto-la-store-pg/
tags: [module, la-store-pg, link-analysis, postgres, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis PostgreSQL store - admin guide

Use this store when several pods serve one Space, or when Investigations must live in a managed database. Design:
[architecture](architecture.md).

## Install

1. Use an Advanced or Preview bundle: the module is staged there (`offerings/advanced.toon`).
2. Provide a PostgreSQL database and a user that can create a schema and tables in it. The store creates the schema
   `space_<space id>` (dashes become underscores) and its tables on first use; there is no separate migration step.
3. Set the JVM properties below and restart.

## Configure

| Property | Default | Meaning |
|---|---|---|
| `-Dinvestigations.backend` | `fs` | set to `db` to use this store; any other value answers 503 |
| `-Dinvestigations.db.url` | falls back to `-Dinspecto.db.url` | must start `jdbc:postgresql:` |
| `-Dinvestigations.db.user` | falls back to `-Dinspecto.db.user` | may be omitted when the URL carries it |
| `-Dinvestigations.db.password` | falls back to `-Dinspecto.db.password` | resolved through the secret resolver, so `${ENV:NAME}` works |
| `-Ddb.pool.size` | platform default | HikariCP pool size, shared with the other PostgreSQL stores' sizing |

Example:

```
-Dinvestigations.backend=db
-Dinvestigations.db.url=jdbc:postgresql://db.internal:5432/inspecto
-Dinvestigations.db.user=inspecto
-Dinvestigations.db.password=${ENV:INSPECTO_INV_DB_PASSWORD}
```

Size limits are Space settings and apply to both stores: `max_set_bytes` (64 MiB default) and
`max_investigation_bytes` (4 GiB default); over either, a write is refused 413.

## Secure

- Keep the password out of command lines: use a `${ENV:NAME}` reference. The connection string is never logged with
  its password (only the URL before `?`).
- Use TLS parameters on the JDBC URL (`sslmode=verify-full` and a trusted certificate) when the database is remote.
- Sealed rows are protected by append-only triggers; a database owner can still drop the schema, so restrict who holds
  that role.

## Back up and restore

Back up the database (for example `pg_dump --schema=space_<id>` per Space). Investigation data is no longer under the
Space write root once `db` is selected; there is no import from or export to the filesystem store, so switching backends
starts a Space with no Investigations.

## Health and troubleshooting

`GET /health/details` shows `live.investigations` UP or DOWN per Space with the cause; the server log WARNs once when it
turns DOWN and logs INFO when usable again.

| Symptom (503 `CAPABILITY_UNAVAILABLE`, "Investigation store unavailable: ...") | Fix |
|---|---|
| "... is not a backend (fs or db)" | correct `-Dinvestigations.backend` |
| "... this bundle carries no database Investigation store" | use an Advanced or Preview bundle |
| "... needs a connection" / "needs a jdbc:postgresql: URL" | set `-Dinvestigations.db.url` and restart |
| "the Investigation database cannot be used: cannot connect ..." | database down or credentials wrong; an outage recovers on its own, a wrong setting needs a restart |
| "Space id ... cannot be made a PostgreSQL schema name" | the Space id has characters a schema name cannot hold |
| "the database Investigation store module is broken" | two providers on the classpath, or a corrupt jar |
