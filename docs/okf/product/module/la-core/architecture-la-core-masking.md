---
type: Concept
title: Link Analysis — architecture — Domain core — masking onward
description: Part 2 of 4 of Link Analysis — architecture: 
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — architecture — Domain core — masking onward

Part 2 of 4 of [Link Analysis — architecture](architecture-la-core.md) · previous: [Shape, modules and the domain core](architecture-la-core-shape-modules.md) · next: [Domain core — discovery; link index, Graph Run and HTTP surface](architecture-la-core-index-http.md).

## 3. Domain core (la-core) (continued)

### 3.4 Masking

How Link Analysis keeps raw identifiers out of its answers: mask tokens and their keys, the exploration alias book, the
masked and conditional reads, reference rows and Reference details, case history, entity profiles, *Start from...* and
*My work*.

`MaskTokens` (entity-store): token = `masked:` + first 16 hex of HMAC-SHA256(key, id); the 32-byte key is published
by hard link so a race loser reads the winner's key. One key per Investigation (`mask.key` in its directory) and ONE
Space identity key (`MaskTokens.spaceKey`: `<write root>.secrets/.risk-score-mask.key`, owner-only, outside every
exported, imported and backed-up tree; D-M7, 2026-10-13, moved from `audit/entity-facts/mask.key`, so a token minted
before the move differs from one minted after) shared by LA exploration, Entity Lists, Screening, BI and Risk /
Anomaly Scores. A list member's token therefore differs from an Investigation's for the same value. `LinkAnalysisSettings.maskingMode` defaults
to `typed` (fail closed). `EntityMasking` (la-api) masks on the way **out**: engine results and caches stay raw; a
raw id that masking hides is answered as a nonexistent node; `reveal` needs `canRevealLinkEntities`.

**One Reveal for the nodes on screen, one alias per entity (LA-FULL-CYCLE-TEST-1 phase 2, decisions D3 and D4,
2026-11-16).** Masked stays the default. `POST /inv/investigations/{id}/reveal` takes every masked alias on screen in
one call (up to `input.reveal_ids`; the SPA chunks a larger screen) with a required `reason` (non-blank, at most 200
characters, 422 otherwise) and writes **one** `LINK_ENTITY_REVEALED` row per entity revealed (LA-19), naming the alias
as `alias` and as the row's target, with the reason and `via`, never the raw id. ⚠ Gotcha: the old single row carried
the aliases as a `tokens` attribute, and `SecretScrubber` redacts every attribute whose key names a secret, so the trail
read `tokens=***redacted***`; never name an audit attribute `*token*`. The deep-link reveal
(`InvestigationInboundRoutes`) writes the same shape since 2026-11-16 (LA-DEEPLINK-REVEAL-AUDIT-1): the alias as `alias`
and as the target, `via: deep-link`, `count` 1, and no Investigation or reason, since a deep link carries neither. The
scrubber is unchanged; the attribute was renamed instead. The gate is unchanged: a lead of the
Investigation holding the capability; a reviewer or analyst 403, anyone else 404. Nothing is stored: a reveal changes
no later response, and the SPA keeps the real ids in browser memory for the open Investigation only
(`InvestigationSessionStore.revealed`), drawn as the node's `canvasLabel` while `label` (what every op, search and
selection sends) stays the alias. The step log rewrites revealed aliases on screen (`revealText`); the Dossier keeps
them masked (its own hand-off reveal is unchanged). Reveal is offered only in a saved Investigation: an unsaved or
exploration canvas has no Investigation and purpose to record it against, and says so.
*Details go with their id:* `/inv/reference-rows` masks the text and date values of a row whose key is masked, because a
holder's name, a city or a date beside a masked id identifies it; numbers and flags (counts, scores, measures) stay
readable, as `typed` leaves an unclassified number in the clear (`all` masks integral values anyway).
`/inv/projection/multi` masks a node mapping's `attrs` with its id: they are read as text (`CAST … AS VARCHAR`), so a
numeric attribute is masked there too, the fail-closed side of the same rule. With `reveal: true`, a
`reason` and an `investigationId`, reference rows read that Investigation's pseudonyms raw (the key and the details
masked only because of it; a column masked by its own classification names another entity and stays masked, and under
`all`, where the Space masks every value that may be an id whatever the key, a reveal lifts the key alone), under the
same gate as the reveal route, one audit row per entity read (`via: details`); the node dialog's Details and Enrichment
ask this once the screen revealed the node. *One alias per entity (D4):* aliases stay per Investigation (an alias that
cannot be matched across Investigations is a privacy property, and one key would re-mint every sealed Dossier's
aliases). Inside an Investigation every surface shows its alias: reference rows' key column reads the alias the
caller ASKED with (it read the Space's alias beside the Investigation's before), and the node dialog's Summary maps
its counterparts through the Working Set's `exploreAliases`. Exploration outside an Investigation keeps the Space key.
Tests: `ControlApiInvestigationRevealOnScreenTest` (written failing first against master); the SPA's
`investigation-state`, store, oversight, node-insight, entity-summary and component specs.

**A reader outside Link Analysis resolves an Investigation's alias through a port** (`LA-SUSPICION-FACTORS-INVESTIGATION-1`,
2026-10-31). `InvestigationAliases` (entity-store, ServiceLoader) is provided by la-api's `InvestigationAliasResolver`:
`InvestigationRoutes.openForRead` first (its 404 / 422 propagate), then `EntityMasking.of(inv, []).reveal(alias)`, so only a
pseudonym that Investigation issued resolves. Its one caller is `GET /risk-scores/{model}/{alias}?investigation=<id>` (the
Studio's *Factors* on an Investigation canvas: the toolbox passes `investigationId()` to `inspecto-risk-score-panel`). We kept
the per-Investigation key rather than moving Investigations to the Space key: moving would change every existing Investigation's
pseudonyms (Dossiers, notes and hand-offs already carry them).

**Mask the same way everywhere (DR-D2, operator 2026-10-10).** The stateless exploration reads (`/inv/projection{,/neighbors,/multi}`,
`/inv/traversal/recursive-paths`, `/inv/pattern/*`, `/inv/value-measures`) have no Investigation, so `ExplorationMasking`
(la-api) masks them under the **Space** identity key (`MaskTokens.spaceKey`, the key an Entity List member uses; D-M7): `all`
masks every id; `typed` masks every id when a bound endpoint column's classification is claimed by a masked Entity Type
(`EntityMasking.maskedColumns`, the rule an Investigation applies); `none` shows raw. Each answer carries
`masking {mode, masked, basis}`, which the SPA shows as a *Masking: mode* badge in the query panel. An alias handed back (an
expand's `value`, a traversal's `startNode`, an Investigation seed's `ids`) is resolved from an in-memory alias-to-raw
book of what this server minted for this Space (bounded LRU; nothing raw is ever sent). The book is also appended, one
`alias<TAB>base64(raw)` line per newly minted alias, to `<root>.secrets/.exploration-alias-book` beside the Space key
(owner-only, outside every exported tree), and every in-memory miss scans that file, so an alias survives a restart
or an eviction; a line is believed only when its alias is the token of its raw value, so a torn line is skipped
(LA-EDITIONS-RESIDUALS-1 4c, 2026-10-19; `ExplorationMaskingSpaceKeyTest`). **Measured and fixed 2026-10-20
(`LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1`, `ExplorationAliasBookScaleBench`):** the per-miss scan was a half-file read on average
(109 ms per alias on a 1 M-line / 39 MB book, so resolving 200 distinct evicted aliases took 21.8 s and a 1000-node graph after
a restart about 110 s: linear in the book per alias, quadratic over a graph). A per-book-file offset index (alias to line
start, 64 bits of the token, first line wins, two `long[]`, about 32 bytes per alias) is now built by ONE pass and only
extended over the bytes appended since; each miss then reads the one indexed line and still believes it only when its alias
is the token of its raw value (a torn first line of an alias falls back to the full scan, so a later good line is found). The
same 200 aliases cost 1.5 s (one 1.2 s pass plus 200 single-line reads, 7.6 ms each); a never-minted alias costs nothing after the pass.
A book above 4 M lines (about 160 MB) stops being indexed and falls back to the scan (4.5 M lines: 0.8 s per alias, accepted: far
above the 500k in-memory bound). A repeated miss on a book of unchanged size still answers from the bounded negative cache
(any mint grows the book and re-opens the question), pinned by `ExplorationMaskingSpaceKeyTest`. Case history tries the Investigation's own
pseudonym before the book, so Investigation-alias nodes never read it. An append first ends a torn last line with a
newline, so a crash mid-append cannot swallow the next line; appends go through an `APPEND` channel as one write, so
two processes sharing a Space lose no line. ⚠ The negative cache is keyed on the file's SIZE: an outside rewrite of the
book to the same size can leave a cached miss stale until restart. The
file is not compacted: it grows by one line per distinct alias minted (plus one if an evicted alias is minted again).
An alias never minted (or whose book was removed) is 422 *alias not known* and the query is re-run. ⚠ An Investigation keeps its own key, so a seeded entity has a second alias
there: Working Set answers (`/working-set`, `/replay`, op answers) carry `exploreAliases` (Investigation alias to
exploration alias, aliases only) and the SPA matches a selected node to its Working Set entity through it. Sealed logs still
bind raw ids. Known gap: an Investigation also masks a seed's explicit `entityType`, which a stateless read does not
carry, so a column no Entity Type claims is raw on the query graph but seeded ids of a masked `entityType` are aliased in the Working Set.

**Stateless four-eyes semantics (fix 2026-10-11).** A *default* is not a declaration (2026-10-12): a `recursive-paths` call that sends no `maxEdgeYield` has its default clamped to `four_eyes_fan_out_above` (the response `fences.maxEdgeYield` says what ran) instead of being refused; one that declares a larger bound is still refused. A stateless read's `limit` is a RENDER CAP, never a fan-out: `/inv/projection`,
`/multi` and the identity import are judged only as rows against `four_eyes_budget_above`. `four_eyes_fan_out_above` gates where one
entity's expansion is the thing read - an Investigation `expand` (held for approval) and `/inv/projection/neighbors` of ONE node, judged
on that node's real counted degree (distinct links; over the threshold = 403). Traversals still judge their declared `maxEdgeYield`.

**Conditional projection read (PERF-P4-2, 2026-10-24).** The flat `/inv/projection` and `/inv/projection/neighbors` (not `list`,
not `/multi`) answer with `ETag` + `Cache-Control: private, no-cache`; a repeat with a matching `If-None-Match` gets `304` and no
projection read (`ProjectionCache`: LRU 64, TTL 60 s, process-local). One read still runs before the cache is
consulted: a `/inv/projection/neighbors` request, when `four_eyes_fan_out_above` is set, first runs the one degree
`COUNT(*)`, because that gate must judge the live degree before a cached answer is allowed. It is consulted only AFTER every gate of the read (write root,
body 422, R3 view 404, filter, four-eyes, alias resolution), and the read is still audited on a hit with the miss's own
`source` / `indexVersion` / `indexStale` / `sourceReason`. Bounds: a result over 5,000 rows or 2 MB serialised is served but not
stored (no ETag); all entries together stay under 32 MB of SERIALISED bytes, evicting least-recently-used by bytes. ⚠ That
figure counts the JSON, not the heap: measured retained heap is about 5.8x the counted bytes unmasked and 4.7x masked, so the
real footprint at the bound is roughly 130-190 MB. Every store first sweeps the expired entries; an expired entry is never
served, but it stays in memory until the next store sweeps it (not at the TTL). The key (and so the ETag)
hashes: route, Space, viewer (Subject id, capabilities, data scopes, attributes, held roles), canonical body, resolved limit, the
Dataset registry entry and relation SQL, its FRESH input fingerprint, the LA settings in force (masking mode, Entity Types,
thresholds, index, caps), the Space masking key, and for neighbours each mapping's published index version. Only a `files:`
fingerprint is cached - a view, a Postgres store, too many files or a timed-out listing is read every time. Limits, stated:
(1) the fingerprint sees path, size and mtime (ms) only, so an in-place rewrite that keeps BOTH size and mtime (an
mtime-preserving copy) is served from the cache until the TTL ends; (2) a hit does not re-run masking. This adds no exposure of its own:
`ExplorationMasking.lookup` answers from the in-memory alias book first, and `remember` appends to the book file only
for a pair this process did not already hold, so a MISS does not re-write a deleted book file either. A pair the process
never held is simply unknown (422 "run the query again") whether the read was a hit or a miss; (3) the relation SQL is derived from the registry entry for every cached
(files-backed) Dataset, so it is never the only key input that changes. The executor's outer `LIMIT n+1` is
already planned by DuckDB as a Top-N over the group-by (no full sort), so the projection needs no inner limit.

**Conditional Working Set read (PERF-P4-3, hardened 2026-10-25).** `GET /inv/investigations/{id}/working-set` answers with a strong `ETag`
built from the store's `logToken` (a stat), the verified content key, table, page, pin and masking profile; a matching `If-None-Match`
is a bodiless `304` after the gate. Four rules keep it honest: (1) the token is taken BEFORE the log is read and re-checked AFTER the read
and the fold - if it moved (a failed promote or Draft append truncating inside one coarse mtime tick) the freshly read answer is served as a
correct `200` UNCACHED with NO ETag, so an old token is never cached against newer content; (2) a cached relation is trusted for 30 s on a
MONOTONIC clock (`System.nanoTime`), so a backwards wall-clock step cannot stretch the window before the full SHA-256 revalidation; (3) the test
seams (`WorkingSetRoutes` clock/window/`FULL_READS`, `FsInvestigationStore.LOG_READS`) are package-private, reached from other modules only
through same-package accessors in the test source sets; (4) an error response of any status never carries an ETag - `WorkingSetRoutes` tags
only around a successful serialisation and `ControlApi.respond` withdraws any tag from a status of 400 or above (this also covers the shared `ETags.respond`).

**Owner display names in the list (T-186, 2026-11-01).** The server keeps no directory of people (a `Subject` carries only its own
name), so `InvestigationRoutes.recordOwnerName` writes the creator's `displayName` as `ownerName` into the Investigation's `details`
record right after the create, fork (`/reorder`) or template instantiation succeeds - through the existing `updateDetails`, so the
filesystem and Postgres stores needed no change, and a later edit merges per field and keeps it. `GET /inv/investigations` serves the
recorded name; an Investigation without one (created before this) falls back to the caller's own Subject name when the caller owns it,
else no `ownerName` (the UI shows the login id). Masking: the name passes `EntityMasking.inText` exactly as the title does, so a name that
is a masked entity id shows as its per-Investigation pseudonym; the name is never laid over the header (`withDetails` takes only title and
purpose), so it reaches neither the log header, the embed nor the Dossier. Not atomic with the create: a crash between the two writes
leaves an Investigation that shows its login id, which is the older behaviour, not a failure.
**Live check 2026-11-01 (Preview Demo Auth bundle of `a24fca153`): FAIL on Demo Auth.** An Investigation instantiated from
`mule-ring-template` as `analyst` (and one made by a plain create) listed `owner` only, with no `ownerName`, so **File ▸ Open…** read
"Owner analyst" even to its creator, and no `investigation-details.json` was written. Cause: `DemoAuthenticator` puts the Demo User's
name in `Subject.attributes().displayName`, not in the `displayName` component, so `recordOwnerName` finds no name and the list fallback
none either (defect `DEMO-AUTH-SUBJECT-DISPLAY-NAME-1`). An OIDC sign-in, which fills `displayName`, was not driven.
**Fixed 2026-11-01 (`DEMO-AUTH-SUBJECT-DISPLAY-NAME-1`):** `DemoAuthenticator` passes the Demo User's name as the Subject's `displayName`
component as well, so nothing on the LA side changed. `DemoAuthHttpTest` asserts the session's display name, and
`ControlApiInvestigationListTest` now covers the template-instantiate path. Not re-walked live yet.


**Reference rows (`POST /inv/reference-rows`, 2026-10-11).** Body `{dataset, keyCol, keys[1..500], columns?, investigationId?}`
→ `{dataset, keyCol, columns, rows:[{key, row}], matchedKeys, unresolved, rawKeysRefused, truncated, masking{…, maskedColumns}}`. The node
dialog's Risk / Enrichment and the panel's *Fill nodeScores from indicators* look entities up through it instead of reading a
whole Dataset over `/db/query` (which masks nothing). Gates: 503 write root → 422 body (`keyCol`/`columns` safe identifiers)
→ 413 over 500 keys → with `investigationId` the Investigation read gate (404) → `relationFor` R3 (404) → four-eyes row
budget on the key count (403) → 422 for a column the relation lacks. Identifiers are quoted; keys are bound `?` parameters
shaped so DuckDB can prune row groups by min/max (`typeof` of one row picks the form): a VARCHAR key is compared directly
(`key IN (?, …)`), an integer key gets typed binds (`key IN (CAST(? AS BIGINT), …)`) when every key is the canonical decimal
of an in-range value, and anything else falls back to `CAST(key AS VARCHAR) IN (?, …)` (same rows, never a failure), at most `reference.max_rows` rows (default 4,000; `truncated`). Keys IN (only when the exploration rule masks `keyCol` - otherwise an alias is unresolved, so it cannot be matched against an arbitrary column as a membership oracle): an Investigation pseudonym
(`EntityMasking.reveal`) or an exploration alias (the book) resolves; under masking a RAW key names nothing (counted in
`rawKeysRefused`, never echoed, like `/inv/graph/runs`' hidden raw id), so the route is no raw-id oracle. Rows carry the key AS ASKED. Values
OUT, `none` shows raw; otherwise the key column is masked when the exploration rule masks `keyCol` or any key was an alias,
and any column whose registry/lineage class is claimed by a masked Entity Type or is `ColumnClassification.SENSITIVE`
(MSISDN/IMSI/ACCOUNT/PII) is masked, and under `all` every value but fractional numbers and booleans (an integral column may be an unclassified id), to the Space's exploration alias; untraceable lineage masks every column. Audited as
`LINK_REFERENCE_READ` (counts only, never the keys).

**Reference details on nodes (`ONBOARDING-FIRST-RUN-1` C6a step 4, 2026-11-05; A-5, every edition).** A Reference is
an Add data Dataset of kind `reference` with an `identifyingColumn` and a `primaryEntityType` (`LaDataset` gained
`kind` and `identifyingColumn`, filled by both hosts: the LA app from the registry entry, the console from Studio's
`extra`). `reference-details.ts` (`nodeReferences`, `referencesFor`, `nodesNotMatching`) keys References by that
Entity Type. **Node dialog:** `NodeInsightContext.references` lists the References for the node's type (its own
`entityType`, else the type the graph is drawn as); the **Details** section reads the node's row of each through
`NodeRiskService.lookup` (`POST /inv/reference-rows`, so masked, the key as asked, the Investigation's aliases
resolved) and lists its other columns. **Filter by details** (`<inspecto-la-reference-filter>`, in the filter panel of
the *rows* dock tab, explorer only, shown while a drawn Entity Type has a Reference): a Reference (when several), a
detail and a text; `filterByDetails` looks up the drawn nodes of that type (chunked at 500 keys) and `hideLocally` the
ones whose detail does not contain the text (case ignored) or that have no row - unless the read was cut
(`truncated`: a missing row may lie beyond the cut, so those stay, and the line says so). The filter remembers the nodes it
hid: it is *active* while any of them is still hidden (Undo, a new draw or *Show hidden again* end it), and *Show all*
brings back only those, never another hide. The panel is offered from the whole drawn graph, so a filter that hid every
node of its type keeps its *Show all*. No
join builds data (DT-6). Tests: `reference-details.spec`, `link-analysis-reference-filter.component.spec`,
`node-insight.component.spec` (Details), `link-analysis.component.spec` (*Filter by details*).

**Colour by a Reference detail, and the coverage line (`LA-REFERENCE-DETAILS-MORE-1`, 2026-11-06; every edition).**
*Colour:* `NodeRule` gained `reference?` (the Reference's Dataset id). A rule with it reads `attr` from
`GraphDisplayOptions.referenceDetails` (Reference → node id → column → value), never from the node's `attrs`.
`nodeRuleMatches` never matches a value starting `masked:` (`MaskTokens.TOKEN_PREFIX`). The host's
`referenceDetailsWanted` (the References a rule names among `drawnReferences`, with every drawn node of their Entity Type,
hidden nodes included, so a hide does not re-read) drives one effect. `loadReferenceDetails` runs `NodeRiskService.lookup`
(chunked `POST /inv/reference-rows`; a newer read wins), and `detailValuesByNode` keeps only unmasked values: it drops the
key column, every `masking.maskedColumns` entry (any case; `ReferenceTable` gained `maskedColumns`) and every `masked:`
value. A node without a row gets no entry and stays uncoloured. `referenceDetails` sits in `displayOptions` only, never
`chosenDisplay`, so a saved view keeps the rule but not the values. `referenceDetailsNote` lists unreadable or truncated
reads and masked details. The rule works in the explorer only, like *Filter by details* (an Investigation has no
`drawnReferences`).
*Coverage:* `GET /datasets/{id}/coverage` (`DatasetCoverage`, core `DatasetRoutes`; read gate = the Dataset's view
permission, as `/arrivals`; 503 without a write root, 400 for a bad id, 404 for an unknown or unviewable id) answers
`{dataset, entityType, entityTypeLabel?, identifyingColumn, described, datasets:[{dataset, columns, appear, absent}],
truncated, reason?}`. `described` counts the distinct trimmed keys of the identifying column. Each other viewable,
non-Reference Dataset contributes the columns whose `classification` resolves to the Entity Type
(`OnboardMatches.typeOf`), and `appear` counts the described keys found in any of them. The read is bounded at
`MAX_SCAN_ROWS` = 1,000,000 rows per Dataset and `MAX_DATASETS` = 20 Datasets; `truncated` says when a cap was hit. The
answer holds counts only, and never a count over a column masked for the caller (`BiResultMasking.hidesFromCaller`, the
`matches` rule). Someone who may create a Dataset can fill it with chosen values classified as the Entity Type, so
`appear` would otherwise tell them which masked keys the Reference holds (the verification probe, 2026-11-06). So a
Reference whose identifying column is masked for the caller answers no counts, only a `reason`, and a holder's masked
columns are never compared; a holder left with no column is not listed. Under masking, then, the line shows only to
someone who may reveal ids, or when the keys are not masked. Keys compare
as trimmed text, without the Entity Type normaliser, the same as `matches`. The UI is
`<inspecto-la-reference-coverage>` on the shared import card for a `kind: reference` Dataset, so it shows on Add data's
result, the console Dataset page and the Link Analysis Data page. It re-reads whenever the card's arrivals change and
shows nothing when the read fails. Tests: `ControlApiDatasetCoverageTest` (a synthetic pair: 40 described, 20 appear, 20
absent, no key in the body; no count over a column masked for the caller, either side; a Dataset the caller
cannot view is 404 as the Reference and never counted; the bounded scan), `node-rules.spec`, `reference-details.spec`,
`link-analysis-display-rules.component.spec`, `link-analysis.component.spec` (the batched masked read),
`reference-coverage.component.spec`, `import-dataset-card.component.spec`. All are mutation-checked.

**Case history (`POST /inv/case-history`, 2026-10-11).** Body `{key, investigationId?, status?, limit?}` →
`{entityType, resolved, reason?, objects, total, truncated, masking{mode, masked}}`. The node dialog's Case history for a
key it holds only as an alias (`GET /objects/by-entity` needs the raw id). The key resolves with the reference-rows rules:
an Investigation pseudonym (readable Investigation only), else an exploration alias, and only when the masking covers the
Entity Type (`all`, or `typed` with a masked type). A raw key resolves only under mode `none`; under any other mode it
names nothing, whatever the header type (`resolved:false`, the same answer as an unknown alias — no oracle;
`LA-CASE-HISTORY-RAW-KEY-1`, 2026-10-19). So under `typed`, an uncovered type (e.g. `cell`) has no case history here. The read applies the `ObjectReadMasking.hides` filter
(D-P8, `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1` (1)): `EntityHistory.query` (the 7-argument public form `ObjectServiceAccess.byEntity` calls) builds the
filter from the Space root and the caller itself, as does the `GET /objects/by-entity` route, so a caller without `canRevealLinkEntities` gets no object
matched through a classified key and no seam caller can skip it. Entity Type = the Investigation header's `entityType`/`sourceType`/`targetType`,
else `msisdn`. The read is the ops module's own (`ObjectAccess.byEntity` → `EntityHistory.query`, reached through
`CasePort.history` / `HostCasePort`): same matching, SEC-7d / ABAC post-filter and `truncated`. No key is echoed; under
masking titles are scrubbed fail-closed: the raw id in any spelling (spaces, dashes, dots, parentheses, `+`) becomes the
key as asked, every other token of 5+ digits `[number]` and an email `[email]` (placeholders, never minted aliases), and
rows carry only an allowlist of fields.
**Attached Cases (fix, 2026-10-11).** Attach to Case (seal → `POST /inv/snapshots/attach` → `PUT …/case`) registers no
entity reference on the host, so the host's read alone missed the very Case the analyst attached the graph to. The route
now also lists, deduplicated against the host rows, each Case linked to an Investigation whose current Working Set
contains the entity (`matchedOn:"investigation"`, `via` = Investigation id) and each Case a sealed snapshot holding the
entity among its `nodes` was attached to (`matchedOn:"snapshot"`, `via` = snapshot id; alias / pseudonym node ids are
resolved server-side). Fail closed: only Investigations the caller can read (`openForRead`; a snapshot is judged by the
Investigation it is anchored to, an unanchored one is never listed) and only an existing Case the caller may see
(`CasePort.summary` + `visibleTo`); same status filter, title scrub and field allowlist. Bounded: at most `case_history.max_linked_investigations`
(default 200) Case-linked Investigations and the newest `case_history.max_attachments` (default 1,000) attachment records are read; beyond that `truncated:true`.
Gates: 503 write root → 503 Case management not installed → 422 body → Investigation read gate (404) → 422 unsearchable
key (message never names it). Rate class expensive; audited as `LINK_CASE_HISTORY_READ` (counts only).

**Entity profiles (la-entity-profile-plan lane A, 2026-10-15; la-api `EntityProfileRoutes` + `EntityProfiles`).**
`GET /inv/entity-types` (types, bindings, cached headline; never a query), `GET /inv/entity-types/{id}/profile?sections=`
(aggregates only) and `GET /inv/entities/{type}/{key}/profile`, shapes fixed by plan §4.5. Seams and gotchas:
* **Bindings** (`EntityTypeBindings`): a type binds every viewable Dataset column whose `classification` it claims; `role`
  is source/target when a saved `link-analysis-view` (`query.projection`, `projections[]`, `multi.edges[]`, read through the
  new `DatasetProvider.linkAnalysisViews` port) or a published index manifest names the column as a link end. Time column =
  the Dataset's `eventDate`, else an index mapping's `timeColumn`.
* **One Entity = the normalised key, counted in DuckDB** through `EntityKeySql`, the SQL mirror of `EntityTypes.normalise`;
  `EntityKeySqlTest` runs the shared normaliser parity fixture through it (sharp s needed an explicit fold: DuckDB's `upper`
  keeps it). Every readable binding joins ONE `UNION ALL` relation, so distinct counts and the timeline span Datasets.
* **Shared column profile** (`ColumnProfile`): the overlap profile's former private `ColumnStats`, now also the per-Dataset
  pass of the bindings section.
* **Reads run in UTC** - DuckDB knows `UTC`, not the `Z` that `ZoneOffset.UTC` names (a `Z` zone fails every time read).
* **Cache** keyed by every bound Dataset's `files:` input fingerprint (`InputFingerprintCache`, so a new File is seen within
  its TTL); a view-backed or `no-files` Dataset is never cached. The headline is kept per caller view of the bindings.
* **Links** come from `LinkStatsSource`s in order; only `DatasetLinkStats` (`from:"dataset"`) exists until the index manifest
  carries link statistics (plan §4.2). Limits: the `entity_profile.*` items of the catalogue.
* **A raw identifier never travels in a URL** (operator 2026-10-15): for a type masked under the Space's mode the Entity
  route takes ONLY the alias and refuses a raw value 422 before reading anything, without repeating it; `POST
  /inv/entities/{type}/resolve {key}` (raw value or alias, in the body) answers the alias of the normalised key - the same alias
  the exploration reads mint - or, for an unmasked type, the normalised value. The envelope's `links.self` echoes the request
  path, which is why the rule is enforced at the route.
* **The Start box's type-ahead** (LA-ONBOARD-1 phase 6, OB-9; `reveal` [architecture-data-onboarding.md](architecture-data-onboarding.md) §6.12): `POST /inv/entities/{type}/suggest {prefix?, dataset?, limit?, reveal?}`
  runs over the same union relation, restricted to the type's LINK-END bindings (`source` / `target`; attribute columns never
  start a graph) and optionally one Dataset: values whose raw spelling or normalised key begins with the prefix, busiest
  first, with their link rows and first / last seen, plus `distinct` (Entities matching; a value that normalises to nothing
  is no Entity). A masked type lists nothing, and is counted only for an EMPTY prefix - a count per prefix would spell a raw
  value out character by character. The prefix travels in the body; the read is a `read-shaped` exemption like resolve.
* **Delete if unused** (LA-ENTITY-TYPE-RETIRE-1, operator decision 2026-11-01; no Retire state): `DELETE /inv/entity-types/{id}`,
  gated `canAuthorWorkbench` (the capability that states the types through `PUT /settings/link-analysis`) → 503 write root →
  404 not in force → 409 while anything uses it or for the last type → `LinkAnalysisSettings.deleteEntityType`, a
  read-modify-write under `LaLimits.WRITE_LOCK` that states the types in force minus it (deleting an inherited default states
  the rest, as the Industry Pack labels them in force - so a later Domain Profile switch no longer relabels them) and keeps
  every other key. **What counts as a use** (`EntityProfileRoutes.uses`, recorded decision): a column of
  ANY registered Dataset whose classification the type claims - every Dataset, not the caller's R3 view; a column the caller
  cannot view is counted, never named; a **live** Entity List of the type (a retired list can seed nothing again, so it does
  not block); an Investigation whose header (its endpoint types), main log or an open Draft's log names the id under an
  `entityType` / `sourceType` / `targetType` key anywhere in an entry (seed, Seed by list, expand, connect), undone steps
  included because replay reads every entry; matched in any case, as masking reads a sealed type (a seed keeps the type as
  sent). Investigation Templates are not checked.
  Not a lock across stores: a use created between the check and the write is not caught. `PUT /settings/link-analysis` can
  still drop a type from the stated list without this check (that is how New Entity Type writes); the SPA deletes only
  through the route. Audited `LINK_ENTITY_TYPE_DELETED`; rate class expensive (it reads every Investigation's log).
  Test: `ControlApiEntityTypeDeleteTest` (falsified: without the use check the three 409 probes answer 200).
Audited as `LINK_ENTITY_TYPES_LISTED`, `LINK_ENTITY_TYPE_PROFILED`, `LINK_ENTITY_PROFILED`, `LINK_ENTITY_KEY_RESOLVED`,
`LINK_ENTITY_SUGGESTED` (never a key, a prefix or a value). Rate class: the list exempt; both profiles, resolve and suggest expensive.

**Start from... (LA-ONBOARD-1 phase 6, as built 2026-10-22).** The SPA's pure half is `link-analysis/start-from.ts`
(`startMapping` precedence: `defaultLinkMapping` > a Domain Profile `investigate` mapping over the Dataset > the first saved
single-mapping view > the classified `source` / `target` columns; `startCatalogue` over the Datasets, views and
`GET /inv/entity-types`; with the registry unreadable only the primary types start). `LaDataset` (and the console's `Dataset`,
read-only) carry `primaryEntityType` and `defaultLinkMapping`. An explorer start is `drawStart`: one neighbours read per value
(at most 25, five at a time) merged into a NEW graph, the Grow preview's `held` set as the basic held items, the values marked
expanded, and the run remembered over the mapping WITHOUT a filter, so Grow continues from the ring. One entity on the
Investigations level goes through `prepareInvestigation`: the Domain Profile binding wins (override), else `genericBinding`
(the start Dataset's mapping typed by the seed, server-default rung). **F-8 root cause** (traced 2026-10-22): an Entity
profile's *Investigate* navigates `?seed=<key>&entityType=<type>&dataset=<where[0]>`; on the Explorer level
`prepareInvestigation` returned at once with "Investigations are available in Standard and above…", queued nothing, ignored
`dataset`, and the stream dropped the three params; the empty state's only action was *Choose a Dataset* →
`exploreDataset()` → the Query panel's guided picker, whose pick ran an unseeded `POST /inv/projection` of the whole
Dataset. The seed never reached any read. On Standard an `account` fared no better: no profile investigates it, so the
same picker drew the whole Dataset with the seed merely queued. Both now draw / bind from the entity (spec
`link-analysis.component.spec.ts` "F-8: …", falsified against the old branch). `?dataset=<id>` alone (Add data's *Explore in
Link Analysis*, phase 4) preselects the Dataset in the Start box when it can start a graph, else picks it in the Query panel
as *Explore a Dataset* does.
