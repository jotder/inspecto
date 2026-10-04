# Safety Policy narrowing — design

**Status: IN FLIGHT (2026-09-28). D1–D16 answered — every recommendation accepted (§8). S0 probes recorded (§6.1); S1 built (`SafetyPolicyTier`, `HostPattern`). S2a + S2b built (2026-10-03/04); S3 paths + S4 egress built (2026-10-04). S5 built bar the IndexBuilder opt-in; S6 state and S7 explain route built (2026-10-04).**
Row: `docs/BACKLOG.md` §3.8 `DUCKLE-C6-POLICY-NARROWING-1` (P3, adopted 2026-09-15 from duckle §1 C6 —
`archived-documents/plans-archive/duckle-concepts-candidates.md` row C6). Owner concept once built:
[`okf/backend/config/config-safety.md`](../okf/backend/config/config-safety.md).

**Sibling, not overlap.** `policy-authoring-ux-design.md` (commit `98699a09f`, on branch
`worktree-agent-a853e1291906a76f9`, not yet on master) designs the *authoring* UX for **Access Policies** —
lint + editor for `access-policies.toon`, i.e. *which Subject may call which route*. This document is about
something else: **what a Space's runs may touch** — filesystem roots, network hosts, Connector schemes,
DuckDB extensions, output formats, caps, and progress-state advances — and the law by which a lower tier
may only take permissions away. Access Policies appear here only as **evidence** (§1.5): their seed merge is
the in-repo example of a lower tier *widening*, and their unreadable-doc posture is the precedent §5 reuses.
Nothing here changes Access Policy semantics or their editor.

---

## 1. As-is — what exists, grounded

### 1.1 The one policy object is process-wide, and it has no Space tier

`SafetyPolicy` (`inspecto-config/.../config/safety/SafetyPolicy.java`) is a record of `allowedRoots`,
`maxThreads`, `maxBatchFiles`, `maxBatchBytes`, `allowedFormats`, `allowedCompression` (`:34-40`).
`defaultPolicy()` `:83-91` builds it from `-Dassist.safety.roots` **plus every hosted Space base** in
`DiscoveredRoots.snapshot()` (`DiscoveredRoots.java:43-58`) — a **union** — recomputed per call.

🔴 **Consequence, structurally present, not yet measured:** every Space's config is checked against the
roots of **every** hosted Space. PathJail resolves a *relative* value against the Space
(`PathJail.resolveAgainst` `:287-308`), but an *absolute* path into a sibling Space's base passes
`requireUnderAny` (`:130-142`) because that base is an allowed root. That is a cross-Space widening built
into the default. Probe **P0-c** (§6) measures it before anything depends on the claim.

Every gate asks the same process-wide object: all 15 production callers of `ConfigSafetyValidator.check`
pass `SafetyPolicy.defaultPolicy()` — `SaveGate.java:125,127`, `RunRoutes.java:570`,
`DataSourceRoutes.java:154,208`, `JobRoutes.java:393`, `BundleRoutes.java:511`, `ComponentRoutes.java:662`,
`EnrichmentRoutes.java:102`, `PipelineRenameRoutes.java:414,417`, `PipelineSettingsRoutes.java:109,112,183,186,261`.

### 1.2 Plan-time vs act-time gates that exist today

| When | Where | What it checks |
|---|---|---|
| save | `SaveGate.java:125-127` → `ConfigSafetyValidator.check/checkJob` | paths under roots, caps, formats, DuckLake keys (`ConfigSafetyValidator.java:59-72`, `:563-600`) |
| register | `RunRoutes.java:570` | same, because a file may be placed on disk without `POST /config/write` (`:560-561`) |
| load | `PipelineConfigParser.java:1268` | `PathJail.requireUnderAny(allowedRoots(), …)` |
| act (paths) | `PipelineJobRunner.java:268,293`, `CleanupTask.java:36,47`, `BackupTask.java:95-96,189,198,276-277`, `PartitionCompactor.java:63`, `ReferenceCompactor.java:96`, `ReportJob.java:131`, `PartitionPruneTask.java:40`, `StorageReportTask.java:45`, `EnrichJob.java:65`, `Asn1GrammarSource.java:76` | `PathJail.requireJobPathUnderAny` / `requireUnderAny` |
| act (network) | **none** — see §4.1 | — |
| act (state) | **none** — see §4.2 | — |

So the row's "at plan time **and** at the point of the act" already holds for **paths**, and holds for
nothing else.

### 1.3 Prefix matching — where it is a boundary and where it was a string

- **Filesystem containment is component-wise.** `PathJail.contains` `:352-369` uses `Path.startsWith`, which
  compares name elements, so `/data/space1` does **not** contain `/data/space10`; it then re-checks real
  paths for a symlink escape (`:361-368`). `require` refuses UNC `:321-322` and any URI `:325-328`. `DataRef.requireUnder`
  `:83-91` reuses that verdict. Every other `Path.startsWith` containment site in the backend sampled
  (`DbBrowserRoutes.java:191,202`, `BundleImporter.java:86`, `RemoteAcquisitionHandler.java:252`,
  `MetadataValidateTask.java:98`, `InvestigationRoutes.java:1292-1297`) is `Path`-typed, so boundary-correct.
- **Object-store prefixes are string-matched, and are correct only by normalisation.** `S3Connector.java:78`,
  `GcsConnector.java:74`, `AzureBlobConnector.java:75` force a trailing `/` onto the configured prefix; the
  later `key.startsWith(prefix)` (`S3Connector.java:125`, `GcsConnector.java:126`, `AzureBlobConnector.java:125`)
  is then a boundary match. Remove that one line and it is not.
- **The §5.4 defect was a string prefix used as a spelling check.** `LakehouseCatalog.isShared`
  (`inspecto-util/.../LakehouseCatalog.java:109-113`) first accepted `postgres://…` because it starts with
  `postgres:`; now `lower.startsWith(b) && !lower.startsWith(b + "//")`. Recorded in commit `7486cb3ed`
  (*"A prefix match is not a spelling check"*). That is the evidence the row cites: every prefix this design
  introduces (paths, object-store prefixes, host suffixes) goes through **one** boundary matcher (§3.3).

### 1.4 Settings today *override*; an unreadable settings file is silently empty

The per-Space settings documents layer by **replacement** and fail **open**: `SchedulerSettings`
(`control/SchedulerSettings.java:20-21`, space tier `:38-41`) and `LinkAnalysisSettings`
(`control/LinkAnalysisSettings.java:33-35` Javadoc) both read a missing **or unreadable** file as `EMPTY` —
"settings never fail a boot". That posture is right for a cadence and exactly wrong for a narrowing policy:
an unreadable narrowing file read as empty is a **silent widening**. §5 inverts it for this document only.

### 1.5 Access Policies — the two pieces of evidence

- **A lower tier widens by name.** `PolicyEngine.effective` (`inspecto-policy/.../PolicyEngine.java:142-148`)
  overlays authored policies on the seeds **by name**, so an authored `space-isolation` replaces the built-in
  tenancy deny wholesale (sibling's failure mode F6). Under this document's law that merge is illegal; this
  document does **not** change it — it is the sibling's call.
- **Unreadable denies — but only in Enterprise.** `AccessPolicies.load` `:97-116` marks a doc unreadable
  on a stat failure, `parseFile` `:118-127` on any parse/validate failure, and `PolicyEngine.decide` `:76-81`
  DENIES on it. The enforcement lives in `inspecto-policy`, so on Personal/Professional "a stored policy has
  no runtime effect" (`AccessPolicies.java:33-36`). The refusal §5 needs must be **core**, not edition-gated
  (D11).

---

## 2. What the policy is

A **Safety Policy** (name owed — D1; `SafetyPolicy` already exists and is the natural home) is one TOON
document per tier:

| Tier | File | Who writes it |
|---|---|---|
| **Server** | `safety-policy.toon` in the server config directory (D3) | the operator, on disk only |
| **Space** | `<space>/config/safety-policy.toon` | a Space administrator (through a capability-gated settings route) |
| *(Pipeline — deferred, D2)* | a `safety:` block | — |

```toon
mode: enforce
permit:
  network: true
  install_extensions: false
  advance_state: true
allow:
  roots[2]: /srv/inspecto/spaces/acme,/mnt/exports
  hosts[2]: sftp.acme.example,*.blob.core.windows.net
  connectors[3]: sftp,s3,https
  extensions[2]: ducklake,postgres_scanner
  formats[1]: PARQUET
deny:
  hosts[1]: 169.254.169.254
  roots[1]: /srv/inspecto/spaces/acme/secrets
caps:
  max_threads: 8
```

Illustrative only (no inline `#` comments — a TOON trap). `mode` is legal in the server file only (D5);
`permit.*` fold by AND, `allow.*` by intersection, `deny.*` by union, `caps.*` by min (§3.1).
Unknown keys are **refused** (§5), unlike `AccessPolicies.validate`, which ignores unknown keys (sibling F8).

---

## 3. Merge semantics — precise

### 3.1 The fold

For tiers `T₁ = server, T₂ = Space` the effective policy `E` is:

| Field kind | Absent in a tier | Present in a tier | Fold |
|---|---|---|---|
| `permit.*` (boolean) | `true` (does not constrain) | its value | **AND** |
| `allow.*` (set) | ⊤ — "anything" | that set — `[]` means **nothing** | **INTERSECT** (§3.3 for prefixes) |
| `deny.*` (set) | ∅ | that set | **UNION** |
| `caps.*` (number) | +∞ | its value | **MIN** |
| `mode` | server: `enforce`; Space: must be absent | server value | **server only** |

⚠ **Absent ≠ empty.** `allow.hosts` absent means "this tier does not narrow hosts"; `allow.hosts: []`
means "no host at all". The TOON codec must preserve that distinction end-to-end — the same
absent-vs-stated rule `SchedulerSettings.write` already keeps (`:43-46`).

**An act is permitted iff** every relevant `permit` is `true` in `E` **and** it matches `E.allow` for every
constrained dimension **and** it matches nothing in `E.deny`. Deny beats allow inside a tier and across
tiers.

**There is no name, no overlay, no replace.** A lower tier cannot address an upper tier's entry — it can
only add denies, add allow-sets to intersect with, set permits to `false`, or lower caps. That is the
structural difference from `PolicyEngine.effective` (§1.5), and the reason monotonicity (§3.4) is provable.

### 3.2 Worked examples

| # | Server | Space | Effective | Why |
|---|---|---|---|---|
| E1 | `allow.hosts [a.ex]` | `allow.hosts [a.ex, b.ex]` | `[a.ex]` | a Space cannot add `b.ex` |
| E2 | *(no `allow.hosts`)* | `allow.hosts [a.ex]` | `[a.ex]` | ⊤ ∩ X = X — a Space narrows freely |
| E3 | `allow.hosts [a.ex]` | `allow.hosts []` | `[]` | the Space opted out of the network by allowlist |
| E4 | `deny.hosts [x]` | `deny.hosts [y]` | `[x, y]` | union |
| E5 | `permit.network true` | `permit.network false` | `false` | AND |
| E6 | `permit.install_extensions false` | `permit.install_extensions true` | `false` | a Space cannot re-grant |
| E7 | `allow.roots [/data]` | `allow.roots [/data/s1, /other]` | `[/data/s1]` | `/other` is outside `/data`; `/data/s1` is inside |
| E8 | `allow.roots [/data/s1]` | `allow.roots [/data]` | `[/data/s1]` | the narrower of a nested pair wins, whichever tier wrote it |
| E9 | `allow.roots [/data/s1]` | `allow.roots [/data/s10]` | `[]` | `/data/s1` and `/data/s10` are **disjoint** — not a prefix |
| E10 | `allow.hosts [*.ex.com]` | `allow.hosts [api.ex.com, evil.net]` | `[api.ex.com]` | suffix at a label boundary |
| E11 | `caps.max_threads 8` | `caps.max_threads 32` | `8` | min |
| E12 | `mode audit` | `mode enforce` | **refused** | `mode` in a Space file makes the file unreadable (§5) |

### 3.3 Intersecting prefix sets — one boundary matcher

Allow-sets of **prefixes** (roots, object-store prefixes, host suffixes) do not intersect as strings. For
prefix sets `A`, `B`:

```
A ⊓ B = { narrower(a, b) | a ∈ A, b ∈ B, a covers b or b covers a }
```

where `covers` is **one** function per domain, each a boundary match:

| Domain | `a covers b` iff | Refuses |
|---|---|---|
| filesystem root | `PathJail.contains(a, b)` — component-wise + real-path (`:352-369`) | `/data/s1` vs `/data/s10` |
| object-store prefix | same bucket, and `b` equals `a` or starts with `a` **normalised to end in `/`** | `bucket/in` vs `bucket/inbox` |
| host | `a == b`, or `a = *.d` and `b` ends with `.d` (a **dot** boundary) | `*.ex.com` vs `evilex.com`, vs `ex.com.evil.net` |

The object-store matcher lifts the trailing-`/` normalisation now repeated in three connectors
(§1.3) into the shared matcher, so that rule has one definition. Hosts compare **case-folded, IDNA-normalised
ASCII**, never raw strings.

### 3.4 The invariant

> **Monotonicity:** for every Space document `W`, `permitted(E(S, W), act) ⇒ permitted(E(S), act)`.

It is a property test (T13), not prose: generate random tier pairs and acts, and assert the implication.

---

## 4. Enforcement-point inventory

Each point gets a **plan-time** check (at save/register/load — the §1.2 seams, switched from
`defaultPolicy()` to the Space's effective policy) **and** an **act-time** check at the line below. The act
check exists because the plan is bypassable: a file placed on disk, a scheduler cycle, a CLI run.

### 4.1 Network — every hop, plus DuckDB itself

A **hop** is every distinct `(host, port)` the process opens a socket to for a run: bastion, proxy, target,
every redirect target, token endpoint, catalog backend, and every broker a client learns from metadata.

| # | Egress | Act site | Hops beyond the target | Guard today |
|---|---|---|---|---|
| N1 | Collector connector build (single seam) | `CollectorConnectors.forConfig` `inspecto-acquire/.../CollectorConnectors.java:37-50` | — | scheme lookup only |
| N2 | SFTP | `SftpConnector.java:265` (`ssh.connect`) | bastion `SshTunnel.java:72`; proxy `SocksProxySocketFactory.java:36-42`, `HttpProxySocketFactory.java:63-101` | host-key policy only (`HostKeyPolicy.java:68`) |
| N3 | FTP/FTPS | `FtpConnector.java:274` | same proxies | none |
| N4 | S3 / GCS / Azure over HTTP | `AbstractHttpObjectStoreConnector.java:66,84` | 🔴 **`Redirect.NORMAL` at `:51`** — every redirect is followed unchecked; GCS token endpoint `GcpServiceAccountToken.java:95` | none |
| N5 | Kafka | `KafkaConnectorFactory.java:32` (`KafkaConsumer::new`) | **every broker** named by cluster metadata — the client dials them itself | none |
| N6 | DB export (JDBC) | `DbConnections.java:70` | `options.jdbc_url` honoured **verbatim** (`:45`) — the host is inside a URL string | none |
| N7 | Connection probe | `ConnectionTester.java:68` via `ConnectionRoutes.java:81,98,103` | the probe's own tunnel hop | route capability only |
| N8 | Operational DB test | `OperationalDbReport.java:81` via `SystemRoutes.java:60` | — | **URL comes from the request body** |
| N9 | Webhook sink | `WebhookSink` (Connection-only, `https` only, proxy/tunnel refused — `WebhookSink.java:47-54`) → `HttpWebhookSinkTransport.java:23-26` | `Redirect.NEVER` ✅ | Connection onboarding capability |
| N10 | Notification channels | `WebhookChannel.java:95`, `SmtpEmailChannel.java:123-139` | JDK default (no redirects) | server `-D` properties only |
| N11 | OIDC | `OidcAuthenticator.java:182-186` (`RemoteJWKSet`), `OidcTokenRelay.java:98` | — | server `-D` properties |
| N12 | Intelligence → control plane | `ControlPlaneClient.java:42,70` | — | — |
| **N13** | **DuckDB: extension INSTALL** | `DuckDbExtension.ensureLoaded` `inspecto-etl/.../DuckDbExtension.java:53-77` (`INSTALL` at `:77`) | extension repository host | cached LOAD → staged file → network ladder |
| **N14** | **DuckDB: DuckLake catalog** | `DuckLakeRegistrar.java:170-186` (`ATTACH 'ducklake:<catalog_url>'`) | the catalog host inside `catalog_url`; autoloaded scanner (`:172-181`) | `LakehouseCatalog.requireShared` (spelling, not host) |
| **N15** | **DuckDB: SQL reaching a URL** | sandboxed paths set `autoinstall/autoload_known_extensions=false` (`SqlSandbox.java:77-78`) and seal with `enable_external_access=false` + `lock_configuration` (`:93-99`) — used by `ComponentPreview.java:97`, `SandboxConsignmentReader.java:67`, `ExpectationEvaluator.java:49`, `ViewQuery.java:58`, `SqlAst.java:73` | — | **unsandboxed engine connections set neither**: `DuckDbUtil.applyDuckDbSettings` `:108-119` sets only temp/memory; `SqlTemplateJob.java:90` opens bare `jdbc:duckdb:` and runs authored SQL (`:79`, `:96`) with **no `SqlGuard` call in the file** |

⚠ **N15 is an observation, not a measured egress.** Whether `SELECT * FROM read_csv('https://…')` in a
`sql_template` job autoloads `httpfs` and reaches the network on DuckDB `1.5.2.1` (`pom.xml:235`) is
exactly probe **P0-a**. If it does, it is a live defect independent of this design and gets its own row.

**Proposed act-time mechanism.** There is no JVM-wide socket hook to lean on (no SecurityManager on the
JDK 27 toolchain), so:

1. **`EgressGate.require(host, port, purpose)`** — one function in `inspecto-config/.../safety/` beside
   `PathJail`, reading the run's pinned effective policy (§5.3). Called at N1–N8, N13–N14, and at **each hop**
   (bastion, proxy, redirect, token endpoint) *before* the socket opens.
2. **Redirects are never followed by the client.** N4 moves to `Redirect.NEVER` and follows a `3xx` itself
   after `EgressGate.require` on the `Location` host — the same posture N9 already has.
3. **Kafka (N5):** check bootstrap servers at build, and every partition leader's host
   (`PartitionInfo.leader().host()`) before the first fetch of a cycle (D12).
4. **DuckDB itself (N13–N15):** every engine connection — not only sandboxed ones — gets, in
   `DuckDbUtil.applyDuckDbSettings`: `autoinstall_known_extensions=false`, `autoload_known_extensions=false`,
   the network filesystems disabled when `permit.network` is false, and `allowed_directories` =
   `E.allow.roots`, then `lock_configuration=true`. The setting names and their behaviour on `1.5.2.1` are
   **probe P0-b** — measure before building; ⛔ do not ship a setting whose effect was not observed.
   `DuckDbExtension.ensureLoaded` consults `permit.install_extensions` and `allow.extensions` before its
   `INSTALL` step, and a DuckLake `catalog_url`'s host goes through `EgressGate` before the `ATTACH`.

N10–N11 are **server-configured, not Space runs** — out of the Space tier by construction (D15). N12 is
loopback to the control plane and exempt. N8 stays in scope under the **server** tier (D16).

### 4.2 State mutation — every watermark/offset advance

| # | Advance | Act site | Storage |
|---|---|---|---|
| M1 | processed markers | `ConsignmentIngestor.finalizeSource` `:378` → `MarkerManager.createMarkerFile` `:581-587` | marker files |
| M2 | fingerprint ledger | same method, `ledger.record(e)` `:590-594` (`AcquisitionLedger.record` `AcquisitionLedger.java:26`) | acquisition ledger DB |
| M3 | DB-export row watermark **and Kafka offsets** | same method, `recordDbWatermark` `:600-612`; Kafka's offset rides it (`KafkaConnector.java:45-48`, `enable.auto.commit=false` `:329`) | acquisition ledger DB |
| M4 | remote slice frontier (durable, pre-land) | `RemoteAcquisitionHandler.land` `:271-288`, `SliceFrontiers.write` `:279` (`SliceFrontiers.java:70-82`) | `<staging>/.frontier/*.json` |
| M5 | incremental job high-water | `PipelineJobRunner.advanceWatermarks` `:735-741` (called `:393`) → `PipelineWatermarkStore.put` `:51-58` | `<audit>/<pipeline>__<store>.watermark` |
| M6 | record-dedup claims | `RowShaper` `:409` → `DbDedupLedger.claim` `:133` | dedup ledger DB |
| M7 | run ledger (feeds `$job.last_success_time`, `StreamWatermark.java:36-37`) | `JobService.record` `:1388-1391` | run ledger |
| M8 | *rewind* — retract, prune | `ReprocessCommand.java:75-77` (`retract`), `AcquisitionLedger.prune` `:72`, `DedupPruneTask.java:27` | same stores |

Not advances, and deliberately **not** gated: the in-memory stashes `KafkaConnector.java:235`,
`DbExportConnector.java:159`, `RemoteAcquisitionHandler.java:288`. The advance is M3/M4; gating the stash
would refuse a fetch whose result is thrown away anyway.

**No M-site consults any policy today.** `PipelineDryRun` commits nothing by construction — a throwaway
DuckDB, `PipelineDryRun.java:25-30` — not by a check.

**Proposed.** `permit.advance_state = false` means *this Space's runs may not move progress state*.
- **Plan time:** a pipeline whose config implies an advance (remote/DB-export/Kafka Collector, content dedup,
  `incremental_column`, windowed `transform.dedup`) is refused at save/register with a finding on the key that
  implies it (D9).
- **Act time:** one `StateGate.requireAdvance(what)` before M1–M7, reading the pinned policy. ⚠ **Ordering
  matters:** M1–M3 run *after* outputs are durable. Refusing there leaves outputs without their ledger row, so
  the next run re-ingests. The act-time check is therefore **also** made once at batch start, before any
  output is written; the M-site checks are the backstop that turns a planner bypass into a loud failure.
- M8 (rewind) is a separate permit, `permit.rewind_state` (D10).

---

## 5. Unreadable policy refuses the run

### 5.1 States

| File state | Server tier | Space tier |
|---|---|---|
| absent, not named | defaults (enforce, no narrowing beyond today's roots) | no narrowing from this tier |
| absent, **named** by the server file (D7) | — | **unreadable** |
| parses, validates | applied | applied |
| TOON damage · stat/IO failure · unknown key · bad value · `mode` present in a Space file | **unreadable** | **unreadable** |

**Unreadable ⇒** every plan-time gate returns a 422 finding naming the file, and every run in scope (all
Spaces for the server file, that Space for a Space file) is **refused before it starts** with a stable error
code (`ERR_SAFETY_POLICY_UNREADABLE`), recorded as a failed Run with the reason — never skipped, never
"no policy". The control plane stays up and `/health` reports the degradation (D6).

### 5.2 Why each row

- **Stat failure is unreadable**, as `AccessPolicies.load` `:112-115` already treats it — a permission error
  must not read as "absent".
- **Unknown keys are unreadable**, because a mistyped `alow:` in a narrowing file silently removes the
  narrowing. This is the sibling's F8 hazard, closed here by construction.
- **Deleting a named file cannot widen**: naming (D7) is what makes absence detectable. An *un-named*
  Space file that is deleted does widen back to the server policy — that is stated, not hidden.

### 5.3 Snapshot

The effective policy is computed **once per run at plan time and pinned** into the run context; every
act-time gate reads the pin. Re-reads use the mtime/size cache shape of `AccessPolicies.load` `:100-111`. A
file tightened mid-run applies to the **next** run (D8).

---

## 6. Slices

| Slice | Content | Done when |
|---|---|---|
| **S0** probes | **P0-a** does `sql_template` SQL reach `https://` (N15)? **P0-b** measure the DuckDB `1.5.2.1` settings in §4.1 step 4 one by one against a local stub. **P0-c** does an absolute path into a sibling Space pass today's gate (§1.1)? | each answered with a recorded observation; P0-a / P0-c file their own rows if positive |
| **S1** model | `SafetyPolicy` gains tiers + `permit/allow/deny/caps/mode`; the fold (§3.1); the three boundary matchers (§3.3); pure, in `inspecto-config` | T1–T4, T13 green |
| **S2** loading + refusal | server + Space files, strict parse, unreadable states (§5.1), pinned snapshot; the 15 `defaultPolicy()` callers (§1.1) take the Space's effective policy | T5, T6, T14 green |
| **S3** paths ✅ | `allow.roots`/`deny.roots` feed `PathJail`'s root list at every §1.2 act site; default Space roots per D4 | T4 (paths), P0-c twin |
| **S4** egress | `EgressGate`; N1–N8 + each hop; N4 redirect rewrite; N5 leader check | T7, T8, T12 |
| **S5** DuckDB | engine-connection hardening; `DuckDbExtension` + DuckLake host through the gate | T9, T10 |
| **S6** state | `StateGate`; batch-start check + M1–M8 | T11, T12 |
| **S7** explain | `GET` effective Safety Policy per Space, each field carrying **which tier constrained it** | route test; the UI is a later design, not the sibling's |

S1 → S2 are the spine; S3–S6 are independent once S2 lands.

### 6.1 As built

**S0 — probes (2026-09-28, DuckDB `1.5.2.1`, JDK 27, a local `HttpServer` stub counting requests).**

- **P0-a — positive.** A bare `jdbc:duckdb:` connection (the `SqlTemplateJob` shape) ran
  `SELECT count(*) FROM read_csv('http://127.0.0.1:<port>/x.csv')`, returned the row, and the stub counted
  **2** requests (HEAD + GET): `httpfs` autoloaded from the local extension cache. Filed as its own row,
  `SQL-TEMPLATE-EGRESS-1` (BACKLOG §3.8) — ✅ closed by S5 below: the probe measured the bare-connection
  *shape*, not the job, which was already sealed.
- **P0-b — observed, one setting at a time, fresh in-memory database each:** `autoinstall_known_extensions=false`
  + `autoload_known_extensions=false` → the query fails, **0** requests; `enable_external_access=false` → fails,
  0; `disabled_filesystems='HTTPFileSystem'` → fails, 0; `allowed_directories=[…]` with external access off →
  fails, 0; after `lock_configuration=true`, `SET enable_external_access=true` is refused ("the configuration
  has been locked"). ⚠ The failure surfaces through the JDBC driver as a generic *"unsuccessful or closed
  pending query result"*, not DuckDB's own message — S5's tests must assert the stub count, not the text.
  `allowed_directories` alone (external access on) was **not** measured; S5 measures it before relying on it.
- **P0-c — already closed.** `CROSS-SPACE-JAIL-1` (2026-09-24) made `SafetyPolicy.defaultPolicy()` →
  `forSpace(CurrentSpace.id())`: the operator's `-Dassist.safety.roots` plus **that** Space's base only,
  pinned by `ControlApiCrossSpaceJailTest`. That is D4's default, so D4's precondition is met; §1.1's union
  describes the pre-fix code.

**S2a — loading + refusal (2026-10-03).** `SafetyPolicyFiles.effective(serverDir, spaceBase, id, defaultRoots)` loads both files strictly (unknown key, bad value, relative root, `mode`/`require_spaces` in a Space file, stat/IO failure, TOON damage → `SafetyPolicyUnreadableException`, `ERR_SAFETY_POLICY_UNREADABLE`), D7 via server-file `require_spaces`, (mtime,size) cache, and `SafetyPolicy.forSpace` applies the fold to roots/caps/formats (server dir = `-Dsystem.config.dir`). Tests `SafetyPolicyFilesTest` (13; the unknown-key guard mutation-checked). Owed for S2: 422 mapping at the gates, failed-Run refusal, per-run pin, `/health`; `deny.roots` waits for S3.

**S2b — refusal surfaces (2026-10-04).** `ControlApi.errorBoundary` maps the exception to a 422 + `errorCode ERR_SAFETY_POLICY_UNREADABLE` + an ERROR finding naming the file, covering every route-borne `ConfigSafetyValidator.check` caller (the agent-tool callers catch it themselves). `SafetyPolicy.pinnedForRun` resolves the effective policy once before a run claims anything, refuses when unreadable and pins the snapshot in a `ScopedValue` (D8); wired at `CollectorService.runPipeline`, `PipelineScheduler.runOne`, `JobService.runJob` (a `FAILED` Run with the reason). `GET /health` reports `DEGRADED` + the files (D6) and keeps serving. T5/T6 at run level + the gate over HTTP: `SafetyPolicyUnreadableRoutesTest`, `JobServiceTest`, `SafetyPolicyFilesTest`. Found on the way: `PathJail.defaultRoots()` also calls `defaultPolicy()`, so an unreadable file stops a pipeline from *loading* (S3 will own that consumer). T14 (act-time host gate on a pipeline placed without the save gate) needs S4's `EgressGate` and stays with S4.

**S3 - paths (2026-10-04).** `SafetyPolicy` gained `denyRoots` (the folded tier's `deny.roots`); `PathJail.requireUnderAny(roots, deny, value, field)` refuses a contained path under a deny root, and the 3-arg form reads the deny set from `PathJail.deniedRoots()` (the calling thread's `defaultPolicy()`, so it follows the S2b pin), covering every 1.2 act site without touching them; `ConfigSafetyValidator` mirrors it as a finding. `allow.roots` needed no change (S2a already narrowed `allowedRoots`). **Owned S2b's finding:** `PathJail.allowedRoots()` threw at *load* (`PipelineConfigParser.resolveSchemaRef`), so an unreadable file dropped the Pipeline from the registry. Decision: loading is not running - the load-time jail falls back to `SafetyPolicy.baseRoots(space)` (no policy file read; never wider than before narrowing) and the Pipeline stays registered; the RUN is the visible fail-closed refusal (failed Run, 422 at every gate, `/health` DEGRADED). **Pin hand-over:** `SafetyPolicy.pinned()` + `runWithPinned(policy, run)`; `MultiCollectorProcessor.runAll`/`runConfigs` (virtual-thread fan-out, where `PipelineConfig.load` and the ingest run) rebind the snapshot on each worker. P0-c twin and the deny/allow tests: `SafetyPolicyPathsTest` (a worker handed the pin keeps the run's deny after the file is loosened; the un-handed twin sees the loosened file). Not changed: the three object-store connectors' own trailing-`/` line (S4).
**S6 — state (2026-10-04).** `StateGate.requireAdvance/requireRewind` (`config/safety/`) over the pinned tier; wired at batch start (`ConsignmentIngestor.process`), M1-M3 (`finalizeSource`), M4 (`RemoteAcquisitionHandler.land`), M5 (`PipelineJobRunner`, before the sink writer and in `advanceWatermarks`), M8 (`ReprocessCommand`, `DedupPruneTask`). `StateGateTest` covers the verdicts; T11's full runner-level check and T12's Kafka half are not built. **Deferred:** plan-time refusal at save/register (D9), M6 (dedup claims in `RowShaper`) and M7 (run ledger - a refused run must still be recorded as failed, so it is deliberately ungated), `AcquisitionLedger.prune`. Detail: `okf/backend/config/config-safety.md`.

**S4 — egress (2026-10-04).** `EgressGate` (`config/safety/`): `require(host, port, purpose)` over the run's pinned tier (`SafetyPolicy.effectiveTier()`), `of(tier)` for an explicit hand-off, `server()` for D16; `requireJdbcUrl` / `requireHostList` for URLs and broker lists. Wired at N1 (`CollectorConnectors.forConfig` gates the whole profile: target, bastion, proxy, `jdbc_url`, `bootstrap_servers` — N2/N3/N6 ride it), N4 (`AbstractHttpObjectStoreConnector.send`, token endpoint included), N5 (partition leaders, D12), N7 (`ConnectionTester`), N8 (`/system/operational-db/test`, server tier). T14, T8, T12 (leader half) and T7's gate half green in `SafetyPolicyEgressTest`. ⚠ §4.1's `Redirect.NORMAL` finding is stale: `PinnedObjectStoreHttp` (2026-09-27) never follows a 3xx, so the "followed after the gate" half of T7 is superseded — the `Location` host is never dialled. T12's `advance_state` half waits for S6. Detail: `okf/backend/config/config-safety.md`.

**S1 — model** (`inspecto-config/.../config/safety/`). `SafetyPolicyTier` is one tier (every field `null` =
absent) and, after `fold(server, space)`, the effective policy: permits AND, allow-sets intersect through a
covers-matcher (`PathJail::contains` for roots, `HostPattern::covers` for hosts), denies union, caps MIN,
`mode` server-only (a Space tier stating it throws). `HostPattern` is the D13 grammar — exact / `*.suffix` /
CIDR — IDNA + case-folded, parsed with `InetAddress.ofLiteral` so it never resolves a name;
`deniesAddress(InetAddress)` is the connect-time resolved-address check S4 calls. `objectPrefixCovers` is
the object-store matcher; the three connectors still carry their own trailing-`/` line — lifting them onto
it is S4. The existing `SafetyPolicy` record is **not yet** changed: S2 attaches the pinned effective tier to
it and folds its caps (D14: the validator's caps are the ceiling, `capThreads(base)` etc.).
Tests: `SafetyPolicyTierTest` — E1–E12, T1–T4 at matcher level, T13 over 2000 random tier pairs
(mutation-checked: a space-wins overlay for hosts, caps and `install_extensions` turns 5 of 13 red, T13
included).

**S5 — DuckDB, first half (2026-09-28): `SQL-TEMPLATE-EGRESS-1` closed, no production change.** The row's
premise did not hold for the job itself: since `SQL-TEMPLATE-SANDBOX-1` (2026-09-24) `SqlTemplateJob` opens
through `DuckDbUtil.openInMemory`, then `SqlSandbox.disableExtensionAutoload` (autoinstall + autoload off)
before any view is registered, and `SqlSandbox.sealAllowing` (`allowed_directories` = the data root,
`enable_external_access=false`, `lock_configuration=true`) before the authored SQL runs — the P0-b settings
already. P0-a probed a bare `jdbc:duckdb:` connection, the shape the job had *before* that fix. The existing
`anHttpReadNeverReachesTheNetwork` could not show this: `SqlGuard` refuses `read_csv` first, so it stays green
with the connection hardening deleted. Pinned now by
`SqlTemplateJobSandboxTest.anHttpReadTheGuardMissesNeverReachesTheNetwork`: the guard swapped for a
pass-everything one, a loopback `HttpServer` stub, and a twin on a bare connection that MUST reach the stub
(count > 0) before the job run must leave it at **0**. Mutation-checked: deleting the two `SqlSandbox` calls
turns it red with *expected 0 but was 2* and a SUCCESS run (and `aFileLiteralTheGuardMissesIsStoppedByTheConnectionSeal`
with it). Open for S5: every other `openInMemory` caller is unsealed — filed as `ENGINE-INMEMORY-UNSEALED-1`
(BACKLOG §3.8), with the harden-by-default remedy and the `DuckDbExtension` / DuckLake host gate; T9, T10
still owed. `allowed_directories` alone (external access on) is still unmeasured.

**S5 — DuckDB, second half (2026-10-03): `openInMemory` sealed by default (`ENGINE-INMEMORY-UNSEALED-1`, narrowed).**
`DuckDbUtil.openInMemory(spill[, dirs[, beforeSeal]])` now turns autoinstall + autoload off, runs the caller's
trusted `beforeSeal` (the two excel writers `LOAD` the bundled extension there), sets `allowed_directories` to the
declared dirs and `enable_external_access=false`. It does **not** lock the configuration — measured on DuckDB
1.5.6: once external access is off, `allowed_directories` and `enable_external_access` refuse to change and `LOAD`
is refused even with autoload switched back on, while `threads` / `TimeZone` / `memory_limit` stay settable (the
LA builder and the event store set them after open). Callers running authored text add
`DuckDbUtil.lockConfiguration` (`sql.template`, `ReportXlsx`, `SqlGuard`'s parser). Ground (2026-10-03, 20 callers):
authored text reaches `MaterializeTask`, `PostgresPublishJobType`, `ExchangeSnapshotWriter` (Dataset relations —
a hand-authored view passes through unguarded), `SqlTemplateJob`, `IndexBuilder` (LA relation), `TypeFlow`
(authored cast/expression DDL, no files); the rest run engine-built SQL. **The probe was positive**: a view
reading `http://` made the materialize connection hit a loopback stub 4 times; sealed, 0 and the run fails
(`MaterializeTaskTest.anAuthoredViewThatReadsAUrlNeverReachesTheNetwork`, mutation-checked by skipping the seal).
Relation readers declare `DatasetRelation.readRoots` (data root + a `shared/` ref's Exchange snapshot). No caller
needed `httpfs`, so no object-store opt-in exists. `IndexBuilder` first held a file-access opt-in; it now declares
`DatasetProvider.readRoots` + the store directory instead (`ENGINE-INMEMORY-UNSEALED-1` closed) and the opt-in was deleted.
⚠ **Measured gotcha:** the `temp_directory` subtree is implicitly readable AND writable under the seal — the
`null` spill default (all of `java.io.tmpdir`) let a sealed connection write any temp sibling, so it moved to
`<tmpdir>/.duckdb_tmp`. Still owed: T9, T10 and the `DuckDbExtension` / DuckLake host gate.

**S5 - DuckDB, third half (2026-10-04): extension + DuckLake gates; T9/T10.** `DuckDbExtension.load` reads the calling thread's `SafetyPolicy.effectiveTier()`: a name outside `allow.extensions` is refused for every mode, and the unstaged `INSTALL` fallback (the only network step) is refused when `permit.install_extensions` is false - a cached `LOAD` and the staged-file `LOAD` still work (T10, recording-proxy test: no `INSTALL` issued). `DuckLakeRegistrar.registerInto` calls `gateEgress` BEFORE its catch-all, so a refusal is never downgraded to the single-topology warning: a shared catalog's `host=`/`hostaddr=` (or the libpq default `localhost`) and a remote `scheme://authority` data path go through `EgressGate` (a file catalog / local path dials nothing). T9 needs no new code: every `sql_template` connection is sealed unconditionally (the first half above), so an `http://` read never reaches the network whatever `permit.network` says - there is no "permit.network true -> reads rows" twin by design. Tests: `DuckDbExtensionTest` (+4), `DuckLakeEgressGateTest` (3). **Deferred:** the `IndexBuilder` file-access opt-in (see BACKLOG `ENGINE-INMEMORY-UNSEALED-1`); `allowed_directories` alone (external access on) still unmeasured and unrelied on.

**S7 - explain (2026-10-04).** `GET /settings/safety-policy` (`SafetyPolicyRoutes`, per Space via `/spaces/{id}/...`), gated `canAdminister` (the answer names roots and host patterns) and listed in `CapabilityManifest`. `SafetyPolicy.tiersForSpace` returns the server tier as stated, the Space tier as stated and the fold (`SafetyPolicyFiles.Tiers`, which `effective` now delegates to - one loader, one refusal path); `SafetyPolicyExplain` renders one row per field: `effective` plus `constrainedBy` in `server | space | default`. Constrained means: a `permit.*` only by a tier stating `false`; an allow/deny set by any tier stating it; a cap by the tier(s) whose stated value is the effective MIN; `default` when no file states it (operator roots + Space base, validator caps). `null` effective = unrestricted. The response also lists the consulted files (path, present) and the D15 `exempt` surfaces (N10-N12). **Decision:** an unreadable file answers **200 `readable:false` + `problem`**, no effective policy - the diagnostic exists to name the broken file, so it does not 422 like the gates (which still refuse). Read-only, so no write-root gate and no caller path to jail. Test: `SafetyPolicyExplainRoutesTest` (real HTTP, Subject-armed: no Subject refused, author 403, admin 200). The UI is a later design.

---

## 7. Test plan

Every negative test sits beside a **twin** in the same class: identical setup minus the one narrowing, which
must **succeed**. A refusal that its twin cannot distinguish from "the stub was down" proves nothing. Each
gate is also **mutation-checked**: revert the gate line, and the negative must go red.

| # | Negative (must be refused) | Twin (must succeed) | Observable proving the act did not happen |
|---|---|---|---|
| T1 | server `allow.hosts [127.0.0.1]`, Space adds `localhost` → dial `localhost` refused | server allows `localhost` too | stub server request count = 0 |
| T2 | Space `deny.hosts [127.0.0.1]` → dial refused | no Space file | stub count 0 vs 1 |
| T3 | Space `allow.hosts []` → every dial refused | Space `allow.hosts` absent | stub count |
| T4 | `allow.roots [<tmp>/s1]`: write to `<tmp>/s10/x` refused; `allow.hosts [*.ex.test]`: `evilex.test` and `ex.test.evil` refused | `<tmp>/s1/x` written; `api.ex.test` passes the matcher | file absent vs present |
| T5 | `mode: audit` in a Space file → the Space's runs refused as unreadable | `mode: audit` in the server file → accepted | Run status + code |
| T6 | Space file with TOON damage / unknown key `alow:` / a **directory** named `safety-policy.toon` (forces the IO branch) → run refused | the same file valid | Run status; zero outputs |
| T7 | allowed stub A answers `302 → B`, B not allowed → refused | B allowed → followed | **B's request count = 0** vs 1 |
| T8 | target allowed, bastion not → refused before any socket | bastion allowed | bastion stub accept count |
| T9 | `sql_template` SQL `read_csv('http://127.0.0.1:<stub>/x.csv')` with `permit.network false` → fails | `permit.network true`, host allowed → reads rows (pins P0-a either way) | stub count |
| T10 | `permit.install_extensions false` → `DuckDbExtension` refuses `INSTALL` | a cached/staged extension still `LOAD`s | no `INSTALL` executed (spy connection) |
| T11 | `permit.advance_state false`, incremental job → 422 at save; planner bypassed (runner called directly) → run fails **before** sink write | `true` → watermark file written | `.watermark` file bytes + mtime unchanged; sink dir empty |
| T12 | Kafka (`MockConsumer`) with `advance_state false` → no `recordDbWatermark`; leader host not allowed → no fetch | allowed → offset recorded | ledger row absent vs present |
| T13 | property: `permitted(E(S,W)) ⇒ permitted(E(S))` over random S, W, acts | — | ≥ 1000 generated cases, shrinking on failure |
| T14 | pipeline file placed on disk (no save gate), host not allowed → refused at N1 act time | host allowed | stub count |

---

## 8. Decisions (operator) — all answered 2026-09-28, as recommended

1. **D1 — Name.** **Answered (operator, 2026-09-28): as recommended.** Recommend **Safety Policy**, extending the existing `SafetyPolicy` record; GLOSSARY §1-A
   entry beside Access Policy with ⛔ bare *Policy*. Alternative: a new type — rejected, it would be a second
   home for roots and caps.
2. **D2 — Tiers.** **Answered (operator, 2026-09-28): as recommended.** Recommend **server + Space now**; the Pipeline tier later. The law already makes a Pipeline
   tier safe to add, and nothing asks for it yet.
3. **D3 — Server file location.** **Answered (operator, 2026-09-28): as recommended.** Recommend `safety-policy.toon` in the server config directory, with
   `-Dassist.safety.roots` read as the server's `allow.roots` when the file does not state them, so existing
   deployments keep working. Breaking changes are free here (nothing after 3.x shipped), so the `-D` can be
   retired later rather than shimmed.
4. **D4 — Default Space roots.** **Answered (operator, 2026-09-28): as recommended.** Recommend a Space's effective roots default to **its own base + the
   operator's declared roots**, not every hosted Space's (closes §1.1). Gate on P0-c's result.
5. **D5 — `mode` values.** **Answered (operator, 2026-09-28): as recommended.** Recommend `enforce | audit`, server file only, default `enforce`; `audit` records a
   would-refuse event and lets the act proceed. Alternative: `enforce` only — simpler; loses a safe rollout.
6. **D6 — Unreadable server file.** **Answered (operator, 2026-09-28): as recommended.** Recommend **refuse runs, keep serving**, `/health` degraded. Refusing to
   boot would also take down the UI an operator uses to see why.
7. **D7 — Naming Space files.** **Answered (operator, 2026-09-28): as recommended.** Recommend the server file may list Spaces whose policy is **required**; a
   named-but-missing file is unreadable; an un-named absent file narrows nothing.
8. **D8 — Mid-run changes.** **Answered (operator, 2026-09-28): as recommended.** Recommend a snapshot pinned per run at plan time; changes apply to the next run.
   Alternative: re-read at every act — catches a tightening sooner, but one run could then pass half its
   gates under each version.
9. **D9 — `advance_state false`.** **Answered (operator, 2026-09-28): as recommended.** Recommend **refuse at plan time** (a read-only run already exists as the dry
   run). Alternative: run without advancing — produces duplicates on every re-run, so rejected unless asked.
10. **D10 — Rewind.** **Answered (operator, 2026-09-28): as recommended.** Recommend a separate `permit.rewind_state` (M8), default `true`, so "may not advance"
    does not also block the operator's reprocess.
11. **D11 — Edition.** **Answered (operator, 2026-09-28): as recommended.** Recommend **core, every edition, Personal included**: this is config safety, not ABAC,
    and the unreadable refusal must not vanish on a classpath without `inspecto-policy` (§1.5).
12. **D12 — Kafka brokers.** **Answered (operator, 2026-09-28): as recommended.** Recommend checking every partition leader host before the first fetch of a cycle.
    Alternative: bootstrap only — leaves the metadata-advertised hops unchecked, which is the row's "every hop".
13. **D13 — Host grammar.** **Answered (operator, 2026-09-28): as recommended.** Recommend exact host, `*.suffix` at a dot boundary, CIDR for IP literals; plus
    checking the **resolved** address against `deny` CIDRs at connect time (the metadata address
    `169.254.169.254` via a DNS name). No regex.
14. **D14 — Caps.** **Answered (operator, 2026-09-28): as recommended.** Recommend caps fold by **MIN** — "permissions AND" applied to numbers — and that
    `ConfigSafetyValidator`'s existing caps (`SafetyPolicy.java:34-40`) become the server tier's defaults.

15. **D15 — Server-configured egress.** **Answered (operator, 2026-09-28): as recommended.** Recommend N10–N12 (notification channels, OIDC, the loopback client)
    stay **outside** the Space tier — no Space run drives them — and are listed as exempt in the explain route.
16. **D16 — `POST` operational-DB test (N8).** **Answered (operator, 2026-09-28): as recommended.** Recommend it passes the **server** tier's `EgressGate`, since its
    URL comes from a request body.
