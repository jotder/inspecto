---
type: Concept
title: Link Analysis — HTTP surface and Investigation UI — Gate order, embeds, entity profiles and inbound integration
description: Part 1 of 4 of Link Analysis — HTTP surface and Investigation UI: Gate order for an /inv request; Embed links (LA-EMBED-VIEW-1: API S1-S3 built 2026-10-12; the SPA shell S4 and the frame headers S5 built 2026-10-13); Entity profile routes (as built 2026-10-15); Third-party integration (inbound) (as built 2026-10-17, LA-EDITIONS-1 phase 5)
tags: [module, la-core, link-analysis, architecture, http, investigation, spa]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — HTTP surface and Investigation UI — Gate order, embeds, entity profiles and inbound integration

Part 1 of 4 of [Link Analysis — HTTP surface and Investigation UI](architecture-http-and-ui.md) · next: [Outbound integration, CSV upload, masking and exclusion lists](architecture-http-and-ui-integration-uploads.md).

## 6.1 Gate order for an `/inv` request

1. `ControlApi.authenticate` → Subject (401 audited).
2. `rateLimit` → `LinkAnalysisRateClasses`: the expensive routes (the `EXPENSIVE` table in `LinkAnalysisRateClasses`, about twenty entries; the earlier "12 POSTs" figure was stale) share a per-subject `linkAnalysis` bucket (capacity 30,
   refill 1 per 2 s; install limits `rate.link_analysis_burst` / `_per_s`, else `control.rateLimit.linkAnalysis.*`; 429); Investigation routes are exempt (pinned by `LinkAnalysisRateClassCoverageTest`).
3. `authorize` → host ABAC PEP.
4. `requireModuleEnabled` → feature `geoLink` in the Space's `modules.toon` (404 `MODULE_DISABLED`); then the host's
   idempotency check, before the handler. ⚠ The gate only covers a route whose `RouteModule` overrides
   `featureIds()` → `geoLink`; all 34 LA route-serving modules (32 in `la-api` + geo-link's
   `InvestigationMeasureRoutes` and `InvestigationDataChangesRoutes`) do (recounted 2026-11-15) — a new LA route module
   must too (pinned by `ControlApiGeoLinkModuleGateTest`).
5. `ApiContext.withCapability` → 403. ⚠ A no-op when there is no Subject (Basic / tests without an armed
   Authenticator).
6. `InvestigationRoutes.open`: write root (503) → safe id (422) → header (404) → membership role (`Need`
   READ / LEAD / APPROVE; a member of the linked Case also passes READ) → R3 Dataset visibility (`ComponentAccess.canView`, 404) → Advanced PDP D-E7
   (`RowScope.visible`, can only narrow, 404) → deferred role refusal 403. **Non-members get 404-as-absence; a
   member lacking the role gets 403 only after R3 and the PDP have spoken.**
7. Handler → `EntityMasking` on output → audit (`LinkEventTypes` via `EventSink` + `AuditTrail`; ≥500 and 401/403
   recorded by `ControlApi`).

## 6.2 Embed links (LA-EMBED-VIEW-1: API S1-S3 built 2026-10-12; the SPA shell S4 and the frame headers S5 built 2026-10-13)

A **lead** mints a read-only URL to ONE Investigation for someone with no login (operator decisions D1-D12, 2026-10-12).
Code: `EmbedGrants` (token, hashing, resolve, revoke, counters), `InvestigationEmbedRoutes` (mint / list / revoke),
`PublicInvestigationRoutes` (the public GET), grant records behind `InvestigationStore` (`createEmbedGrant`, `embedGrant`,
`replaceEmbedGrant`, `embedGrants`; the file implementation only - another backend answers 503 until it implements the
`EmbedGrantStoreContract`).

* **Token** `<grantId>.<secret>`: grantId 16 URL-safe chars (the public half, names the record), secret 256 random bits; only the
  SHA-256 of the whole token is stored and compared in constant time; shown once at mint; never in a list, a log line or an audit row.
* **Storage is a sidecar, never the op log**: `investigations/<id>/embeds/<grantId>.json` plus a one-line pointer
  `embed-grants/<grantId>` (the lookup starts from a stranger's grant id, which `EMBED_GRANT_ID` keeps from ever becoming a path).
  Minting, using and revoking never move the Dossier root or any sealed byte (pinned by test).
* **The holder is not a Subject.** `EmbedGrant` (auth-spi) is stamped as `ATTR_EMBED_GRANT` by the one public route; no capability gate
  accepts it, and a session sent on the public path is dropped. `PublicPaths` (auth-spi) is the registry that replaces the hard-coded
  `startsWith` in `ControlApi.isSelfVerifyingPublic`: the module registers `/public/investigations/` when its routes register.
* **Redaction.** `PublicPaths.redact` keeps the token out of audit rows (`AuditTrail`), ERROR/debug log lines, the static-fallback log and
  `links.self` in the envelope. `ControlApi`'s per-token rate bucket is keyed by a hash of the path, never the token.
* **One answer.** Disabled, malformed, unknown, tampered, expired, revoked, deleted, minter-not-lead, Case closed and masking loosened are
  the same `404 NOT_FOUND "not found"`; the reason goes to the `LINK_EMBED_DENIED` audit row with an 8-hex hash prefix. (A Space with `geoLink`
  disabled answers `MODULE_DISABLED` like every LA route: that reveals a module setting, not a token.)
* **Masking follows the Space** (`maskingMode`, D3): the view is `InvestigationRoutes.masked` over the evaluated sealed log - one masking
  path. A link auto-revokes if the Space LOOSENS masking after the mint (a stricter mode never revokes). Reveal, pending expands, drafts,
  the Case, Dataset names and SQL are not in the view.
* **Expiry** (D4): default 72 h, default cap 30 d (720 h), `embed.default_ttl_hours` / `embed.max_ttl_hours` in `link-analysis.toon`; a request
  above the Space's cap is 422, never clamped; a Space admin may set `max_ttl_hours` anywhere from 1 h up to the validation ceiling of 8760 h (365 d; 422 above in the Settings API), and `default_ttl_hours` may not exceed it. `embed.refuse_classifications` refuses a mint (422) when the
  strictest classification of the Investigation's bound columns is listed.
* **Expiry sweep** (LA-EMBED-EXPIRY-SWEEP-1): `EmbedExpirySweepModule` (inspecto-geo-link, a route-less `RouteModule`) runs a daemon timer every 10 minutes over every Space whose `geoLink` module is on and calls `EmbedExpirySweep.sweep`, which drops the token hash of every expired grant (row and audit trail stay); `-Dla.embed.enabled` off = it does nothing; a failing Investigation or Space never stops the tick. Mint and list still purge on their own.
* **Who** (D6/D11): `canShareInvestigation` AND a lead of the Investigation; the Advanced PDP is asked the new ABAC action `embed`
  (`RowScope.allowed`, resource carries `classification`); `ControlApi.actionFor` maps both routes to `embed`. `-Dla.embed.enabled` (default
  off) is the kill switch: mint 503, every public request the uniform 404. At most 20 live links per Investigation.
* **Auto-revoke** (S3): the minter's membership revoke flags their links at once; every public read re-checks that the minter is still a
  lead, the linked Case is open and masking is not looser, and flags the grant when not. Expired grants lose their hash on the next mint or
  list (the row stays for the audit). An Investigation has no close status (`DELETE /inv/investigations/{id}` removes it with its embeds, and refuses 409 while its Case is open), so "closed" means its linked Case closed.
* **Scope and per-link masking** (S4): a mint body may carry `scope` (`working_set` | `with_notes` | `full`, default `full`; the Working Set is always shown) and `masking_mode`
  (`none|typed|all`, default the Space's; a mode LOOSER than the Space's is 422, so a link can only be stricter). Both are stored on the grant (`scope`, `maskingMode`), listed in the summary, and the
  view masks at `EntityMasking.stricter(Space now, link)`. The auto-revoke check still compares only the Space's mode with `maskingModeAtMint`, so a stricter Space never kills a link and a
  link stricter than its Space is not revoked by the Space staying loose. A grant minted before scopes existed reads as `full` and its Space's mode.
* **Frame headers** (S5, `FrameHeaders` in `inspecto/control`): (1) `-Dcontrol.frameDeny=true` (default OFF, read per request) puts `Content-Security-Policy: frame-ancestors 'none'` + `X-Frame-Options: DENY`
  on EVERY response except the embed surfaces (`/embed/*` the viewer page, `/public/investigations/*` the data route); the Dossier HTML's own CSP also carries `frame-ancestors 'none'`.
  `ControlApiFrameHeadersTest` pins "flag on changes every non-embed response and never an embed one, flag off changes none". (2) The viewer page `/embed/**` is always served by `ControlApi.writeEmbedShell`:
  the SPA `index.html` with every `<link href="https://...">` removed (no font host, not even a preconnect) Angular's inline `onload` stylesheet handler dropped, and every inlined `@font-face` that names another origin dropped (Angular inlines the Google font CSS into the built index; `font-src 'self'` refused each one, so the console was noisy; the viewer now uses system fonts, and the Material Icons ligature font is not loaded - the viewer draws no `mat-icon`), under
  `default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; base-uri 'self'; form-action 'none'; frame-ancestors <list>`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store`, nosniff. `<list>` = `embed.frame_ancestors` of `link-analysis.toon` (Settings page + `PUT .../settings/link-analysis` `embed.frameAncestors`; canonical `https://host[:port]`, `http` only for
  localhost, no wildcard/path/query; a bad stored entry is DROPPED, a bad API entry is 422) - the allow-list of the TOKEN's OWN Space only (2026-10-14: the page path `/embed/inv/<token>` carries the token, so `writeEmbedShell` asks
  `EmbedShellSpaces.spaceOf(token)` in `inspecto-auth-spi`, which `PublicInvestigationRoutes` backs by resolving the token against every attached Space's grants; Space A's list never admits Space B's origin);
  a token that is not a LIVE grant (unknown, wrong secret, revoked, expired, kill switch off) frames for nobody, so a revoked link in a portal iframe is blocked by the browser rather than shown as *not available*; empty = `'none'`. `X-Frame-Options` is sent only when the list is empty (it cannot name an allow-list). The data route stays `frame-ancestors 'none'` always (a framed JSON answer is useless).
  (Decision D2 "link-only" is superseded by the 2026-10-13 operator call that the consumer is a real customer portal.)
* **Headers** on every answer of the public route: `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`,
  `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, no CORS, no cookie (D2: link-only, no per-grant
  allowlist). Throttled per token and per IP (`control.rateLimit.embed.*`, burst 60 then 2/s per link; the IP bucket is the callback one).
* **Gotchas.** The host refuses to start with a mutating handler on the public path (CapabilityManifest "undeclared mutating route"), so
  GET-only is structural. A grant id differing only by case aliases a file on a case-insensitive filesystem; the full token hash still decides.
  Audit events: `LINK_EMBED_MINTED | REVOKED | VIEWED | DENIED` (never the token, the label or an entity id).
* **Audit rows, pinned and live-driven (2026-10-14).** Each successful read writes one `LINK_EMBED_VIEWED` (`actor` `embed:<grantId>`, `investigationId`, `grantId`, `issuedBy`, `step`, `uaHash`); each refusal one `LINK_EMBED_DENIED` (`reason`, `presentedHash`, and `grantId` / `investigationId` when the token names a known grant - revoked, expired, purged, minter-not-lead, masking loosened); an auto-revoke also writes `LINK_EMBED_REVOKED` (`revokedBy` `system`, the same `reason`). None carries the token, the label or an entity value. ⚠ The hash key is `presentedHash`, NOT `tokenHash`: the audit sink redacts any attribute whose key contains `token`, so the old name was always `***redacted***`. ⚠ The public path names no Space, so the host binds none; `PublicInvestigationRoutes` binds the grant's own Space (`ApiContext.spaceIdOf`) once the token resolves, or these rows reach only the in-memory global log and no Space's event store (refusals of an UNKNOWN or MALFORMED token have no Space and stay global). Pinned by `ControlApiInvestigationEmbedTest` (field by field, minter-not-lead and masking-loosened end REVOKED by `system`) and `ControlApiEmbedMultiSpaceTest` (rows land in the grant's Space only). Live (Advanced demo bundle, two Spaces with different `embed.frame_ancestors`): the viewer page is framed by the token's OWN Space only (`frame-ancestors https://portal-a.example` vs `portal-b`, `none` for a revoked or forged token, no `X-Frame-Options` on a live viewer page), the console is clean with no third-party request, masking is as minted (`all`), loosening the Space to `none` revoked the `all`-minted link on its next read (`MASKING_LOOSENED`, `system`) and the token is in no host log line. A revoked link is a 404.

## 6.3 Entity profile routes (as built 2026-10-15)

Plan archived: [`la-entity-profile-plan`](../../../../archived-documents/plans-archive/la-entity-profile-plan.md) (decisions D-EP1..D-EP8, contract §4.5, live pass §10).

* **Routes** (la-api, gated and audited like the exploration reads): on `EntityProfileRoutes`, every edition, `GET /inv/entity-types`
  (cheap, reads the cache only) and `POST /inv/entities/{type}/resolve {key}`; on `EntityPageRoutes`, Standard and up (`entityPages`,
  D-LAE4), `GET /inv/entity-types/{id}/profile?sections=` (bindings, volume, shapes, time, lineage, links, relationships,
  measures - [architecture-data-onboarding.md](architecture-data-onboarding.md) §6.15) and
  `GET /inv/entities/{type}/{key}/profile`. Audit events `LINK_ENTITY_*` (three).
* **A raw identifier never travels in a URL** (the rule, the resolve POST and the type-ahead: [architecture-la-core.md](architecture-la-core-masking.md#34-masking)
  §3.4 *Entity profiles*). Type pages are aggregates only (D-EP3): a dominant value is reported as
  `kind:"dominant"`, `value:null`; only the built-in sentinels (`''`, `-99`, `0`, `NULL`, `null`, `unknown`, `n/a`) are shown by value.
  **Dominance counts a value the normaliser rejects by its raw (trimmed) text (2026-10-18)**, so a name filling a number column still
  raises the sentinel (seen on the IMS Space); built-in sentinels and blanks never dominate. The SPA never rounds a share short of
  all up to 100 % (`percent()` caps it at 99.9 %: a 99.95 % one-to-one share sat beside a maximum of 15 per Entity).
* **Bindings** (`EntityTypeBindings`, one Entity = the normalised typed key `EntityKeySql`, D-EP5: [architecture-la-core.md](architecture-la-core-masking.md#34-masking)
  §3.4 *Entity profiles*); a column no link end names has `role` `attribute`.
  **Parties (2026-10-16):** a Dataset column may carry `party` (free text, trimmed, lower-case; the binding shows it). Relationships
  and an Entity's related identifiers pair two columns of a row only when their parties match or either has none, so a served IMSI
  pairs with the served MSISDN and not with the other party's number (that pairing counts contacts, and on a 132 M-row day it ran
  past the 600 s timeout even sampled). A Dataset that names no party pairs every column as before.
* **Statistics** come from one `ColumnProfile` aggregate per Dataset; every approximate distinct is capped at its row count (the sketch overshoots
  on small counts: 109 distinct over 104 rows before the cap). Links read the index first (`IndexLinkStats` over the manifest's `kindEdges`,
  `typeNodes`, `degree`, `timeSpan`), else a Dataset query under the budget, else `assessed:false` (D-EP4). Every section carries `assessed` /
  `reason`; nothing unmeasured is reported as zero.
* **Cache** keyed by the input-file fingerprint (D-EP2); a view-backed Dataset has none and is read each time under `entity_profile.timeout_s`.
  Cold read 2.2-5.4 s on the IMS Space (29,650 rows), cached about 200 ms. **A section whose read FAILED is answered but never cached**
  (`assessed:false` plus `retryable:true`; a section that does not apply has no `retryable`), so the next read retries it.
* **Production scale (2026-10-16, a 132 M-row day).** The profile used to run under the 1 GB sandbox default and its dominant-value and
  relationship reads ran out of memory, then the failure was cached. Now: (1) profile SQL is trusted, so it runs under its own install-wide
  budget `entity_profile.memory_limit_mb` (default 4,096); (2) above `shape_sample_rows` the dominant value is read from a ROW reservoir
  (`dominantSampled`, `sampleRows`; a row sample, not an Entity sample, because a value holding a fifth of a column is certain to be in it);
  (3) a relationship read that still runs out of memory, or runs past `entity_profile.timeout_s` (MSISDN to IMSI over the
  132 M-row day exceeded even the 600 s ceiling), is retried over an Entity HASH sample, 1 in 64 (`sampled`, `sampleEvery`,
  `approximate`: each sampled Entity's count is exact, the counts are scaled up); (4) an out-of-memory reason reads "ran out of its memory
  budget (N MiB) ... raise entity_profile.memory_limit_mb", never the engine's text. Measured on the IMS day with the sandbox settings: at
  1 GB the dominant-value and relationship reads fail; at 4 GB they take 58 s and 149 s; hash-sampled at 1 GB 41 s and 123 s.
  Pinned by `ControlApiEntityProfileScaleTest`.
* **Limits** (Space layer, server-gated): `entity_profile.timeout_s` 30, `.max_datasets` 20, `.shape_sample_rows` 1,000,000, `.top_n` 20,
  `.sentinel_share` 20 (%), `.timeline_days` 400, `.relationship_threshold` 6, `.measure_sample_entities` 100,000;
  install layer (system admin, `la-limits.toon`):
  `entity_profile.memory_limit_mb` 4,096.
* **Data as of** reads `GET /datasets/{id}/freshness`, a read gate only (D-EP7).
* **Not built (retired to BACKLOG §6, operator 2026-10-28):** P4 — no scheduled `la.entity.profile` Job, profile Dataset, drift
  Alert Rules or 30-day history, so a cold profile still reads the whole Dataset several times (about 10 minutes on a 132 M-row day at
  2 threads). Reopen when a customer needs profile drift alerts or fast profiles on very large days. `LA-ENTITY-PROFILE-RESIDUALS-1` is closed.

## 6.4 Third-party integration (inbound) (as built 2026-10-17, LA-EDITIONS-1 phase 5)

Plan: [`inspecto-la-editions-plan`](../../../../archived-documents/plans-archive/inspecto-la-editions-plan.md) §4. Standard and above (the Investigation
surface). Outbound (case hand-off, webhooks, client-credentials trust) is a separate lane.

* **Routes** (`InvestigationInboundRoutes`, la-api, geoLink feature):
  * `POST /inv/inbound/investigations` — gated on `canOpenInboundInvestigation` (was `canManageIncidents` until 2026-11-01). A fraud product or case manager hands over its own record
    and the entities it is about; Inspecto LA opens an Investigation seeded with them and answers its id and a link.
  * `POST /inv/deep-links/resolve` — read-shaped (CapabilityManifest exemption): validates an "Open in Inspecto LA" link for the
    SPA's `/open` page.
  * Both are rate-classed **expensive** (`LinkAnalysisRateClasses`): a flood from a calling product is throttled per Subject (429).
    The inbound call is idempotent, so a retry is safe.
* **Composition, not a new write path.** The inbound route calls the existing writes through their own gates:
  `InvestigationRoutes.create` (purpose required, R3 Dataset gate, column checks), `appendOpOn` (one `seed` op per Entity Type,
  then an optional `window` op) and `InvestigationReferenceRoutes.add` (the record becomes an external reference: `trusted:false`,
  never fetched, grants nothing). Every audit event is the one those writes already emit.
* **Entities are typed** `"<type>:<key>"` by an Entity Type in force. The key is a raw value (in the BODY) or an exploration alias,
  normalised by the type's normaliser; the seed carries the normalised key, so the bound columns should hold normalised values.
* **Binding.** `binding: {dataset, sourceCol, targetCol, linkKindCol?, timeCol?, timeColZone?, eventsCol?}` when the caller knows it;
  otherwise the first link mapping the Space knows (a saved Link Analysis view, then a published index, the order `EntityTypeBindings`
  reads them) whose link end is bound to the FIRST entity's type, with its kind column, its time column (or the Dataset's `eventDate`)
  and the endpoint types. None → 422 asking for a binding. A `window` needs a time column (422 otherwise).
* **Idempotent on `(reference.system, reference.id)` without a lock.** The Investigation id is derived:
  `ext-<first 32 hex of sha256(system + "\n" + id)>`. The store creates a header CREATE_NEW, so two racing calls cannot both create,
  on any node or store backend; the loser reads as a repeat. A repeat answers the same id with `created:false`, never re-derives the
  binding, and adds nothing; if the first call died between steps, the repeat seeds or labels what is missing. A repeat by a Subject
  who cannot change that Investigation (not its lead, R3, PDP) is **409** without the id.
* **Auth.** Gate `canOpenInboundInvestigation` (seed role `integration`; was `canManageIncidents` until 2026-11-01). The caller
  becomes the lead and reads the Investigation through the member rule. A machine (client-credentials) Subject is admitted only in a
  Space whose `roles.toon` lists its client in `identity.machineClients` and keeps only that capability (`OIDC-CC-INTEGRATION-ROLE-1`,
  decisions A1 + B1 + C2 operator-decided 2026-11-01; [oidc review](../oidc/tests-security-review-oidc-client-credentials.md)).
* **Wire contract (inbound).**

  ```http
  POST /api/v1/inv/inbound/investigations
  Authorization: Bearer <token of a Subject holding canManageIncidents>

  {"reference": {"system": "fraudpro", "type": "alert", "id": "AL-1001", "url": "https://fraud.example/alerts/AL-1001"},
   "entities": ["msisdn:+249912000001", "imei:350000000000011"],
   "purpose": "Fraud alert triage", "title": "Alert AL-1001",
   "window": {"from": "2026-10-01T00:00:00Z", "to": "2026-10-04T00:00:00Z"}}

  200 {"data": {"investigationId": "ext-<32 hex>", "created": true, "seeded": 2,
                "reference": {"system": "fraudpro", "type": "alert", "id": "AL-1001"},
                "link": "/open?investigation=ext-<32 hex>", "note": "opened"}}
  ```

  `reference.type` defaults to `alert` (the calling system's word; not an Inspecto **Alert**). The response never echoes an entity.
* **Deep link "Open in Inspecto LA"** (roadmap I5). `<la-app>/open?entity=<type>:<key>[&from=<instant>&to=<instant>][&investigation=<id>]`;
  the console serves the same page at `/studio/open`. At least one of `entity` / `investigation`; any other parameter is refused. For a
  type masked under the Space's `maskingMode` the key must be the alias (`masked:<16 hex>`, from `POST /inv/entities/{type}/resolve`):
  a raw value is refused 422 and never repeated. The page posts the parameters to `/inv/deep-links/resolve`, which answers
  `{entity?: {type, key, masked}, investigation?: {id, title}, window?}`; an Investigation the caller cannot read is the 404 of absence.
  `reveal:true` returns a masked entity's raw value only to a holder of `canRevealLinkEntities` (403 otherwise), audited as
  `LINK_ENTITY_REVEALED` with `via: deep-link` naming the alias as `alias` and as the row's target (never as `tokens`, which the
  event sink's `SecretScrubber` redacts; LA-DEEPLINK-REVEAL-AUDIT-1, 2026-11-16). Aliases are resolved from the Space's alias book ([architecture-la-core.md](architecture-la-core-masking.md#34-masking) §3.4), which is
  persisted beside the Space key, so a deep link still works after a restart (2026-10-19).
* **Tests:** `ControlApiInvestigationInboundTest` (geo-link, armed Authenticator: seeding and window, idempotency incl. other system /
  id, invalid entity / reference / window / binding 422 with nothing written, 403 / 401 / 409, 503, the deep-link masking and reveal
  rules, the deep-link reveal's audit row naming the alias after the scrubber while a real secret is still redacted, Investigation
  404), `InvestigationInboundRoutesContractTest` (TCK). SPA: `open-link.spec.ts`, `open-in-la.component.spec.ts` (axe).
* **As built 2026-10-20 (`a6866a45f`):** the `window` of a link to an Entity applies. The Open page forwards `?from=&to=` to the Entity
  page, which passes them to `GET /inv/entities/{type}/{key}/profile?from=&to=` (ISO-8601 instants with a zone, half-open `[from, to)`,
  UTC; 422 for a malformed instant or `from >= to`). Inside the window the server reads `where`, `firstSeen`, `lastSeen`, `activeDays`,
  `coveredDays` and `perDay`, and echoes `window {from, to}`; `links` and `related` stay whole-history (the page says so, with a *Show the
  whole history* link). A mixed-type inbound call binds through ONE link mapping that links a column of EVERY type it names; when no
  single mapping does, it is refused 422 naming the types (an Investigation has one mapping), unless the caller sends an explicit
  `binding`. The window bounds are 0001-01-01..9999-12-31 (422 outside). A deep link carries only `from` / `to`: an Investigation window's other settings (slots, day or timezone rules) stay on the Investigation and are not carried to the Entity page. A mapping covering all types is preferred over the first one that covers the first type.
