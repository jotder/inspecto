---
type: Concept
title: Events & Metrics — Notifications and audit — the audit hash chain onward
description: Part 2 of 4 of Events & Metrics: 
tags: [control-plane, events, metrics, observability, deadlock]
timestamp: 2026-07-16T00:00:00Z
item: module.event
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-event
---

# Events & Metrics — Notifications and audit — the audit hash chain onward

Part 2 of 4 of [Events & Metrics](spec-events-metrics.md) · previous: [Notifications and the audit trail](spec-events-metrics-notifications.md) · next: [Authorable notification rules and Alert Rule authoring](spec-events-metrics-alert-rules.md).

## Notifications & audit trail (shipped 2026-06-29, `ddfa288`) (continued)

* **Tamper evidence — the audit hash chain (`ASSURE-AUDIT-CHAIN-1`, 2026-09-27).** Every `AUDIT` and
  `ACCESS_DENIED` event a Space's `EventLog` emits is linked onto that Space's chain
  (`spi/inspecto-audit-spi/src/main/java/com/gamma/audit/AuditChain.java`): attributes `audit_seq` (from 1),
  `audit_prev_hash` (`""` at genesis — the Entity Fact log's convention) and `audit_hash`, SHA-256 over a
  canonical encoding (JSON, keys sorted at every depth, nulls written, UTF-8, format version `v: 1`; the payload
  is normalised through the store's own JSON round trip first, so a double reads back as the same text).
  - **The seam is `EventLog.emit`, not `AuditTrail`** — `AuditTrail` is not the only class that emits `AUDIT` rows, and the hash must cover
    the row AFTER `SecretScrubber` has run. The log holds its `AuditChain` monitor across link + append: one
    writer per Space, so seq order is append order.
  - **No stored head.** The head is recovered from the store (`EventStore.chainHead`) on first use and after a
    store swap. A row replayed from the write-ahead journal is already linked and is appended as-is, so a hard
    kill neither forks nor duplicates the chain; the replay also skips events already flushed to Parquet (a kill
    between flush and truncate used to store them twice — a duplicate seq). A store swap re-links the carried
    audit rows onto the incoming store's head. `chainHead` THROWS on an unreadable store, because answering
    "empty" would restart the chain at genesis; the row is then kept unlinked rather than lost.
  - **Time never goes back along the chain:** linking raises `ts` to the head's when the clock reads earlier,
    which is what lets the verifier call an out-of-time record a `reorder`.
  - `GET /audit/verify?from=&to=` (seqs; `canAdminister`) walks `[from, to]` in 1000-record pages with one
    record of look-ahead, at most 100 000 per call (`complete: false` + `next`), and names the FIRST bad seq:
    `duplicate`, `gap`, `reorder`, `broken-link`, `hash-mismatch`, `anchor-mismatch`, `missing`
    (`inspecto/src/main/java/com/gamma/control/AuditVerifier.java`). A broken chain is a 200 with `ok: false`.
  - **Anchors** (`inspecto/src/main/java/com/gamma/control/AuditAnchors.java`): `{day, firstSeq, lastSeq,
    lastHash, count}`, HMAC-SHA256 with the Space's Pending Change key over `"audit-anchor\n"` + canonical JSON
    (domain-separated from the Pending Change MAC, whose input starts with `{`). Stored in
    `<config root>.secrets/audit-anchors.jsonl`, the key's sibling — outside every import, export, Exchange and
    backup. A `daily` anchor closes each finished UTC day, rolled in the background on the first request the
    Space serves after midnight (`ControlApi.routeDispatch` → `rollIfDue`); `POST /audit/anchors` anchors on
    demand; `GET /audit/anchors` exports them. Neither signs a chain that does not verify: the roll stops before
    a break and never anchors the day the break is in; on-demand answers 409, as it does when the last anchor's
    MAC fails or its record no longer hashes to what it names.
  - **Records from before 2026-09-27 carry no seq and are outside the chain**: it starts at seq 1 with the first
    row linked after the upgrade.
  - 🔴 **Anchors need a durable event store** (`BASIC-EVENTS-DURABLE-1`, 2026-11-06). The in-memory store restarts
    the chain at seq 1 on every boot, so the anchor written before a restart names a record the next boot no
    longer holds, and every later roll answers 409 — forever. Basic ran that store until 2026-11-06 (its
    `serve.*` set no `-Devents.backend`), and a Basic install sharing Spaces with a Preview one (`spaces\<id>.ref`)
    also met the anchors Preview wrote over its Parquet chain. Every edition's launcher now passes
    `-Devents.backend=parquet` (`offerings/basic.toon` posture, `tools/check-launchers.mjs`). Three code rules
    follow (operator decision A, 2026-11-06; `AuditAnchorsDurableRestartTest`, mutation-checked):
    - **Never anchor over a memory store.** The scheduled roll skips it with one INFO line per Space;
      `POST /audit/anchors` answers 409 *events are kept in memory; nothing to anchor*, and
      `POST /audit/anchors/rebaseline` 409 *… nothing to rebaseline* (a break over a memory chain would strand
      the Space again at the next boot).
    - **Every anchor carries a MAC'd `store` tag** (`memory|parquet|db|other`), so an anchor written since
      2026-11-06 can never be mistaken for a memory-era one.
    - **One automatic signed BREAK for a memory-era anchor file** (`AuditAnchors.legacyBreakIfDue`), written by the
      rebaseline machinery — the old file moved aside and pinned by digest, `by: system`, reason *anchored over a
      non-durable store before <day the durable chain starts>*, a WARN logged once, verify lists it, and the roll
      resumes at seq 1 of the durable chain. ONLY when all five hold: the store is durable; the anchor file
      verifies; the chain does not hold the record the last anchor names; the whole durable chain is newer than
      that anchor (it starts at seq 1, after the anchor's `createdAt`); and the anchor has no `store` tag. Any
      other case still refuses (409) as before — an operator rebaseline is the remedy.
    - ⚠ **Accepted cost (operator, 2026-11-06):** a Space whose last anchor predates the tag — on ANY edition,
      Standard+ anchors from before 2026-11-06 are untagged too — and whose WHOLE durable event store is wiped
      or replaced meets the same five conditions, and is labelled as this transition once. The window closes at
      that Space's first tagged anchor. It is
      still a visible, signed break in verify (`acknowledged-break`) and the anchor export — never silent — and
      no anchor written since the tag can qualify. ⚠ Until the durable chain holds its first record the roll
      still answers 409 and backs off (5 min, doubling): the break needs that first record to prove condition 4.
  - ⚠ **What it cannot see.** SHA-256 needs no key, so a chain rewritten end to end AFTER the last anchor
    verifies — only an anchor catches it (`AuditVerifierTest.aChainRewrittenEndToEndIsCaughtOnlyByTheAnchor`
    pins both halves). Rows deleted from the FRONT look exactly like retention (`prune` drops whole old days), so
    a walk starting above seq 1 reports `fromGenesis: false` rather than a break. A local administrator can read
    the key and re-sign; exporting the anchors off the box is the answer to both. Anchors are not backed up
    (`BackupTask` skips `*.secrets`), so a restored store verifies without them.
  - 🔴 **Hardened after an independent verification FAILED the first cut (2026-09-27, same day):**
    - *No silent holes.* A row that cannot be linked (unreadable head, hash failure) is stored marked
      `audit_unlinked=true`, with an ERROR event and `inspecto_audit_unlinked_total`; verify counts every
      chainable row without a seq written since the chain began → `unlinked`. One corrupt `.parquet` used to
      break the glob read, unlink every later row and still verify: chain reads now fall back to one query PER
      FILE, keep answering, and verify fails with `unreadable-file` naming it. ⚠ Reversed 2026-09-28 (security
      review of the seq index): while any file is unreadable, recovering the head REFUSES to link — the file's
      seqs are unknown, the head may be in it, and linking onto the lower head that was seen would reuse stored
      seqs (a fork) — so audit rows are stored marked unlinked until it is readable, removed, or retried by a
      verify. A held head (already recovered) is unaffected. **Ordinary reads too (2026-09-28,
      `ASSURE-AUDIT-CHAIN-RESIDUALS-1` (4)):** `query` / `page` / `count` (`/events/search`, `/api/v1/events`,
      the audit search and export) used to fail as a whole on one corrupt file and answer from the unflushed
      buffer only, an empty result that looked complete. Every Parquet read now shares one path: the files not
      known to be unreadable go in one query, split per file when it fails; an unreadable file is SKIPPED,
      named in `unreadableUnits()`, logged at ERROR and raised ONCE as a WARN `SIGNAL`
      (`signalType: events.file_unreadable`, `file: <relative path>`). It stays out — skipped, never retried —
      until it is gone or its identity (size, modification time, file key) changes, and it raises one Signal
      per identity. ⚠ Found by security review the same day: letting any successful read clear it let a
      footer-only `COUNT(*)` re-raise the Signal on every poll, and let a file whose payload page is corrupt
      pass the index's few-column aggregate, be re-chosen by a chain read and fail again FOREVER under the store
      lock (every append waits on `chainHead`). A transient failure (a full temp dir, a Windows sharing violation —
      where the file key is null, so identity is size + time only) is not permanent: `rebuildChainIndex`,
      which every verify runs first, also forgets the unreadable list, so each verify retries every file. ⚠ Those routes have no diagnostics slot, so the skip is surfaced as
      the Signal and the log, not as a count in the response body.
    - *The journal replay reads per file too* (`ASSURE-AUDIT-CHAIN-RESIDUALS-1` (9), 2026-09-28). Its
      already-flushed check (`flushedIds`) and `presentIds` used to read one glob, so one corrupt file failed the
      replay on every open and kept a crashed run's journalled events out of Parquet. They now go through the same
      per-file read. **Fail-closed choice:** a journalled event an unreadable file COULD hold — one in the same
      `level/year/month/day` partition, or any event when the file sits outside a well-formed partition — is not
      proven unflushed, so it is HELD, never written twice (a duplicate audit row is a forked chain): moved
      atomically to `pending.held.jsonl` (ERROR log naming the files, plus the file's `events.file_unreadable`
      Signal), retried on every open, dropped as already-flushed once the file reads again or written once the
      file is removed. Events in other partitions replay normally. `presentIds` answers from the readable files
      but THROWS when an id is unresolved while any file is unreadable ("absent" would let `installStore` append
      a carried row twice). ⚠ A held event is invisible to reads until released.
      Review fixes (same day): **a held audit row blocks linking** — its seq is invisible to `chainHead`, so while
      any is held `unreadableUnits()` also lists `pending.held.jsonl (N audit row(s) held back …)`; `AuditChain`
      then refuses to link (new rows are stored unlinked) rather than reuse the held seq, and `/audit/verify`'s
      `unreadable-file` names the hold as the reason for the gap. It clears on a reopen that releases the rows.
      The held file keeps every not-yet-flushed event (held AND released) until the replay flush lands, so a
      failed flush plus a crash loses none. `EventLog.installStore` carries per event: a row `presentIds` cannot
      resolve is left out (it may already be there) with an ERROR event naming it, and the rest still carry.
      `flushLocked` reports whether the batch really landed: a give-up at the retention cap (`maxRetained`,
      50 000) empties the buffer too, and replay used to read that as success and delete the journal. A replay
      flush that did not land moves its unflushed events to the held file (which no later flush truncates) and
      removes the journal; they are retried on the next open. After a give-up (buffer dropped) its chained rows
      count as held audit rows, so linking stays blocked until they are released — no seq is reused.
      *(10) staged files:* `PartitionWriter` deletes its `.staging/<tag>/` tree best-effort in a `finally`, so a
      failed COPY or reveal leaves nothing behind, and every `ParquetEventStore` file walk (`parquetFiles`,
      `hasParquet`) skips `<root>/.staging/`, so a crash's unrevealed file is never read as event rows.
    - *The anchors are a chain.* Each anchor carries `prevAnchorMac` in its MAC input and must start at the
      previous `lastSeq + 1`; a garbled line (`anchor-unreadable`), a removed or reordered anchor
      (`anchor-chain-broken`) and a finished day (before yesterday) no anchor covers (`anchor-missing`) all
      fail. The file is created owner-only like the key. The roll and `POST /audit/anchors` REFUSE (409) over a
      broken file, and over a missing or empty one while the chain holds rows that should already be anchored —
      a deleted file is never silently re-signed; an operator resolves it (⚠ there is no reset route: moving
      the file aside is a manual, visible act). An anchor whose rows were cut from the front of a day that still
      has rows is `truncated-before-anchor`; an anchor whose whole day is gone is what retention leaves, not a
      failure.
    - *Bounded work.* The roll runs on a control-plane schedule (every 10 min, once per UTC day per Space,
      backing off 5 min → 6 h after a failure) — never on a request; `POST /audit/anchors` is limited to one per
      10 s per Space (429); both walk only from the last anchor. `DbEventStore` keeps the seq in an indexed
      `audit_seq` column. The Parquet store (seq in the attributes JSON) keeps a **per-file seq index**
      (2026-09-28, `ASSURE-AUDIT-CHAIN-RESIDUALS-1` (3)): per file its chained-row count, min/max seq,
      unlinked audit-row count and newest `ts_ms`, keyed by size + modification time. It is computed FROM the
      files — one grouped query (`GROUP BY filename`) over every file not yet indexed, at the first chain read
      that meets it — and held **in memory only**: no sidecar can make it lie, a restart rebuilds it with one
      scan (what the head lookup already cost), and a file written before the index existed is indexed like
      any other (that is the legacy fallback). `chainHead` reads only the file(s) holding the highest seq;
      `chainPage(from, n)` takes files by ascending min seq until the files lying wholly at or above `from`
      guarantee `n` rows, then reads every file whose range reaches `from` and starts at or below that bound —
      so both claims on one seq are always read (duplicates stay detected) and a hole stays a hole;
      `unlinkedSince` reads only files holding an unlinked row. A chosen file that fails to read leaves the
      index and the choice is made again (bounded). **`/audit/verify` rebuilds the whole index first**
      (`EventStore.rebuildChainIndex`, one grouped scan — the old per-verify cost): size and modification time
      are both settable by whoever rewrites a file, so a same-size, same-time rewrite adding a forged row would
      otherwise be skipped by a page. Only the append path's `chainHead` and anchor paging trust the cache.
    - *One writer per directory.* The first link takes an OS lock on `<events>/.chain-writer.lock`; a second
      EventLog or process on the same directory is refused and its rows land marked unlinked. A carried row the
      incoming store already holds is not re-linked on a store swap, and verify fails one eventId at two seqs
      (`duplicate-event`).
    - ⛔ **On-box anchors do not defend against anyone who holds the key** (a local administrator can rewrite
      the store and re-sign every anchor). The defence against that party is the OFF-box copy: export
      `GET /audit/anchors` on a schedule to storage the Space's administrators cannot write, and compare.
    - *The scheduled export is built* (`ASSURE-AUDIT-CHAIN-RESIDUALS-1` (1), 2026-10-04): the `maintenance` Job
      task **`audit_anchor_export`** (`AuditAnchorExportProvider`, contributed through the
      `MaintenanceTaskProvider` seam; the logic is `AuditAnchorExport`) appends the Space's signed
      `audit-anchors.jsonl` lines VERBATIM — not re-signed, not reformatted, hashes and seq numbers only, never
      entity data — to `<out_dir>/audit-anchors.jsonl`. `out_dir` is the operator's, resolved under the Space
      config root and path-jailed like every Job path (a `*.secrets` directory is refused; `ConfigSafetyValidator`
      checks it at authoring because `out_dir` is already a job path key). Authoring or running it needs
      `canAdminister` (`JobRoutes.ADMINISTER_ONLY_TASKS`, like `event_prune`). **Fail closed, every refusal a
      FAILED run:** no anchors yet; the source anchor file does not verify; the destination is not a regular
      file, lacks a trailing newline, or its lines are not exactly the leading lines of the source (edited,
      truncated, foreign) — never repaired or overwritten. **Append-only and idempotent:** only the lines the
      destination lacks are appended and forced; a re-run with nothing new writes nothing. After a
      `rebaseline` (source opens with a BREAK anchor and shares no line with the destination) the new epoch is
      appended after the old. The destination file verifies with the Space's key through the same
      `AnchorFile.firstProblem()` the on-box file gets, so a copy truncated at the head is caught. ⚠ A tail
      truncation of the COPY is not visible to that check alone — the next run refuses it (lines no longer a
      prefix) only if the source still holds more. **Deferred:** a Sink destination (the slice is a path only —
      no credentials or cloud integration), and keeping the copy somewhere the Space's administrators cannot
      write is the operator's deployment choice, not enforced here.
  - 🔴 **Round 3 (a second verification FAILED round 2, 2026-09-27):**
    - *The writer lock is checked by identity.* An OS lock guards a file, not a path — the lock file can be
      deleted while held and a second writer locks a new one. The lock now covers a byte far past a random token
      written into the file, and before EVERY link the store re-reads the path and compares the token; on a
      mismatch it stops linking for good (rows marked unlinked, ERROR, metric).
    - *"Anchoring started" is durable.* `<config root>.secrets/audit-anchoring.json` (owner-only, created
      first-writer-wins with the first anchor) names the first anchor of the current file and the latest one
      written. Once it exists, a missing file (`anchor-file-missing`) or one that no longer holds both anchors
      (`anchor-file-truncated`) always fails and is always refused; the young-install grace applies ONLY before
      it exists. ⚠ It is exactly as strong as the secrets directory — whoever can write there can read the key.
    - *Truncation is judged against the configured retention.* `truncated-before-anchor` now compares with the
      shortest enabled `event_prune` window (`JobService.eventRetentionDays`): an anchored day whose rows are gone
      is retention only when it is before that cutoff; with no prune configured, any missing anchored day fails.
  - **Operator runbook — when `/audit/verify` fails.** (1) Read `firstBad`; do NOT delete or edit anything under
    `data/events/` or `config.secrets/`. (2) Compare the Space's last off-box export of `GET /audit/anchors` with
    the current one to see what changed, and open an Incident. (3) Only once the cause is understood, record it:
    `POST /audit/anchors/rebaseline {"reason": "…"}` (`canAdminister`, audited, once a minute). It refuses (409)
    when nothing fails, repairs nothing, moves the old anchor file aside as `audit-anchors.<ms>.replaced.jsonl`,
    and starts a new anchor file with a signed `break` anchor holding the reason, the problem verify found, the
    last good anchor and the new start seq (the head + 1). (4) From then on the DEFAULT `/audit/verify` is never
    `ok`: it answers `ok: false` with `acknowledged: true`, `breaks: [{at, reason, problem, lastGoodSeq, by, …}]`
    (every epoch, back through each replaced anchor file) and `epochStart`. `/audit/verify?epoch=current` may say
    `ok` for the current epoch alone, and still lists the breaks. The replaced file is KEPT and pinned by digest
    in the break; it missing or edited is `prior-epoch-missing` / `prior-epoch-altered`. The rebaseline is also
    its own chained audit event, `audit.rebaseline`, carrying the reason, the problem, `lastGoodSeq` and the
    actor. (5) Export the anchors off the box again.
  - 🔴 **Round 4:** truncation is judged by what a prune ACTUALLY removed. `event_prune` now writes a chained
    `events.pruned` AUDIT row (`prune_before`, `partitions_removed`, `retention_days`, the job as actor) through
    the Space's EventLog; anchored rows gone from the front are retention only for days before the cutoff of a
    VERIFIED prune record. A configured `retention_days` job that never ran accounts for nothing; the configured
    window is only reported (`retention.configuredSource`), beside `retention.source: prune-record | none`.
  - ⛔ **By design, after ANY rebaseline the default `/audit/verify` stays `ok: false` PERMANENTLY** — there is
    no route that clears a break, and none will be added: a trail that once needed a break must say so to every
    later reader. Day-to-day checking uses `?epoch=current`.
  - *A prune is only a prune in the task's own shape:* verify counts a row as a prune record only when
    `action=events.pruned` AND `source=job` AND `actionCategory=retention`. And because that record decides
    which audit rows may lawfully vanish, authoring or editing an `event_prune` job needs `canAdminister` — on
    `POST/PUT /jobs` and `POST /config/write` (on top of `canAuthorWorkbench`); a bundle import refuses to carry
    one at all.
  - ⚠ **One known window, stated so it is not overclaimed:** *lock race* — a writer that already linked rows it
    had not yet flushed when its lock file was deleted can collide with a second writer that recovered an older
    head from disk — the fork is not prevented, it is DETECTED afterwards as a `duplicate`.
  - **The one-anchor crash window is closed** (`ASSURE-AUDIT-CHAIN-RESIDUALS-1` (6), 2026-10-04). An anchor line is
    still appended and fsynced BEFORE the anchoring record's `latestMac` is updated (order unchanged), so a crash
    between the two leaves `audit-anchoring.json` one anchor behind a file that already holds it.
    `AuditAnchors.reconcile(root)` runs at `ControlApi.start()` for every Space: when the anchor file verifies as a
    whole (`firstProblem()` is null — every MAC valid, every link intact, the first anchor the one the record
    names) and holds MAC-chained anchors AFTER the one `latestMac` names, `latestMac` is advanced to the file's last;
    `firstMac` is kept. Truncating that last anchor is then `anchor-file-truncated`. ⚠ A file that does NOT verify is
    never touched — a tampered or missing mid-file anchor stays reported by `firstProblem()`, nothing is repaired
    silently — and a file BEHIND its record (a genuine truncation) is never "reconciled" forward, so a crash
    produces no false `anchor-file-truncated`. Not covered: a crash inside a `rebaseline`'s file rotation (the
    first anchor changes, so it already reads as truncated).
  - **A shared `DbEventStore` has one chain writer or the Space does not start** (`ASSURE-AUDIT-CHAIN-RESIDUALS-1`
    (2), 2026-10-04). 🔴 No honest signal COUNTS the pods sharing a database (no replica count, pod id or partition
    map reaches the store), so the guard is a try-lock the process holds for its whole life: `DbEventStore.open`
    on a `jdbc:postgresql:` URL opens a dedicated, never-pooled connection and `claimChainWriter()` takes a
    session-level `pg_try_advisory_lock` (key from `current_schema()`) on it, re-checking that the connection is still
    valid on every link. The server drops the lock with the session, so a crash never wedges the next start. It is
    a fail-closed single-WRITER guard, not a pod detector: any second process that links onto the same database is
    refused. `ServiceStores.openDbEventStore` claims eagerly and, on refusal, throws
    `Space '<id>' refuses to start … run ONE pod per Space … or -Devents.backend=parquet` — deliberately outside the
    open's degrade-to-in-memory `catch`, because degrading would silently drop the audit trail. A Postgres store
    built from a bare `ConnectionSource` (no dedicated connection) also refuses; DuckDB needs no claim (the engine
    holds an exclusive file lock) and any other engine is refused; the Parquet store's OS file lock is unchanged.
    Consequence: D6's multi-pod READ sharing of the event DB now needs one writer pod per database (a second pod
    on the same database no longer starts its Space).
  - **Residuals (not built):** (the scheduled off-box anchor export is built — `audit_anchor_export`, above;
    its Sink destination is deferred); a `DbEventStore` serves the chain reads through the `EventStore` keyset-walk
    defaults (linear per page); no offline checker tool ships (the JSON `/audit/export` carries every hashed field);
    read auditing (audit rows of who READ the trail) is deferred by D-P8.
  - **Masking on read (D-P8, operator 2026-10-06).** `AuditReadMasking` (`inspecto/.../control/AuditReadMasking.java`)
    is applied by `GET /audit/search`, `GET /audit/export` (JSON and CSV) and the `inspecto-observability` feed
    (`/events`, `/events/search`, `/events/{id}`, `/events/export`). An `AUDIT` / `ACCESS_DENIED` row served to a
    caller WITHOUT `canRevealLinkEntities` (the existing unmask capability, reused) has each attribute / payload
    value whose key names a column any Space Dataset classifies `MSISDN|IMSI|ACCOUNT|PII` replaced by the Space's
    `masked:<16 hex>` token — the `EvidenceMasker` resolver (registry `columns[].classification` + schema lineage)
    and the SAME key and token as Risk Score evidence, so tokens link across both. A masked value (≥ 4 chars) is
    also replaced inside the row's `message`. Fail closed: an untraceable lineage masks every attribute outside
    `AuditAttrs.ALL` and every payload value. Other event types pass through; a request with no Subject (Personal)
    is unrestricted, as every `withCapability` gate is. ⛔ Stored rows stay RAW, so the chain, `/audit/verify` and
    the anchors are unchanged. ⚠ Matching is by attribute KEY: a classified value carried under an unrelated key
    (or inside `target_id`) is not detected. Tests: `ControlApiAuditReadMaskingTest`.
  - **Incident / Alert reads (D-P8, `TELCO-FRAUD-MASKED-DASHBOARD-1` (c), 2026-10-14).** `ObjectReadMasking`
    (`inspecto/.../control/ObjectReadMasking.java`) masks, for a caller without `canRevealLinkEntities`, every
    `key.<column>` value the object's `dataset` classifies sensitive (token = `EvidenceMasker.tokenFor`, fail closed on
    unknown lineage), and that raw value wherever it stands in the title, description, correlation id and the other
    attributes (`key`, `alertKey`); `keyMasked:true` is added. Applied to `GET /objects`, its v1 page and
    `GET /objects/{id}`; `GET /alerts` masks the key in each `message`. Revealer and a no-Subject request see raw.
    **Dataset resolution (fail closed).** The classification comes from the `dataset` attribute, else `entityDataset`
    (LA / Screening-minted objects); an object with `key.*` and neither cannot prove a column unclassified, so every
    `key.*` value is masked. `entityKey` (`entity:[<type>:]<key>`) is masked, full id and bare key, whenever its Dataset
    has any classified column (the column is not named). Alert-promoted Incidents always carry `dataset`
    (`AlertService.openKeyIncident`); `key.*` is written nowhere else.
    **Mutation echoes (closed).** Every write route that returns an object (`/ack`, `/resolve`, `/transition`,
    `/assign`, PATCH, `/category`, `/postmortem`, `/impact`, `/findings`, `POST /objects`, and the Case open-from-entities,
    merge and split bodies) goes through the one helper `ObjectReadMasking.echo`, so a non-revealer never gets the raw key.
    Link echoes carry no object. **`/objects/by-entity` (decision for the operator, fail closed):** an object
    whose matching attribute is masked for the caller (`ObjectReadMasking.hides`) is dropped from the result, so a raw
    number a non-revealer supplies is never confirmed as under investigation (empty, indistinguishable from absence, and
    `total` does not count it); unclassified keys still match, a revealer / no-Subject is unchanged. The raw-number
    lookup of a masked key is therefore unavailable to a non-revealer (Link Analysis keeps its own alias route,
    `POST /inv/case-history`). Row titles of surviving matches are unmasked text. Real-HTTP test
    `ControlApiObjectReadMaskingTest` (list, detail, echoes, no-dataset, entityKey, by-entity); unit
    `ObjectReadMaskingTest`.
    **Residuals (4) and (6), built 2026-10-15 (`OBJECT-READ-MASKING-RESIDUALS-1`).** (4) `ObjectLink.toMap` is structural
    only (`from`, `fromType`, `to`, `toType`, `relationship`, `createdAt`; the graph edges, `/objects/{id}/links` and the link
    create echo all use it), so there is nothing to mask; a real-HTTP test pins the exact key list. Free text that CAN carry the
    key is a note: comment `body` and attachment `attributes` (name, uri) are written by users (the only system-authored
    notes, Case merge/split, name a Case title, not an Incident key). `GET /objects/{id}/comments`, `/attachments`, the
    comment create echo and `GET /notes/object/{id}[/comments|/attachments]` now replace the object's OWN classified keys inline
    for a non-revealer (`ObjectReadMasking.note`); stored notes stay raw. Not covered: the RCA-apply echo (template text) and a
    key that is not one of the object's own attributes. (6) `POST /cases/from-entities`: **decision 1 (operator to confirm):** a
    mask pseudonym (`masked:<hex>`, bare or after a type) is refused as an entity key for EVERY caller (422, value-free), because
    an Incident keyed on a token could never be found by the real key; the Link Analysis picker (`case-members.ts`) no longer
    offers a masked node, and the way to a Case from a masked node is to reveal first or join an existing Incident
    (`{objectId}`). **Decision 2 (operator to confirm):** for a non-revealer an entity of a classified Dataset (the same fail
    closed test as `entityKey` masking, `ObjectReadMasking.hides`) is refused 422 value-free BEFORE any lookup, so the answer
    is identical whether or not an Incident exists for the key (no `minted` / reuse oracle); the cost is that a non-revealer
    can no longer mint from a classified Dataset at all. A revealer or no-Subject request is unchanged. Tests:
    `ControlApiCaseFromEntitiesTest`, `ControlApiObjectReadMaskingTest`, `case-members.spec.ts`.
    **Echoes (closed 2026-10-18, `OBJECT-READ-MASKING-RESIDUALS-1`).** `POST /objects/{id}/attachments` and
    `POST /objects/{id}/rca` echo their notes through the same `maskedNotes` as comments; `ControlApiObjectReadMaskingTest`.
    The row is closed; its unverified edges (direct `notify()` by a Run, no write root, `maskingMode` ignored) are in BACKLOG §6.
    **Follow-ups (closed, `OBJECT-READ-MASKING-RESIDUALS-1`).** (1) `GET /objects/{id}/graph` masks each node `title` with
    THAT node's own attributes (same `ObjectReadMasking`). (2) `OBJECT_OPENED` is emitted WITHOUT the title
    (`<type> opened [<id>]`; the title is on the object, nothing consumed it); the append-only log still holds older raw rows,
    so `AuditReadMasking.forRequest` also masks them on read for a non-revealer (`maskOpened`): the object is resolved by id
    through the core `ObjectAccess.summary` seam and its title and the event correlation id are masked with its classified
    keys; fail closed, an object that cannot be resolved (purged, no ops module) loses the title and the correlation id
    (`<type> opened [<id>]`). This covers `/events`, `/events/search`, `/events/{id}` and both `/events/export` formats.
    Real-HTTP `ControlApiEventsReadMaskingTest` (module observability: the fail-closed path), unit
    `AuditReadMaskingOpenedEventTest` (resolved path). (3) For a non-revealer, `q` and `correlationId` on `GET /objects`
    (legacy and v1) and on `/events/search` / `/events/export` match the MASKED row after masking (the store query runs
    without them and the page is sliced afterwards; the v1 `total` counts the post-filtered set), so a raw number matches
    nothing; the `masked:<16 hex>` token a viewer sees does filter the rows that show it. A revealer / no-Subject request
    is unchanged. Consequence: a revealer's `q` no longer finds NEW `OBJECT_OPENED` events by title (use the object list).
    **Notification content is ALWAYS masked (2026-10-15, `NotificationContentMasking`, core `control`).** A notification is rendered once
    and read by recipients nobody can capability-check (mail, webhook, the shared in-app feed), so `NotificationService.dispatch` passes each
    Event through `contentMasking` BEFORE any rule or per-channel template renders from it (the `ReportRowMasking` precedent): the stored in-app
    text, the mail subject and body and the webhook payload are all built from the masked Event. It reuses `AuditReadMasking.mask` (classified
    AUDIT values), `maskOpened` (no object lookup, so a legacy title is dropped) and `ObjectReadMasking` for `key.<column>` / `entityKey`
    attributes and the raw value inline in `{{message}}`; an Event with `key.*` and no `dataset` attribute fails closed (every `key.*` masked);
    a masker that throws drops the notification. The hook is an injected `UnaryOperator<Event>` (the helpers live in core `inspecto`, the engine
    owns the service), so `inspecto-notify-channels` gains no dependency. ⚠ **Decision for the operator:** an operator now receives
    `masked:<16 hex>` in mail, never the number; there is NO escape hatch (a reveal is done in the UI by a holder of `canRevealLinkEntities`).
    Not covered: `notify()` called directly by a granted Run (free text from the Run), and a Space with no write root (no registry to classify).
    Tests: `NotificationContentMaskingTest`.
    **Not covered (record only):** the Regulatory report object view (needs an operator decision), outbound mail / webhook
    policy, the agent SSE stream.
    **Agent surface (`AGENT-EGRESS-MASK-1`, 2026-10-15; see `embedded-intelligence.md`):** the row-reading tools and every
    tool result are masked on the way out (a model-bound result for a hosted provider whatever the caller may reveal).
    **Still open (record only, needs an operator decision):** `GET /agent/triage-runs*`, `/agent/feedback` and
    `/agent/approvals*` carry no capability gate; and `POST /db/query` / `GET /db/table` on an `ops:*` group return raw
    rows to a `canAuthorWorkbench` holder (the BI masking covers Dataset-backed reads only).
  - **Accepted limits (operator, 2026-10-06; `ASSURE-AUDIT-CHAIN-RESIDUALS-1` (5), (7)).** (5) The lock-race fork
    window is a stated limit, not prevented: a writer with unflushed linked rows when its lock is deleted can
    collide with a second writer, and `GET /audit/verify` detects it afterwards as a `duplicate`. (7) A local
    administrator who can read the Space key can rewrite the store and re-sign every anchor - the stated limit of
    on-box evidence, accepted for now; off-box anchoring (`audit_anchor_export` to an external Sink) is built when
    a customer asks.
* **Email/SMTP channel wired to `deliver(n, target)`** (2026-07-20) — `SmtpEmailChannel`
  (`providers/inspecto-notify-channels/src/main/java/com/gamma/notify/channel/SmtpEmailChannel.java`, id `email`,
  ⚠ **relocated from `inspecto-connectors` 2026-09-07, EDG-01 cell 1** — CP-15 is not for Personal and that sidecar ships in every edition,
  already discovered via `ServiceLoader` and configured from `notify.smtp.*` system properties, the
  same idiom as `WebhookChannel`) now overrides `deliver(Notification n, String target)` to address
  the mail to the persisted `ChannelConfig`'s own `target` (comma-separated addresses supported),
  falling back to the fixed `notify.smtp.to` only when `target` is blank — so an operator-managed
  `email` destination actually reaches its own recipient instead of the single configured inbox.
  `ChannelConfig.fromMap` additionally fails closed (422) at channel-creation time when `kind=EMAIL`
  and `target` isn't a valid email address (or comma-separated list) — SMTP config/target problems
  surface at CRUD time, not silently at dispatch. Template rendering (`NotificationTemplate` via the
  shared `DottedPath` grammar) was already generic in `NotificationService.dispatch` and needed no
  channel-specific change. Tests: `SmtpEmailChannelTest` (message addressing, no live server),
  `ControlApiNotificationChannelsTest` (422 on an invalid EMAIL target). Still deferred:
  delivery-status webhooks, digest batching.
* **Conservation imbalance now notifies** (2026-07-23) — `NotificationRules.defaults()` gained a rule
  for `FLOW_CONSERVATION_IMBALANCE` (category `ops`, `minLevel=WARN` so both `LOSS`/ERROR and
  `AMPLIFICATION`/WARN reach the feed, matching that `EventAlertBridge` raises an Alert for both
  kinds). Closes the gap `docs/okf/product/layer/platform/tests-provenance-conservation.md` flagged (OPS-5 product Q).
