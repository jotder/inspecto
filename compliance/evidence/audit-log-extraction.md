# Audit-log extraction — a repeatable pull for an auditor

**Audience:** the deploying organization producing evidence; the auditor receiving it.
**Control:** SOC 2 **CC4** (monitoring) / **CC6** (logical access evidence) · ISO 27001
**8.15–8.17** (logging & monitoring) · NIST **AU** family. Matrix row:
[`../controls-matrix.md`](../controls-matrix.md).
**Closes:** compliance plan C3 gap **G4**.

## 1. What the audit trail contains — and what it does not

Audit records are ordinary **Events** with `type = AUDIT`, captured at a single seam
(`inspecto/.../control/AuditTrail.java`) called once from `ControlApi.dispatch` after a request
resolves. One seam means every current **and future** mutating route is covered without per-handler
wiring — there is no list of audited endpoints to fall out of date.

Captured:

- successful `POST`/`PUT`/`DELETE` that classify as a real mutation (create/update/delete/trigger);
- `GET …/export` — data-export actions (Category B);
- **access-denied attempts** — a non-`GET` to a forbidden/unknown route (404), or a disallowed
  method on a read-only route (405).

⚠ **Deliberately NOT captured, and you must say so rather than let an auditor assume otherwise:**

- **Authentication events — login, MFA, password, true 401/403 — are out of scope** of this trail.
  They arrive with the security module. An auditor asking "show me failed logins" must be pointed
  at the **IdP**, which owns authentication; Inspecto sees an already-authenticated subject.
- Diagnostic `POST`s are skipped as non-mutating: `/test`, `/preview`, `/dry-run`, `/validate`,
  `/assist/*`. They change nothing, so they are noise in an audit trail — but if an auditor asks
  for "all POSTs", this is the reason the counts differ.

## 2. The fields an audit record carries

Beyond the common Event fields (`eventId`, `ts`, `timestamp`, `level`, `type`, `source`,
`pipeline`, `correlationId`, `message`), an `AUDIT` event carries these under **`attributes`**
(`inspecto-event/.../AuditAttrs.java`):

| Attribute | Meaning |
|---|---|
| `actor`, `actor_type` | who acted |
| `action`, `action_category` | what they did |
| `target_type`, `target_id` | what they did it to |
| `ip`, `user_agent` | where from |
| `http_method`, `http_path`, `http_status` | the request itself |
| `abac_action`, `policy` | the authorization decision and the policy that matched |

## 3. CSV and JSON are both audit-complete — ✅ FIXED 2026-08-28 (was AUDIT-CSV-1 / G10)

`GET /events/export?format=csv&type=AUDIT` (and `type=ACCESS_DENIED`) now emits the audit-shaped
CSV: the seven base columns plus one column per §2 attribute key, in the order declared by
`AuditAttrs.ALL` (the projection derives its columns from that one list; a reflection test pins the
list against the constants so a new key cannot miss the export):

```
timestamp,level,type,source,pipeline,correlationId,message,actor,actor_type,action,action_category,target_type,target_id,ip,user_agent,http_method,http_path,http_status,abac_action,policy
```

An **unfiltered** CSV export keeps the seven-column operational-triage shape — attributes of mixed
event types are not projected. For the audit trail, filter to `type=AUDIT` (CSV or JSON both carry
the attributes); JSON additionally carries `payload`.

*History:* before 2026-08-28 the CSV silently dropped every audit attribute — an audit CSV looked
complete (right row count, right timestamps) while omitting everything the audit records. Filed as
AUDIT-CSV-1 / matrix G10; fixed by the audit-shaped projection in `EventRoutes.eventsCsv`.

## 4. The extraction

All routes are `GET` and read-only. Substitute your host, and authenticate as your deployment
requires.

**A dated slice of the audit trail (JSON — the one to use):**

```bash
curl -s 'http://<host>/events/export?type=AUDIT&from=2026-01-01T00:00:00Z&to=2026-03-31T23:59:59Z' > audit-q1.json
```

`from`/`to` are timestamps; `type=AUDIT` restricts to audit records. Export uses the **maximum**
row limit (10 000 — `EventQuery.MAX_LIMIT`), so **a window that returns exactly 10 000 rows is
almost certainly truncated**: narrow the window and pull again rather than assuming that is all
there was.

**Paging the full retained history** (buffer **and** Parquet, newest first, stable under concurrent
writes because it is keyset-paginated on `(ts, eventId)`):

```bash
curl -s 'http://<host>/api/v1/events?limit=500'
```

`limit` is clamped to 500. Follow `metadata.pagination.nextCursor` and pass it back as `?cursor=`
until it is absent. ⚠ Use this, not the legacy `GET /events?limit=`, for anything historical: the
legacy shape serves only the **live-tail ring**, so it silently answers from a small recent window.

**Narrowing further** — `/events/search` accepts `level`, `type`, `pipeline`, `correlationId`,
`q` (text contains), `from`, `to`, `limit`, `offset`. `correlationId` is the one to reach for when
tracing a single request end to end across subsystems.

**A single record:** `GET /events/{eventId}`.

## 5. Chain of custody

- **The extraction is itself audited.** `GET …/export` is a Category B action, so pulling evidence
  leaves its own audit record naming the actor. Mention this to the auditor before they discover
  it — it is a control working, not a surprise.
- Record, alongside each extraction: the exact URL (window and filters), the UTC time it was run,
  the actor, and the row count returned. An extraction that cannot be re-run to the same result is
  not evidence.
- Timestamps render in the **operations time zone** (`-Dops.timezone`), which is not necessarily
  the data's zone or the reader's. State the configured zone when handing over an extract, or
  compare `ts` (epoch millis) instead.

## 6. Known limitations to disclose

1. **Authentication events are absent** (§1) — the IdP owns them.
2. **CSV export omits the audit attributes** (§3). Filed as a product gap in the matrix; until it
   is closed, JSON is the only complete extraction.
3. **Retention is not yet configurable** for the event/audit store (matrix gap **G5**, NIST
   AU-4/AU-11). Extract before any retention pressure applies, and do not claim a retention period
   the deployment does not enforce.
