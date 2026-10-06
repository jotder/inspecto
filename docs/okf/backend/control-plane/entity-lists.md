---
type: Concept
title: Entity Lists — assurance entries (ranges, CIDR, expiry, sidecar, four-eyes)
description: What ASSURE-ENTITY-LISTS-1 (WS-12) added on top of the LA-17 Entity List — range and CIDR entries, expiring entries, the Parquet sidecar Dataset, the match route, the maker-checker hold, and the Risk Score watch-list feed.
resource: inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java
tags: [control-plane, entity-list, assurance, ws-12, maker-checker, risk-score, link-analysis]
timestamp: 2026-09-28T00:00:00Z
---

# Entity Lists — the assurance entries

An **Entity List** is one concept in one store (D-P10, reconciled by `LA-17` D-M8). It is a Space-scoped set of
typed Entity keys with a purpose (`allow · block · watch · exclusion`), stored as facts in the per-Space
**Identity Fact** log (`EntityFactLog`, `audit/entity-facts/`). Link Analysis built it: the routes, the SHA-256 chain,
masking and `excludeBy` / `seedBy` are in
[`link-analysis-entity-model-design.md`](../../../archived-documents/plans-archive/link-analysis-entity-model-design.md) §4.

`ASSURE-ENTITY-LISTS-1` (WS-12, shipped 2026-09-28) extends the same store and the same routes. It adds no kind and
no second store. 🔁 **SEP-08 (2026-10-01):** the store, the seven routes (`/entity-lists…`, renamed from `/inv/entity-lists…`) and the
one shared, hash-chained fact log (`EntityFactLog`, `EntityRegistry`, package `com.gamma.entitylist`) now live in the optional
**`inspecto-entity-list`** module, bundled in exactly the editions that ship `inspecto-geo-link` and absent on Personal
(`AbsentEntityListRoutes` answers the seven routes 503; `/bootstrap` `features.entityList` is `false`). Link Analysis
(`inspecto-geo-link`) depends on it — its `/inv/entity-identities*` routes, `EntityMasking` and the Investigation list-ops
append to and read the SAME log through it; the reverse dependency does not exist. The on-disk format and paths are
unchanged (`audit/entity-facts/`, `mask.key`), so a Space created before the move still verifies its chain. The masking
token algorithm moved with it as `MaskTokens`.

## Range entries (`EntityListEntries`)

`POST /entity-lists/{id}/members` takes `addRanges` / `removeRanges`. Each entry is exactly one of:

| Authored | Stored (canonical) | Matches |
|---|---|---|
| `{prefix: "+4478"}` | `prefix:+4478` | a key that starts with the prefix |
| `{from: "447800000000", to: "447899999999"}` | `range:<lo>..<hi>` | a key of the **same length** with `lo ≤ key ≤ hi` |
| `{cidr: "10.1.0.0/16"}` | `cidr:10.1.0.0/16` | an IPv4 or IPv6 address inside the block |

- Prefix and range bounds are normalised with the list's **sealed** normaliser (D-M9), like members.
- A range needs equal-length bounds: `44785` sorts between `447800` and `447899` but is not in the block.
- A CIDR block with host bits set is refused (422) and the error names the network. IPv6 is stored as eight
  lower-case groups with no `::`, so one block has one spelling.
- An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) is the IPv4 address, both as a block (`/96+n` → `/n`) and as a
  candidate. A zone id (`%eth0`) is not an address. Leading-zero octets (`010.1.2.3`) are refused.
- Addresses are parsed by hand. `InetAddress` is never called, because it would resolve a host name.
- CIDR matches the **raw** value (trimmed), not the normalised key. An address is not an entity key.

Range entries live in their own set, beside the members. A `default`-normalised member key could spell like
`prefix:…`, so the two never share a map.

## Expiring entries

`members` takes `expiresAt`, an ISO-8601 instant in the future. It applies to every entry that call adds. The fold
keeps an expired entry (an as-of read shows it, and `expiring[]` flags `expired: true`), but it **stops
matching**:

- `POST /entity-lists/{id}/match` does not return it;
- `excludeBy` / `seedBy` do not seal it (`InvestigationRoutes.sealList` seals `liveMembers(now)`);
- the sidecar keeps the row with its `expires_at`, and the consumer filters on it.

A later permanent add revives the entry, and a remove forgets the expiry. **An expiring add never shortens a
PERMANENT entry**: that add is a no-op (`changed: 0`). To shorten one, remove it and add it again.

## `POST /entity-lists/{id}/match`

`{values: [...]}` (1..5 000) returns `{listId, purpose, atSeq, matches: [{value, matched, match?, entry?}]}`.
`match` is `key | prefix | range | cidr`, and `entry` is masked exactly as the list renders. A retired list
matches nothing.

The route is **read-shaped** (a `CapabilityManifest` exemption: it persists nothing). It is a POST so that keys
never travel in a URL or an access log. Reads need Space access only. Without a write root the answer is 503, like
every Entity List route.

## The Parquet sidecar (`EntityListSidecar`)

Every write (create, members, retire, and the feed) rewrites `<dataRoot>/entity_list_<id>/entries.parquet`. The file
is written to a temp name and moved into place atomically. A Dataset with `physicalRef: entity_list_<id>` reads it
through the ordinary `DatasetRelation` path.

- Columns: `list_id, purpose, entity_type, match, entry, lo, hi, expires_at, added_at, added_by, reason`.
  `added_*` and `reason` come from the fact that last added the entry.
- For `cidr`, `lo` / `hi` are the first and last address as fixed-width hex: 8 digits for IPv4, 32 for IPv6.
  An address converted to hex joins with `length(lo) = 8 AND hex BETWEEN lo AND hi`, which an equal-key join
  cannot do.
- A retired list writes zero rows.
- ⚠ **The fact log is the truth.** A failed sidecar write never undoes the fact. The route answers
  `sidecar: "failed"` and logs a WARN. The answer is `none` when there is no data root **or none on disk**: the
  legacy single-Space default is relative to the CWD, and a derived projection must not plant a data tree there.
- The writer only touches a directory with its own `.entity-list-output` marker naming the list, so it can never
  overwrite a Dataset it did not create.

## Four-eyes: the kind `entity-list`

`entity-list` is a governable approval-policy kind (`ApprovalPolicy.GOVERNABLE`). Under
`approval: {entity-list: {required: true}}`:

- **`members` and `retire` are held** (`PendingChanges.hold`; both are on `PendingChanges.REPLAYABLE`). A held
  change answers 202 and writes no fact. A second person approves it, and the replay appends the fact with
  `approvedChange: {id, author}`.
- The held content is the effective delta (`add`, `remove`, `addRanges`, `removeRanges`, `expiresAt`), masked as
  the list renders. The base is `{listId, purpose, lastSeq}`, so any change to the list in between makes the
  approval stale (409).
- **D-P5:** a change that only adds, and whose every entry expires within 24 h, applies at once. Under a policy
  it answers `reviewAfter: true`. A removal is never exempt.
- `create` is not held, because an empty list matches nothing.

## The Risk Score watch-list feed

A `risk-score` model may declare `watchList: {list, ttlHours}`, where `ttlHours` is a whole number in 1..24.
After each `risk.score` run, every **high** entity is added to that `watch` list, expiring `ttlHours` after the
run:

- the feed writes one `list.member.added` fact per run, with actor `job:risk.score:<model>`;
- a permanent member stays permanent;
- the sidecar is refreshed.

The 24 h cap is what lets a Job skip four-eyes (D-P5).

The seam is `com.gamma.entitylist.WatchListFeed`, an engine SPI that `inspecto-entity-list` provides by `ServiceLoader`
(`RiskWatchListFeed`). The feed fails closed:

- the save is refused (422) when the list is unknown, retired, not `watch`, or its type is no longer in force;
- the save is refused when the edition carries no Entity Lists;
- a run whose feed cannot happen **fails**. It never skips silently.

## Session decisions (2026-09-28, lane `ASSURE-ENTITY-LISTS-1`)

1. **The store stayed in `inspecto-geo-link`** (superseded by SEP-08, 2026-10-01: it is now the `inspecto-entity-list` module). Moving it into core was not needed. The one core consumer, the
   Risk Score Job, reaches it through a small SPI, and moving it would have meant rewriting Link Analysis-owned
   files. Personal therefore has no Entity Lists.
2. **Ranges are canonical strings in a separate set**, folded like members, so as-of reads and the chain cover
   them with no new mechanism.
3. **Same-length range bounds only.** A block of numbers stays a block of numbers.
4. **IPv4-mapped IPv6 is IPv4**, so one block covers both spellings.
5. **Expiry is judged at match time, not at fold time.** Time passes without a write, so an as-of fold stays
   deterministic.
6. **An expiring add never shortens a permanent entry.**
7. **Match is a POST** (read-shaped) so keys never ride in a URL.
8. **`create` is not governed; `members` and `retire` are.** Only additions expiring within 24 h are exempt (D-P5).
9. **The sidecar is a projection.** It never fails the write, and it never creates a data root.

## Residuals (P3 `ASSURE-ENTITY-LISTS-RESIDUALS-1`)

- `excludeBy` / `seedBy` seal exact keys only; range entries do not take part yet. This is Link Analysis-owned. Decided 2026-10-04: DEFER until a customer asks; range / CIDR matching changes the sealed-log format and the replay hash, so it needs its own design.
- ~~There is no SPA authoring for ranges or expiry~~ shipped 2026-10-06: each writable list in the Investigation
  tab's Entity Lists section has **Edit entries…** (`EntityListEntriesDialog`). It takes one entry per line, and
  the framework-free `parseEntityListEntries` (`link-analysis/entity-list-entries.ts`) splits them into an exact key,
  `+4478*` → `{prefix}`, `lo..hi` → `{from, to}`, or a line holding `/` → `{cidr}`. It refuses a bad line by
  number and sends nothing. The dialog has Add / Remove, and an optional `datetime-local` expiry on an add, which
  must be in the future. Before the edit it reads the list and shows its `ranges[]` and `expiring[]` as served,
  so they are masked, and an expired entry is badged. The server stays the gate: it normalises, checks
  equal-length bounds, and refuses host bits. A `202` hold (from Edit entries or Add selection) is reported as
  *held for approval as `<pc-id>`*, never as an applied change.
- ~~A Dataset over a sidecar is not registered automatically~~ shipped 2026-10-04 as an **explicit, manual** action (below).
  A list write still never writes Dataset config.
- ~~`approverCheck` for a held list change~~ shipped 2026-10-04: every *pending* Pending Change (any kind, so a held
  list change too) carries `approverCheck: none-eligible | unknown | ok` on `GET /pending-changes[/{id}]`, computed live by
  `ActionRequestRoutes.check` over the change's own `approverCapability` with its author out when `fourEyes`. It is
  informational (never auto-declines) and, unlike an Action Request's, ignores data scope: a Pending Change's approve
  gate is the capability alone. Raising it emits no audit row.

## Register the sidecar as a Dataset (manual, 2026-10-04)

`POST /entity-lists/{id}/register-dataset` (no body) creates the Dataset `entity_list_<id>` with
`physicalRef: entity_list_<id>`. The SPA's Entity Lists section offers it as **Register as Dataset** (after a confirm).

- **Same save path as a hand-authored Dataset.** The route calls `DatasetRegistration.create` (public seam in the core,
  because `ComponentRoutes` is package-private), which runs `ComponentRoutes.createComponent`: sharing envelope and owner
  stamp, `validateKind`, the reserved-prefix check, the versioned store write. It does not write the registry file itself.
- **Capability: `canAuthorWorkbench`** (the Dataset-authoring one), not `canManageIncidents`. `CapabilityManifest` carries the
  entry. ⚠ A Dataset reads the raw Parquet, so masking does not apply to it. The confirm says so. Anyone with
  `canAuthorWorkbench` could already hand-author the same Dataset, so a second capability would add no protection. Decided 2026-10-04 (operator): KEEP `canAuthorWorkbench` as the only gate. The SPA's 403 message for this action names the Workbench-authoring capability (not Incident management).
- **Gates.** No write root 503, unknown list 404, **no sidecar on disk 409** (a Dataset over nothing reads nothing), a Dataset of
  that id already exists **409** (never rewritten, so a repeat is safe and a hand edit survives), an approval policy for kind
  `dataset` **409** (refused, not held), the Dataset gate 422. There is no path-jail 403: the only path is built from a
  `LIST_ID`-checked id. The 422 has no live trigger today, because the content is server-built.
- **Why refuse, not hold, under a policy.** A held change replays through the route that recorded it, and
  `PendingChanges.REPLAYABLE` is pinned (`ConfigWriteFunnelTest`) to routes that hold in their own source file.
  `EntityListRoutes#registerDataset` is a `WRITERS` row there for the same reason as the other routes that open the fact log.
- A retired list offers no button (its sidecar holds zero rows).
- Test: `ControlApiEntityListRegisterDatasetTest` (real Spaces, a Subject per caller, including an author without the
  capability and a list-writer without it).

## Scale facts (SP4, 2026-10-06)

Measured in `inspecto-entity-store` (`ListPruningSp4Bench`, `FactLogReplaySp4Bench`, gated `-Dbench.run=true`; findings in `docs/superpower/link-analysis-roadmap.md` "SP4 findings"). As an exclusion/barrier anti-join over a 10^7-edge Parquet relation, a distinct-live-key table derived from the sidecar adds within noise of 25% up to ~10^6 members; filtering the raw sidecar per hop degrades past ~5x10^6 rows, and a literal `NOT IN (...)` list is unusable (seconds at 2,000 literals, a JVM crash at 10^4). `EntityFactLog.read()` verifies the whole chain on every call: ~1 s at 10^4 facts, ~10 s at 10^5, minutes at 10^6; a head-hash cache (0.2 ms) is the cheapest fix and is recommended but not built (a deep tamper is then caught only by the next full verify).
