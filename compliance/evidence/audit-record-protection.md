# Audit-record protection (AU-9)

**What this document is:** a grounded statement of what the audit/event store *actually* guarantees,
and — just as important — what it does **not**. Written 2026-08-30 against the code, not against the
design intent.

⛔ **The standing rule for this control: do not assert immutability the storage layer does not
enforce.** The honest claim here is narrower than "the audit log is immutable", and the narrow claim
is the defensible one. An auditor who disproves an overclaim discredits the controls that *are* real.

---

## 1. The statement

> Inspecto's audit event store is **append-only by construction of the write path**. Every flush
> creates a new, uniquely-named Parquet file; no code path in the application modifies, truncates or
> deletes a written audit file. All audit and event records pass through a **single dispatch seam**,
> giving one auditable point of instrumentation.
>
> **Protection of written audit files against deletion or tampering by a party with filesystem access
> to the event-store directory is NOT enforced by the application.** There is no checksum, hash
> chain, digital signature, WORM flag, or filesystem-permission hardening. That protection depends
> entirely on OS-level file permissions and deployment controls configured by the operator.

---

## 2. What is proven, and by what

| Claim | Evidence |
|---|---|
| Each flush writes a **new** file; nothing is overwritten | `inspecto-event/src/main/java/com/gamma/event/ParquetEventStore.java:166-168` — the base name is `"events_" + timestamp + "_" + flushSeq`; `:81-82` states the uniqueness is deliberate ("no overwrite") |
| No delete/overwrite/truncate path exists against the event directory | No `Files.delete`, `deleteIfExists` or truncate call anywhere in `inspecto-event`. ⚠ The one nearby `delete` — `EventRoutes.java:34`, `/events/views/{name}/delete` — removes a **saved query view**, not event data (`AuditTrail.java:158`) |
| One dispatch seam | `EventLog.emit` (`inspecto-event/src/main/java/com/gamma/event/EventLog.java:142`) is the sole entry point; the SLF4J capture appender (`EventStoreAppender.java:44`), direct callers and the batch-event bridge all route through it |
| The API contract excludes update/delete | `EventStore.java:15-16` — "intentionally no update or delete" |
| A transient write failure retries rather than silently dropping | `ParquetEventStore.java:170-186`; the drop path itself logs at ERROR (`:182`) |

**Event record fields** (`Event.java:14-24`): `eventId` (UUID), `ts` (epoch ms, UTC), `level`, `type`,
`source`, `pipeline`, `correlationId`, `message`, `attributes`, `payload`.

---

## 3. What CANNOT be claimed 🔴

These are the overclaims to refuse, each with the reason:

- **Not "immutable" or "tamper-proof" at the storage layer.** There is no checksum, hash chain,
  signature or WORM flag, and no code sets filesystem permissions — a repo-wide search for
  `PosixFilePermission` / `setPosixFilePermissions` returns **nothing**. Parquet's internal checksums
  detect *corruption*, not deliberate edits.
- **Not tamper-evident, and therefore not non-repudiable.** Nothing would reveal that a file had been
  edited or removed. Append-only is a property of *this application's* write path; any process with
  filesystem access can edit or delete the files with ordinary tools, undetected.
- **Not access-controlled by the application.** The store's root comes from `-Devents.dir` (default
  `SpaceRoot.eventsDir()`, wired at `inspecto/src/main/java/com/gamma/service/ServiceStores.java:156-174`).
  ⚠ **PathJail does not apply here** — it governs config writes reachable through Control API config
  routes; event writes go straight through DuckDB `COPY`/`PartitionWriter`. Do not cite PathJail as
  an audit-store control.
- **Not complete under sustained failure.** `MAX_RETAINED = 50_000` buffered events are **dropped**,
  not merely delayed, if flushing keeps failing (`ParquetEventStore.java:92, 182-186`). Durability
  has an explicit, documented ceiling, and an auditor is entitled to know it.

---

## 4. What would raise the claim

Not scheduled — recorded so the gap is a known one rather than an implied capability:

- A hash chain or per-file digest written to a separate location would make deletion and edit
  **evident** (not prevented). This is the smallest real step.
- OS-level enforcement — restrictive directory permissions, an append-only mount flag, or WORM
  storage — is the operator's lever, and is where actual *prevention* lives.
- ⚠ Note the interaction with retention: the one-year audit-retention window (operator, 2026-08-30)
  requires a partition-delete prune task (**COMPLY-3**). That task will be the **first code path that
  deletes audit data**, so it must be built as a narrow, audited, partition-granular operation — and
  this document must be revised when it lands, because the sentence "no code path deletes a written
  audit file" stops being true on that day.

---

## 5. Review triggers

Re-verify this statement when: a prune/retention path is added (COMPLY-3) · a new event sink or
dispatch seam appears · the event store's storage engine changes · anyone proposes citing this
control as "immutability".
