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
> to the event-store directory is NOT enforced by the application** — there is no WORM flag or
> filesystem-permission hardening, and PREVENTION depends entirely on OS-level file permissions and
> deployment controls configured by the operator. ✅ **Since 2026-09-27 (`ASSURE-AUDIT-CHAIN-1`) such
> tampering is EVIDENT:** every audit record is hash-chained per Space and each finished day is pinned by a
> MAC-signed anchor; `GET /audit/verify` names the first edited, deleted, inserted or reordered record
> (§6). Evident is not prevented, and §6 states what the chain still cannot see.

---

## 2. What is proven, and by what

| Claim | Evidence |
|---|---|
| Each flush writes a **new** file; nothing is overwritten | `platform/inspecto-event/src/main/java/com/gamma/event/ParquetEventStore.java:166-168` — the base name is `"events_" + timestamp + "_" + flushSeq`; `:81-82` states the uniqueness is deliberate ("no overwrite") |
| No delete/overwrite/truncate path exists against the event directory | No `Files.delete`, `deleteIfExists` or truncate call anywhere in `inspecto-event`. ⚠ The one nearby `delete` — `/events/views/{name}/delete`, since EDG-01 cell 6 (2026-09-08) at `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java` — removes a **saved query view**, not event data (`AuditTrail.java:158`). ⛔ That route is now in an OPTIONAL module absent from Personal, which only narrows this surface further; event recording and the core audit read (`AuditLogRoutes`) are unaffected |
| One dispatch seam | `EventLog.emit` (`spi/inspecto-audit-spi/src/main/java/com/gamma/event/EventLog.java:142`) is the sole entry point; the SLF4J capture appender (`EventStoreAppender.java:44`), direct callers and the batch-event bridge all route through it |
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
- ~~**Not tamper-evident, and therefore not non-repudiable.**~~ **Superseded 2026-09-27** — the audit
  records are now tamper-EVIDENT (§6). ⚠ Still **not non-repudiable**: the anchor MAC is symmetric (HMAC with
  a key a local administrator can read), so it is not a signature a third party can verify. Append-only is
  still a property of *this application's* write path; any process with filesystem access can still edit or
  delete the files — what changed is that `/audit/verify` then reports it.
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

- ~~A hash chain or per-file digest written to a separate location would make deletion and edit
  **evident** (not prevented).~~ ✅ **Built 2026-09-27** (§6).
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

---

## 6. Tamper evidence — the audit hash chain (2026-09-27, `ASSURE-AUDIT-CHAIN-1`)

| Claim | Evidence |
|---|---|
| Every `AUDIT` / `ACCESS_DENIED` record carries `audit_seq`, `audit_prev_hash` and `audit_hash` (SHA-256 over a canonical encoding, prevHash included), in one total order per Space | `spi/inspecto-audit-spi/src/main/java/com/gamma/event/AuditChain.java`; linked in `EventLog.emit` under one monitor with the append |
| An edit, deletion, insertion, reordering or tail truncation is named by seq and reason | `GET /audit/verify` → `inspecto/src/main/java/com/gamma/control/AuditVerifier.java`; each case tampered directly in the Parquet store in `AuditVerifierTest` |
| Each finished UTC day is pinned by an HMAC-SHA256 anchor kept outside every import, export, Exchange and backup, and exportable off the box | `inspecto/src/main/java/com/gamma/control/AuditAnchors.java`; `GET /audit/anchors`; `<config root>.secrets/audit-anchors.jsonl` |
| No audit row leaves the chain silently: an unlinkable row is marked, counted and announced, a corrupt store file is reported, a second writer on one directory is refused | `AuditChainTest`, `AuditVerifierTest` (`unlinked`, `unreadable-file`, `duplicate-event`) |
| The anchor file is verified end to end (each anchor MACs the previous one); a garbled, removed or missing anchor fails, and nothing is re-signed over it | `AuditVerifierTest` (`anchor-chain-broken`, `anchor-unreadable`, `anchor-missing`, missing file refused) |
| The chain writer lock is checked by identity before every link; a durable anchoring record makes a deleted or truncated anchor file always a failure; only the configured retention may remove anchored rows; a problem is acknowledged only by a signed, reasoned BREAK anchor that verify names | `AuditChainTest.aDeletedWriterLockStopsTheFirstWriterBeforeASecondCanFork`, `AuditVerifierTest` (`anchor-file-missing`, `anchor-file-truncated`, retention cutoff, `acknowledged-break`) |
| A rebaseline is never invisible (the default verify is not ok while a break exists; the break is its own chained audit event and the replaced anchor file is pinned by digest); anchored rows are accepted as gone only where a chained prune record says the prune removed them | `AuditVerifierTest` (`acknowledged-break`, `prior-epoch-*`, prune record), `ControlApiAuditChainTest`, `MaintenanceLibraryTest.eventPruneRecordsWhatItRemovedOnTheAuditChain` |
| A restart or a hard-kill journal replay neither forks nor duplicates the chain | `AuditChainTest` (restart, abandoned store replayed, already-flushed journal) |

🔴 **What it does NOT give, stated so it is not overclaimed:** a chain rewritten end to end after the
last anchor verifies (SHA-256 needs no key — only an anchor catches it); records deleted from the front
are indistinguishable from retention (`fromGenesis: false`); a local administrator can read the key and
re-sign EVERY anchor (the anchor file is itself chained and owner-only, but that does not stop a key holder) — so on-box anchors are no evidence against that party and must be exported off the box on a schedule; anchors
are not in backups; records stored before 2026-09-27 are outside the chain. ⛔ Cite it as
**tamper-evident**, never as tamper-proof or non-repudiable.
