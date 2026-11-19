---
type: Reference
title: Link Analysis — as-built reference and design records — External references, the Dossier and the separation records
description: Part 5 of 6 of Link Analysis — as-built reference and design records: External references and the Dossier bundle; SPA separation record (D-5); Integration design record (D-6); Platform-layer record (D-1)
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — as-built reference and design records — External references, the Dossier and the separation records

Part 5 of 6 of [Link Analysis — as-built reference and design records](architecture-records.md) · previous: [Studio UI record and the link index](architecture-records-studio-index.md) · next: [Closed-plan record and consolidated as-built facts](architecture-records-closed-plans.md).

## External references and the Dossier bundle

*D-6 / D-7, as built 2026-10-03.* Integration by reference, never by trust; decisions in the
[Integration design record](#integration-design-record-d-6).

* **External references.** `GET` / `POST /inv/investigations/{id}/references`, body `{system, type, id, url?, label?}`,
  append-only `references.jsonl` beside the op log (`SnapshotStore.appendReference`), OUTSIDE the header and the Dossier
  manifest. No edit or delete; a duplicate `(system, type, id)` and the 201st are 409. `POST` is `canManageIncidents` and
  lead; `GET` the read gate. 🔴 **Never trusted:** `trusted:false`, never dereferenced or read by the analysis, grants no
  access (only `PUT …/case` shares). `url` absolute `http`/`https`, a host, no credentials, else 422. Audited
  `LINK_INVESTIGATION_REFERENCE_ADDED`.
* **Investigation members** (D7-1, D19 amending D-E7; `InvestigationMembers`, `InvestigationMemberStore`,
  `InvestigationMemberRoutes`). Roles and fold: [architecture-la-core.md](architecture-la-core.md) §3.1. The creator is the implicit first
  lead, so an Investigation with NO `members.jsonl` behaves as owner-only (nothing migrated). `GET …/members` (any member),
  `POST …/members {subject, role}` and `POST …/members/revoke {subject}` (lead; `canManageIncidents`; non-lead 403,
  non-member 404); last lead 422; audited `LINK_INV_MEMBER_GRANTED` / `_REVOKED`. **The gate** is `InvestigationRoutes.open*`:
  every member READS (log, Working Set, Dossier and bundle, measures, coverage, references, Case link, replay, Graph Runs,
  the list); only a lead WRITES the main log (ops, undo, reorder, template, alert rules, Case link, references, reveal); a
  Case member reads only, never replays; four-eyes approval is lead or reviewer once a members record exists (any capability
  holder before). R3 and the PDP judge every member and only narrow: the PDP resource carries `members`, a DENY hides the
  Investigation even from a lead and its 403s are withheld behind it.
* **Drafts** (D-7; D7-3 to D7-6, D16-D21; `DraftStore`, `DraftRoutes`; shape and states:
  [architecture-la-core.md](architecture-la-core.md) §3.3). ONE live Draft per member (D17: a second fork is 409 naming the first). The
  writer is the main log's (`SnapshotStore.appendStepAt`); steps CONTINUE the numbering. **Base-state rule:** state = the
  evaluator over main entries 1..k + the Draft's own — nothing copied, so a later main step never changes it (`behind` /
  `stale` report how far main moved); `baseLogHash` fails a Draft CLOSED (409) if the main prefix was rewritten. Routes
  `…/drafts` (POST fork, GET list), `…/drafts/{draftId}` + `/log`, `/working-set` (same cached relation, masking after
  cache), `/replay` (full re-fold incl. persisted set hashes), POST `/ops`, `/undo` (same validation; a Draft undoes only
  its own ops; a four-eyes-sensitive expand is refused 422), `/discard`. Gate: member gate, then a lead or reviewer reads
  another's Draft (D7-Q8), an analyst gets 404; only the ACTOR writes (lead or reviewer 403); the actor or a lead discards.
  **Pins:** fork pins the CURRENT version of every index serving the bound columns (none without an index: reads are sealed
  at use); discard unpins; fork is assembled in a scratch dir and renamed in. The rename (`DraftStore.moveRetrying`) retries
  a Windows `AccessDeniedException` up to 20 times (5-80 ms backoff; an antivirus / indexer handle on a fresh `header.json`
  blocks a directory `ATOMIC_MOVE` — 4 of 50 bench forks hit it), then **503 `STORE_BUSY`** leaving nothing. **Discard**
  keeps `header.json` + `discarded.json` and DELETES log and sets (sealed rows may hold personal data); per-step audit
  events keep the ops. No Dossier, bundle or evidence route for a Draft (D20). Audit `LINK_DRAFT_FORKED / _OP_APPENDED /
  _UNDONE / _DISCARDED` (never rows).
  **Rebase and promote** (D7-5): GET `/conflicts` (writes nothing), POST `/rebase` (`{confirm?, expectHead?}`, actor), POST
  `/promote` (`{expectHead?}`, actor or lead). Rebase replays EFFECTIVE ops (undone ops compacted, renumbered M+1..) over the
  current head, re-seals each expand against freshly pinned versions and swaps the Draft in (`replaceRebased`, two renames,
  old restored on failure). Each rebased state is serialised once (canonical bytes give both `workingSetHash` and the set
  file); the fold runs before the main lock (`DraftRebase.verify`; the lock re-checks byte-identical): 800 steps 36.7 s →
  8.4 s (`LA-DRAFT-REBASE-COST-1`, `DraftRebaseCostTest`). A linear rebase was declined 2026-10-04 (the cost is the
  whole-state sealed hash; a chained hash would change every sealed hash and the promote byte pin). Ops are classed
  `no-op` / `changed` / `superseded` / `blocked`; the last two must be named in `confirm` (D7-Q7), else 409. A Draft's
  expand reads ITS pinned version (no longer published → flat Dataset); a pin past 30 days refuses ops and promote with 409
  "must rebase"; a rebase re-pins. Promote needs base == main head and an undo-free Draft, re-verifies under main then Draft
  lock, appends each step with `draft{id, actor, baseStep, step, promotedBy}` provenance, rolls main back on any failure,
  then closes the Draft. Each promoted step reuses the Draft's sealed `workingSetHash` and hard-links its set file, reused
  only when its head carries that hash and step AND its bytes SHA-256 to it; otherwise re-sealed from the fold, whose final
  hash must equal the Draft's (800 steps 34.9 s → 3.3 s warm / 8.3 s cold; linear in set bytes; `LA-DRAFT-PROMOTE-COST-1`,
  `DraftPromoteCostTest`). A promote carrying an expand sensitive under thresholds NOW in force is held (202) on the
  four-eyes queue. Audit `LINK_DRAFT_REBASED / _PROMOTED`.
  **Checkpointed append** (D7-4): `DraftCheckpoints` keeps the last State per Draft (valid while `log.jsonl` keeps size +
  mtime) so an op is one `copy()` + `apply`; an undo still folds; the `baseLogHash` verdict is cached per main log file;
  `/replay` clears and re-verifies. **Admission, hibernate, expiry** (D7-6): Space cap (51st fork 409; hibernated count),
  heavy jobs (rebase / conflict replay, cold relation build, `expand`) under the heavy permits, full cap = 429 at once.
  Idle → `hibernated.json` (checkpoint and cache released; log, sets, pins kept; next access wakes with one cold fold);
  expiry = a discard with `expired:true`, `LINK_DRAFT_EXPIRED`, `expiryWarning` from 7 days out. The three numbers are
  per-Space `drafts` keys (2026-10-04): `max_open` (1..1000, 50), `hibernate_after_minutes` (1..10080, 60),
  `expire_after_days` (1..3650, 30), wire `drafts{…}` + `draftsInForce`; read per call via
  `LinkAnalysisSettings.forRoot(…).effectiveDrafts()`; PUT refuses (422) out-of-range, unknown keys and an expiry not
  longer than hibernation; a hand-edited unordered pair reads as defaults. Settings **Drafts** group: three fields, blank =
  inherit, the expiry > hibernation rule shown client-side. Idle time = `DraftLifecycle.touch` (memory + `accessed.json`
  every 5 min); the sweep is lazy on list / open / fork. `drafts/index.json` is a rebuildable listing index;
  `DraftStore.recover` restores or deletes `.old-<id>-*` left by a crashed rebase after verifying the header. Detail:
  [`la-separation-d7-design.md`](../../../../archived-documents/plans-archive/la-separation-d7-design.md) §4-§6, §12.
* **Dossier bundle.** `GET …/dossier/bundle?at=&snapshots=` returns `inspecto-dossier-bundle/1`: masked Dossier, masked
  references, `custody {manifestRoot, referencesCount, referencesHash}` and a SHA-256 `seal` over the canonical JSON of
  everything but `seal` and `generatedAt`. `POST …/dossier/bundle/verify` (a bundle, or `{bundle}`) answers `{verified,
  sealIntact, rootMatches, referencesIntact, referencesAddedSince, problems, custody}` — the seal, the embedded root, that
  the bundle's references are still the first N, then the SAME manifest comparison as `/dossier/verify`
  (`DossierRoutes.verifyManifest`). A failure is a result, not an error. Audited `LINK_DOSSIER_EXPORTED` /
  `LINK_DOSSIER_BUNDLE_VERIFIED`.
* **Masking, R3, four-eyes.** Masked as it leaves (references too); manifest and `referencesHash` hash the RAW store, so the
  root is identical masked or not and a masked bundle verifies; the seal covers what shipped. Read gate = the Dossier's. A
  pending sensitive expand is not in the sealed log, so not in the bundle.
* **Gotchas.** Offline-checkable: the seal and the manifest's own root; whether the store still agrees needs the verify
  route. `DossierRoutes` was split (`parseAt`, `parseSnapshotIds`, `maskedDossier`, `verifyManifest`) so Dossier, verify and
  bundle share one build. `POST …/references` is a `CapabilityManifest` entry, the bundle verify a read-shaped exemption;
  all four routes are in `AbsentModuleRoutes` and `openapi-v1.json`. Trust between installations (I1): not built, no live
  call needs it.

## SPA separation record (D-5)

Retired design and edge-classification companion
[archived](../../../../archived-documents/plans-archive/la-separation-d5-design.md); steps 1-7 built. As-built placement:
[Product, code layout and the studio](architecture-records-product-layout.md#product-code-layout-and-the-studio).

**Signed decisions (1-7, operator 2026-10-02).**
1. Shared API clients STAY in core behind `@inspecto/core/*`; only LA-only clients moved (`inv.service`,
   `graph-runs.service`, `link-analysis-settings.service`, `geo.service`, `geo-settings.service`, `notes.service`,
   `inv-identity.service`; 1,678 lines). Moving all of `api/` was rejected: its closure leaves the directory (94 files), and
   the barrel lies (40 LA imports of the barrel close over all 72 clients) — measure the injected set.
2. The canvas, `catalog-graph`, `graph-types`, `graph-source`, `entity-key`, `graph-export`, `unique-name` stay in core (14
   host files use the canvas, 14 dialogs `unique-name`); the library holds algorithms, brush, snapshot, filter, history,
   branching engine and `geo`. Two edges the closure could not see (`MultiNodeMapping` / `MultiEdgeMapping`, `ObjectNote`)
   moved to core.
3. In-workspace libraries via `tsconfig` `paths`, NO ng-packagr (offline-safe, one `npm ci`); boundaries are ESLint rules
   (core bans `@inspecto/link-analysis`).
4. Names `@inspecto/core`, `@inspecto/link-analysis`, apps `gamma` (rename deferred) and `la-app`.
5. The OIDC client moved to core (`inspecto/auth`: `SessionService`, guard, interceptor, `pkce`, sign-in and callback) — one
   PKCE implementation, one security review.
6. The LA flavor is a UI flavor, not an edition: `package.ps1 -Product inspecto|la` (`-Ui` removed by `PRODUCT-ASSEMBLY-1`
   PA-2, 2026-11-10). A fifth edition is the promotion path if a customer needs a bundle without the other modules.
7. One bundle ships exactly one SPA (`ui/`); `package.ps1` copies `inspecto-ui/dist/<Ui>` BY NAME and empties the bundle
   directory first.

**Gotchas worth keeping.**
* **The barrel / MapLibre trap.** `public-api.ts` is deliberately tiny; re-exporting widgets and `MapViewComponent` dragged
  MapLibre (1.2 MB) and the widgets into `main` (1.42 → 1.88 MB) because esbuild keeps a module's side effects. Lazy
  consumers import deep paths. `inspecto-ui/tools/check-bundle-shape.mjs` + `bundle-budget.json` (`ui.yml`, plus
  `angular.json` budgets) pin chunk placement and size.
* **Lint scope must follow a move** or it silently stops: the restricted-import and dynamic-import rules name
  `projects/link-analysis/**` and `@inspecto/core/*` and forbid `SessionService` at `inspecto/auth/session.service`
  (mutation-checked; `la-dynamic-import-lint.test.mjs`, `la-static-import-lint.test.mjs`). The design-token guard `ROOTS`,
  Prettier / typecheck globs gained `projects`; vitest needs `../projects/**/*.spec.ts`; `tailwind.config.js` `content` must
  scan `./projects/**` or library-only classes are purged.
* **Hand-kept mirrors name SPA paths** (9 Java parity tests, `check-vocabulary.mjs` path keys and `SOURCE_GLOBS`, doc
  citations): the six `graph-*-parity` fixtures and `branching-parity.fixture.json` moved with their specs;
  `entity-normaliser-parity.fixture.json` stayed in core (`entity-key.spec` reads it).
* **`la-app` token provision** (`LA_APP_TOKEN_PROVISION`, pinned; a token not provided throws by design, only `LA_FEATURES`
  has a safe default — all off):

  | Token | la-app | Behaviour |
  |---|---|---|
  | `LA_DATASETS` | real | Component registry `dataset` kind, no Studio code |
  | `LA_CASES` | real | core `ObjectsService`; `available` follows the `ops` flag |
  | `LA_TAGS`, `LA_TRANSFER`, `LA_AI_ASSIST` | real | core dialogs / components |
  | `LA_FEATURES` | real | live `SessionService` signals from `/bootstrap` |
  | `LA_WIDGETS`, `LA_CATALOG`, `LA_PIPELINE_GRAPH` | stub, `available: false` | one `console.info` per page load; affordance hidden (`GraphSourcesService.sources` filters, `byId` still resolves) |
  | `LA_DASHBOARD_HEADER` | stub | empty component; the Link view widget is never embedded |

  A STRING asset entry in `angular.json` resolves against the PROJECT root and silently copied nothing for la-app — use
  `{glob, input, output}`. The Entity Lists page must provide the empty `InvestigationSessionStore` (NG0201 found only by
  driving the route).
* **Packaging proof** (2026-10-02): both flavors with `-Edition Standard` exited 0 with the boot smoke; the la-app zip's
  index page is titled `Inspecto Link Analysis`; `check-sbom-modules`, `check-launchers`, `check-bundle-platform` and
  `bundle-modules.mjs` needed no change. The la-app IAM client id (`AUTH_OIDC_CLIENT_ID`; launchers default `inspecto-ui`)
  is a deployment decision in `docs/EDITIONS.md`.
* **la-app over Demo User auth** (driven 2026-10-03, no IAM): `-Dauth.mode=demo`
  ([local testing](../../layer/platform/admin-guide-local-testing.md)) is a token relay behind `/auth/exchange`, NOT an
  OIDC provider (no discovery, authorize or JWKS), so the IdP redirect, `state`/PKCE and ID-token validation are not
  exercised. Proven: guard bounce to `/sign-in`, `POST /auth/exchange` 200, Bearer-scoped calls (401 without / forged),
  reload resumes via `/auth/refresh`, sign-out clears `inspecto.session.resumable`. Not driven: token expiry and the
  401-then-refresh retry (`auth.interceptor.spec.ts`); no la-app browser e2e harness exists (backend half `DemoAuthHttpTest`).
* **Real WSO2** (2026-10-05 / 10-09 / 10-10; closed 2026-10-13 as `LA-APP-REAL-SIGNIN-1`, setup and limits in the
  [admin guide](admin-guide-la-core.md)): gotchas — `groups` must be an access-token attribute; the post-logout URL must be a
  registered callback; `serve.bat` does not wire `tokenEndpoint`/`rolesClaim` from env; the built-in browser cannot pass a
  self-signed certificate page. Fixed 2026-10-10: an unconfigured OIDC build shows "Single sign-on isn't set up" and does not
  redirect; the authorize request carries a one-shot `nonce` that `/auth/exchange` forwards and `OidcTokenRelay` checks
  against the `id_token`; `OidcTokenRelay.bootstrapAuth()` publishes the public client config from
  `-Dauth.oidc.authorizeUrl` (+ `scopes`, `endSessionUrl`, `clientId`), so nothing is baked in at build.
* **Not built (D-5):** the "licence text" item; a fifth `LA` edition; module federation (signed no); a second `-Dui.dir`;
  renaming `gamma`; a mobile or SSR build.

## Integration design record (D-6)

Behaviour: [External references and the Dossier bundle](#external-references-and-the-dossier-bundle); retired design
[archived](../../../../archived-documents/plans-archive/la-separation-d6-design.md). Integration is BY REFERENCE: no
installation trusts another; a live call (and so trust relationship I1) is needed only by a feature that makes one.

* **D6-1** storage: append-only `references.jsonl` under `audit/` (a reserved import path, so an imported bundle cannot
  plant one), not a header field (the header is write-once; a reference is a relationship, not evidence), hence outside the
  manifest; ≤ 200 per Investigation (check-and-append is one locked step); a fork or instantiated template starts with none.
* **D6-2** never trusted: caller text, never dereferenced, never merged into the Working Set or log; `url` restricted
  because `javascript:` / `file:` / `ftp:` would be an XSS or local-read vector.
* **D6-3** gates: `POST` is `canManageIncidents` (Case work, no new capability).
* **D6-4 / D6-5** the bundle is sealed (SHA-256 over canonical JSON, keys sorted, `generatedAt` outside) and verifiable
  (custody: manifest root and a references-prefix hash; later additions reported, a rewritten one fails; re-sealing an edited
  bundle is caught by custody). The export persists nothing, so it takes no capability. Accepted trade-off: a read-only Case
  member can export what they can already read.
* **D6-6** the embeddable view was deferred on purpose (new principal type, CSP / `frame-ancestors` and cookie decisions, a
  read-only shell; a leaked URL hands masked evidence to a holder with no audit identity) — since built as embed links
  (`LA-EMBED-VIEW-1`, 2026-10-12/13): [architecture-http-and-ui.md](architecture-http-and-ui.md) §6.2.
* **D6-7** no trust between installations: a reference is a pointer, a bundle a file, verification local.
* Proof: `ControlApiDossierBundleTest` (real HTTP, armed Authenticator); negatives mutation-checked (seal always true,
  references unmasked, prefix unchecked, POST not lead-only, URL checks removed, `trusted` flipped).

## Platform-layer record (D-1)

*Module carve-out, as built 2026-10-01* (verified at `ab36c6e9c`: full reactor, 41 modules, 7,319 tests). Link Analysis no
longer needs `inspecto-processor`, `-etl` or `-engine`. Design
[archived](../../../../archived-documents/plans-archive/la-separation-d1-design.md); execution order in
[`la-separation-execution-plan.md`](../../../../archived-documents/plans-archive/la-separation-execution-plan.md). Today's
module map: [architecture-la-core.md](architecture-la-core.md) §2.

| Module | Holds |
|---|---|
| `inspecto-audit-spi` | `Event` `EventLog` `EventType` (+ `MetricRegistry`); cuts the old `inspecto-event` → `inspecto-etl` edge (`ParquetEventStore` stays in `inspecto-event`) |
| `inspecto-auth-spi` | the LOWER half of the control contract: `RequestAttrs` `WriteRootProvider` `ApiException` `ErrorCodes` `SpiSlot` `WriteGates` `Subject` `Roles` `ComponentAccess` `RowScope` `AccessDecider(s)` `AccessPolicies` `AuditTrail` `AccessGrants` `Authenticator(s)` `CapabilityManifest` `GeoCountryResolver(s)` + the OIDC helpers |
| `inspecto-http-spi` | the UPPER half: `ApiContext` (extends `WriteRootProvider`) `RouteModule` `Handler` `Envelope` `Idempotency` |
| `inspecto-la-core` | model, evaluator, pattern engine, Snapshot store, `LinkEventTypes`, ports `DatasetProvider` / `CasePort` |
| `inspecto-la-api` | LA routes plus `GraphDossierBuilder` `ValueMeasures` `EntityMasking` |
| `inspecto-geo-link` | the **bridge** (artifactId unchanged): `EngineDatasetProvider`, `HostCasePort`, Alert-Rule-bound `InvestigationMeasureRoutes` / `WorkingSetMeasures` |

**Decisions (operator, 2026-10-01).** (1) `ApiContext` is the SPI; host services (`service()`, `spaces()`, `sseStreams()`)
moved to `HostContext extends ApiContext`, reached by `HostContext.of(ctx)`. (2) Package names kept (`com.gamma.control`,
`com.gamma.event`) — a pure move. (3) The 27 `LINK_*` event types moved to `com.gamma.la.core.LinkEventTypes`, values
unchanged (persisted in the audit trail). (4) Entity List routes went to core (SEP-08); host-free statics in
`inspecto-entity-store` `EntityListFacts`. (5) `la-*` may not reach `inspecto-processor`, `-etl`, `-engine`, `-acquire` —
enforced by a `maven-enforcer` rule in each governed pom AND `tools/check-module-deps.mjs` (`ALLOWED`, mutation-checked). (6)
The HTTP / auth cycle was broken, not merged: auth classes used `ApiContext` only through static request-attribute helpers,
now `RequestAttrs` + a one-method `WriteRootProvider`, with delegating aliases so no call site changed.

**Seams.** Two ports, not three: `AlertPort` was dropped (nothing in `la-*` calls the Alert service; Alert-Rule code is
bridge code). An unbound port reports its feature absent (`DatasetProviders.require()` → `503 CAPABILITY_UNAVAILABLE`;
unbound `CasePort` = "ops not installed"), never half-working; both `*Ports` have `forTestAbsent`. `PendingChanges` stays
host-side.

**Gotchas.** A new module is one more place the hand-kept mirrors must agree (the la-api and geo-link manifests'
`provides.routes`, `CapabilityManifest`, bundle lists): list it in its Offering (`offerings/<edition>.toon`, from which
`bundle-modules.mjs` derives the bundle since PA-1), give it a `CLASSPATH_ORDER` position and add it to the static profile
list in `pom.xml` in the SAME commit. An import closure is a prediction, not proof (a first script treated `/*` in a string
as a comment and undercounted 46 → 20 classes, and missed fully-qualified inline references): re-ground with a string-aware
tokenizer and prove with `mvn clean test-compile` of a clean checkout.

**Deferred.** The split package `com.gamma.control` across `inspecto-http-spi`, `inspecto-auth-spi` and
`inspecto-processor` forbids JPMS; a rename to `com.gamma.spi.*` only if JPMS or a clean public surface is wanted.
