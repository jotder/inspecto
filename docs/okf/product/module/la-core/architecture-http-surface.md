---
type: Concept
title: Link Analysis — HTTP surface
description: The Link Analysis HTTP surface in detail - the gate order of an /inv request, embed links, entity profile routes, third-party integration inbound and outbound, the analyst CSV upload, the masked-alias refusal on identity writes and category exclusion lists (sections 6.1 to 6.8 of the backend architecture).
resource: la/
tags: [module, link-analysis, http, routes, embed, integration, architecture]
timestamp: 2026-11-12T00:00:00Z
---

# Link Analysis — HTTP surface

The Link Analysis HTTP surface in detail: the gate order of an `/inv` request, embed links, entity profile routes,
third-party integration inbound and outbound, the analyst CSV upload, the masked-alias refusal on identity writes and
category exclusion lists. The route table stays in [architecture-backend.md](architecture-backend.md) §6.
Section numbers continue those of [architecture-backend.md](architecture-backend.md), from which this page was split on 2026-11-12.

## 6.1 Gate order for an `/inv` request

1. `ControlApi.authenticate` → Subject (401 audited).
2. `rateLimit` → `LinkAnalysisRateClasses`: the expensive routes (the `EXPENSIVE` table in `LinkAnalysisRateClasses`, about twenty entries; the earlier "12 POSTs" figure was stale) share a per-subject `linkAnalysis` bucket (capacity 30,
   refill 1 per 2 s; install limits `rate.link_analysis_burst` / `_per_s`, else `control.rateLimit.linkAnalysis.*`; 429); Investigation routes are exempt (pinned by `LinkAnalysisRateClassCoverageTest`).
3. `authorize` → host ABAC PEP.
4. `requireModuleEnabled` → feature `geoLink` in the Space's `modules.toon` (404 `MODULE_DISABLED`); then the host's
   idempotency check, before the handler. ⚠ The gate only covers a route whose `RouteModule` overrides
   `featureIds()` → `geoLink`; all 30 LA route-serving modules (28 in `la-api` + geo-link's
   `InvestigationMeasureRoutes` and `InvestigationDataChangesRoutes`) do (counted 2026-11-01) — a new LA route module
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
* **A raw identifier never travels in a URL.** The SPA resolves a typed value with the POST and navigates by the key it returns; for a masked type
  the GET is alias-only (`masked:<16 hex>`) and answers 422 to a raw key. Type pages are aggregates only (D-EP3): a dominant value is reported as
  `kind:"dominant"`, `value:null`; only the built-in sentinels (`''`, `-99`, `0`, `NULL`, `null`, `unknown`, `n/a`) are shown by value.
  **Dominance counts a value the normaliser rejects by its raw (trimmed) text (2026-10-18)**, so a name filling a number column still
  raises the sentinel (seen on the IMS Space); built-in sentinels and blanks never dominate. The SPA never rounds a share short of
  all up to 100 % (`percent()` caps it at 99.9 %: a 99.95 % one-to-one share sat beside a maximum of 15 per Entity).
* **Bindings** (`EntityTypeBindings`): every Dataset column the caller can view whose classification an Entity Type claims; `role` is `source` /
  `target` when a saved view or index mapping names it as a link end, else `attribute`. One Entity is the normalised typed key (`EntityKeySql`, D-EP5).
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
  `LINK_ENTITY_REVEALED` with `via: deep-link` naming the alias. Aliases are resolved from the Space's alias book ([architecture-masking.md](architecture-masking.md) §3.4), which is
  persisted beside the Space key, so a deep link still works after a restart (2026-10-19).
* **Tests:** `ControlApiInvestigationInboundTest` (geo-link, armed Authenticator: seeding and window, idempotency incl. other system /
  id, invalid entity / reference / window / binding 422 with nothing written, 403 / 401 / 409, 503, the deep-link masking and reveal
  rules, Investigation 404), `InvestigationInboundRoutesContractTest` (TCK). SPA: `open-link.spec.ts`, `open-in-la.component.spec.ts` (axe).
* **As built 2026-10-20 (`a6866a45f`):** the `window` of a link to an Entity applies. The Open page forwards `?from=&to=` to the Entity
  page, which passes them to `GET /inv/entities/{type}/{key}/profile?from=&to=` (ISO-8601 instants with a zone, half-open `[from, to)`,
  UTC; 422 for a malformed instant or `from >= to`). Inside the window the server reads `where`, `firstSeen`, `lastSeen`, `activeDays`,
  `coveredDays` and `perDay`, and echoes `window {from, to}`; `links` and `related` stay whole-history (the page says so, with a *Show the
  whole history* link). A mixed-type inbound call binds through ONE link mapping that links a column of EVERY type it names; when no
  single mapping does, it is refused 422 naming the types (an Investigation has one mapping), unless the caller sends an explicit
  `binding`. The window bounds are 0001-01-01..9999-12-31 (422 outside). A deep link carries only `from` / `to`: an Investigation window's other settings (slots, day or timezone rules) stay on the Investigation and are not carried to the Entity page. A mapping covering all types is preferred over the first one that covers the first type.

## 6.5 Third-party integration (outbound) (LA-EDITIONS-1 phase 6, built 2026-10-17)

Standard and above (ED-6). The target products are unknown, so this is ONE generic connector: a case hand-off and
signed event webhooks, configured per Space. No named adapter exists; one is built only on a customer's request.

* **Settings** `la-integration.json` in the Space's config root, JSON (a field-mapping template must round-trip
  exactly), written only by `PUT /inv/integration` and read by `GET /inv/integration` (both `canAdminister`: the
  document decides where evidence leaves the installation). Reserved from every import (`ReservedConfigPaths`). A file
  that is present but unreadable reads as not configured, so nothing leaves. Parsed and validated by `LaIntegration`
  (la-api); a refused PUT writes nothing (422). Audited as `LINK_INTEGRATION_CHANGED` with before and after.
* **No secret in configuration.** The endpoint is a registered `https` **Connection** (onboarded under the admin-only
  `canOnboardConnections`); its token is the Connection's `password`, a `${ENV:…}` / `${SYS:…}` / `${FILE:…}` reference
  that `SecretResolver` resolves at send time. A Connection holding a literal token is refused at send. The webhook
  signing secret is itself a reference; a literal is a 422.
* **The wire is the platform's.** la-api reaches the network only through the la-core port `OutboundPort`
  (`OutboundPorts`, registered by the geo-link bridge's `HostOutboundPort`; this is the I3 "remote Case adapter" seam).
  `HostOutboundPort` composes the same three pieces as the Action Request dispatcher: `WebhookSink.endpoint` (https
  Connection, host, no tunnel or proxy), `EgressPolicy.resolve` against the Space's `EgressAllowlist` before every
  attempt (a refusal sends nothing and is not retried), and the edition's `WebhookSinkTransport.exchange` (pinned
  address, TLS verified against the name, redirects never followed; Basic ships none, so it refuses naming the
  edition). Retries: the Collector `RetryPolicy` with the Space's `retry` block (default 2 retries, exponential from
  1 s, capped 30 s, full jitter), on an I/O failure, a 5xx, 408 or 429, under ONE `Idempotency-Key`.
* **Case hand-off** `POST /inv/investigations/{id}/handoff` (`canManageIncidents`, lead only, `?at=&snapshots=` as the
  bundle export, body `{reveal?}`). Builds the Dossier bundle (`DossierBundleRoutes.build`, so the export is also
  audited as `LINK_DOSSIER_EXPORTED`), renders the template and sends it. Gates: 403 capability, 503 write root, 422 id,
  403/404 lead-only, 422 body, 403 `reveal` without `canRevealLinkEntities`, 409 nothing configured, 503 port absent,
  then 422 refused (nothing sent), 502 failed after the retries, 502 answered without a usable case id, 409 the
  reference list is full. The case id at `caseIdPointer` (and a URL at `caseUrlPointer`, kept only when it is an
  absolute http(s) URL without credentials) is linked as an **external reference** `{system, type: case}`: append-only,
  `trusted:false`, granting nothing (the D-6 model). Re-sending the same evidence answers `linked: already linked`.
  Audited as `LINK_INVESTIGATION_HANDED_OFF` or `LINK_INVESTIGATION_HANDOFF_FAILED`. Rate class EXPENSIVE.
* **Masking.** What leaves follows the Space's `maskingMode` exactly as the bundle export does. A holder of
  `canRevealLinkEntities` may send `{"reveal": true}`: the bundle then carries raw ids and `masking.mode` reads
  `revealed`, naming who lifted it. The payload always carries `masking {mode, masked, basis, note}`.
* **Webhooks** (`IntegrationWebhooks`, geo-link): a process-wide `EventLog` tap (the event bridge the Exchange Signal
  forwarder uses), so Alerts fired off a request thread are seen too. Events: `investigation.created`
  (`LINK_INVESTIGATION_CREATED`), `draft.promoted` (`LINK_DRAFT_PROMOTED`), `alert.fired` (`ALERT_FIRED` carrying an
  `investigation` attribute, which `AlertService` now stamps on an Investigation rule's event) and `approval.needed`
  (`LINK_EXPANSION_REQUESTED`). The notification webhook channel did not fit (one JVM-wide URL, unsigned, no retry).
  Delivery is asynchronous (a virtual thread) through the same `OutboundPort`; each delivery writes
  `LINK_WEBHOOK_DELIVERED` or `LINK_WEBHOOK_FAILED` on the Space's ledger. A webhook carries no entity id, so masking
  has nothing to act on; the payload says so. A signing secret that does not resolve sends nothing.
* **Signature.** `X-Inspecto-Signature: sha256=<hex HMAC-SHA256(secret, X-Inspecto-Timestamp + "." + body)>`;
  `X-Inspecto-Delivery-Id` and `Idempotency-Key` are the source event's id, identical on every retry; `X-Inspecto-Event`
  names the event. A receiver recomputes the HMAC, compares in constant time and refuses a timestamp outside its
  tolerance (replay).
* **Tests:** `ControlApiLaIntegrationTest` (real HTTP, armed Authenticator, a local stub server, targets resolved to
  private addresses by a test resolver and allowlisted): settings gates, a linked hand-off, a 5xx retried then failing
  with nothing linked, an egress-denied target and a literal token refused with nothing sent, masking and reveal, a
  verifying webhook signature, an unresolvable secret sending nothing. `IntegrationWebhooksTest`: event mapping and an
  independent HMAC vector.

Settings example:

```json
{
  "handoff": {
    "connection": "case-manager",
    "system": "case-manager",
    "authHeader": "X-Api-Key",
    "template": {
      "title": "Inspecto LA {{investigation.id}}",
      "description": "{{investigation.purpose}}",
      "evidence": "{{bundle}}",
      "findings": "{{findings}}",
      "masking": "{{masking}}",
      "externalRef": "{{seal}}"
    },
    "caseIdPointer": "/case/id",
    "caseUrlPointer": "/case/links/self"
  },
  "webhooks": {
    "connection": "fraud-hub",
    "signingSecret": "${ENV:LA_WEBHOOK_SECRET}",
    "events": ["investigation.created", "draft.promoted", "alert.fired", "approval.needed"]
  },
  "retry": { "count": 2, "backoff": "EXPONENTIAL", "initialDelayMillis": 1000, "maxDelayMillis": 30000 }
}
```

A template string that is exactly one placeholder takes the value as JSON (`{{bundle}}` stays an object); a placeholder
inside other text is replaced by its text. Placeholders: `investigation.id`, `investigation.purpose`,
`investigation.owner`, `investigation.dataset`, `bundle`, `findings` (`{summary, temporalFindings, comparisons}`),
`masking`, `seal`, `manifestRoot`, `handedOffBy`, `handedOffAt`; any other is a 422. With no template the payload is
`{source: "inspecto-la", kind: "investigation.handoff", investigation{id, purpose, owner, dataset}, findings, masking,
seal, handedOffBy, handedOffAt, bundle}`.

Webhook payload example:

```json
{
  "id": "6f1c0d2e-…",
  "event": "draft.promoted",
  "occurredAt": "2026-10-17T09:12:03.114Z",
  "space": "default",
  "investigationId": "inv-a",
  "actor": "analyst-1",
  "data": { "investigationId": "inv-a", "draftId": "d-2", "fromStep": "4", "toStep": "7", "steps": "3" },
  "masking": "no entity ids are carried; read the Investigation through the API, where the Space's masking mode applies"
}
```

**Machine-to-machine trust (I1).** Reviewed 2026-11-01, verdict enable with conditions; items 2 and 6 below are held by code since
`OIDC-CC-INTEGRATION-ROLE-1` (2026-11-01). Outbound calls carry only the Connection's secret header. The review covered:

1. **SSRF**: every outbound target is a registered `https` Connection under the egress allowlist with a pinned address;
   confirm no inbound route accepts a URL to call back, and that redirects stay unfollowed.
2. **Token scope**: a client-credentials token grants one integration role only (open an Investigation from an alert,
   read one Investigation), never a human capability; audience and issuer pinned; short lifetime; no refresh token.
3. **Replay**: inbound requests carry a nonce or timestamp checked within a tolerance window; outbound webhooks already
   sign the timestamp; idempotency keys are honoured on both sides.
4. **Audit on both sides**: every machine call names the client id as actor; the remote side's case id and our
   delivery id are cross-recorded so either party can reconcile.
5. **Secret handling**: client secrets live only behind `SecretResolver` references; rotation without downtime;
   nothing logged.
6. **Masking**: a machine principal never holds `canRevealLinkEntities` by default.

## 6.6 Analyst CSV upload (LA-ANALYST-UPLOAD-1, as built 2026-10-18)

`UploadRoutes` (la-api) lands raw CSV bytes in the inbox of a Collector and does nothing else: the Collector's Pipeline ingests the file, and the existing identity import and index build then read the Dataset. The decisions are **assistant-decided under operator delegation, pending operator confirmation** (`docs/archived-documents/plans-archive/la-analyst-upload-plan.md`).

- **Target:** only a local poll Collector with `collector.accepts_upload: true` (`ConfigSpecs`, `PipelineConfig.Collector.acceptsUpload`), resolved through `UploadLandingPort` (la-core) by the geo-link bridge `HostUploadLandingPort`. Unknown, not opted in and not local all answer one 404. The analyst never picks the Pipeline, schema, Dataset or path.
- **Capability:** `canUploadData` (default to the roles that hold `canBuildLinkIndex`); rate class EXPENSIVE; Basic edition answers 503 with the other Investigation routes.
- **Gates, in order (cheap refusals before the body is read):** (1) capability 401/403, (2) write root 503, (3) name missing 422, (4) path-shaped name 403, (5) Collector 404, (6) charset, `.csv` and the Collector's include patterns 422, (7) size 413 (`upload.max_bytes`, 25 MiB; a declared length is refused before reading, a chunked body after cap+1 bytes), (8) empty, zip/XLSX magic, NUL or non-UTF-8 content 422, (9) resolved path jail 403, (10) D-U7 four-eyes 403, (11) same name in the inbox 409 (`replace=true` opts in), (12) per-Space daily quota 413 (`upload.daily_bytes`, 200 MiB), (13) atomic temp file and move (`AtomicFiles`).
- **Four-eyes** is the existing D-U7 row budget (`four_eyes_budget_above`, rows = lines in the file) through `InvRoutes.refuseIfSensitive`; no threshold set means no gate. Classification is **not** re-judged at upload: the Pipeline's schema and the Dataset read mask as for any Dataset.
- **Audit:** `EventType.LA_UPLOAD` per attempt after the capability gate (Collector, file, bytes, sha256, outcome, refusing gate); never row content. The daily total is a per-Space ledger `<config root>/audit/la-uploads/<UTC day>.bytes` (landed bytes only, one JVM lock).
- **Status, delete and retention (LA-I2-GAPS-RESIDUALS-1 item 1, 2026-10-19).** Each landed upload also writes a metadata record `<config root>/audit/la-uploads/records/<collector>/<file>.json` (`<data root>/audit/la-uploads/…` for a Space with a data root, like the daily quota ledger; `SPACE-DATA-ROOT-CONFIG-AUDIT-1`) (file, bytes, lines, sha256, uploader, landedAt, replaced; never content), pruned after 90 days (`UploadRoutes.RECORD_RETENTION_DAYS`); the landed file itself follows the Collector's own retention (D-UP7 stands). Three routes, all `canUploadData`: `GET /inv/uploads` lists the upload-enabled Collectors (id, Pipeline, include patterns, `maxBytes`; no paths); `GET /inv/uploads/{collector}` lists the caller's own uploads (every upload for `canAdminister`, or when no Subject is attached), newest first, at most 200, rate-classed expensive, each with `state` from `UploadLandingPort.ingest` (`pending` still in the inbox, `ingested` the Collector's `.processed` marker exists, `quarantined` the whole file sits in a quarantine reason folder, `consumed` left the inbox with no marker because duplicate check is off), `rejectedRows` when the Pipeline wrote its `_errors.csv` ledger, and `quarantineReason`. `DELETE /inv/uploads/{collector}/{file}`: 503 no write root, 404 Collector, no record (exact, case-sensitive name) or another analyst's upload (one answer, no existence oracle), 409 once the Collector has picked the file up (marker, fingerprint ledger or quarantine; its rows stay in the Dataset) or while a run of the Pipeline is in flight - the delete takes the Pipeline's run claim without blocking (`CollectorService.whileIdle`), re-checks, renames the file atomically to a `.tmp` name the poll never picks up and only then deletes it; any filesystem fault is an audited 409, never a 500. `replace=true` on the POST is likewise the uploader's or an administrator's (403 otherwise) and never changes the recorded uploader. Otherwise the inbox file and record are removed and `LA_UPLOAD` (`la.upload.delete`, outcome `deleted` / `refused`) is audited. The daily-quota ledger is not credited by a delete. **Hardening as built (LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1 (2)-(6), 2026-10-20):** (2) `HostUploadLandingPort.withdraw` reads the ingest state from the filesystem BEFORE the run claim, so a picked-up file answers 409 picked-up without touching the claim (and never "busy"); the claim is taken only for a pending file and the state re-checked inside it. (3) The fingerprint-ledger "ingested" test compares the entry's recorded `lastModified` (the file's mtime at ingest) with the file's current mtime, not the processing host's wall clock (`processedAt`), so clock skew cannot flip a state. (4) `UploadLandingPort.whileIdle` sweeps stranded `.<name>.withdraw-<nanos>.tmp` files from the inbox's top level on entry, under the claim (so none can be a live withdraw): regular files of exactly that name shape only, never a folder, junction or link (`NOFOLLOW_LINKS`, not recursive); the sweep points are the next withdraw or replace on that Collector. (5) The reject ledger is counted up to `HostUploadLandingPort.REJECT_COUNT_CAP` (10 000) rows; past it `rejectedRows` is the cap and the row carries `rejectedRowsCapped: true`. (6) `replace=true` writes through `whileIdle`, so it takes the run claim: 409 (audited gate `busy`) while a run of the Pipeline is in flight; a fresh name needs no claim. **Review follow-ups (same day):** the sweep matches only `.<name>.withdraw-<nanos>.tmp` where `<name>` is accepted by THIS Collector's include/exclude patterns, so another Pipeline polling the same inbox under different patterns is not swept (the run claim is per Pipeline; two Pipelines with overlapping patterns on one inbox remain a known limit); the reject ledger scan also stops at `REJECT_READ_CAP_BYTES` (4 MiB), and exactly the cap followed by blank lines is an exact count; a `replace=true` re-checks, under the claim, that the file is still in the inbox (409 if the Collector took it meanwhile - not covered by a test, the window is not reachable over HTTP). The SPA upload pane shows the server's busy message for a replace refused mid-run (no Replace offered) and "at least 10,000 rows rejected" when capped. Deferred: no new rate class for the delete (the pre-claim check makes repeated deletes filesystem-only); the cap is a code constant, not a Space limit. Status rows carry file metadata only, so there is nothing to mask; they are filtered to the caller by uploader id.
- **Visual Pipeline editor keeps the flag.** The editor saves through `PipelineEditable.toMap` / `lower`, which carries every `collector:` key verbatim on the acquisition node, so `accepts_upload` survives a save (also after editing another Collector key and under the strict lower); pinned by `CollectorAcceptsUploadLiftLowerTest` (engine). `PipelineLift` / `PipelineCompiler` (the typed-record rebuild) do not carry it, but nothing on the editor save path uses them.
- **Lying Content-Length (raw-socket tests):** the JDK server passes both mismatches to the route. A body shorter than declared answers 400 `MALFORMED_REQUEST` (nothing lands, no temp file); a longer one is cut to the declared N bytes by HTTP framing (201 with N bytes, bounded by the cap on N).
- **Decisions D-UP1…D-UP8 in one line:** land only in an opted-in Collector, never a scratch Dataset (1); 25 MiB per file and 200 MiB per Space per day (2); a new `canUploadData` capability (3); 409 on a same name, `replace=true` for the uploader or an administrator (4); CSV only, no XLSX converter (5); the Collector schema defines the columns and a mismatch quarantines (6); the Collector's own retention, records 90 days (7); route first, then the pane (8).
- **Follow-ups:** the 409 sees only a file still in the inbox (an ingested same-name file is caught by the Collector's checksum duplicate mode); the ledger is not shared across nodes; operator confirmation of D-UP1…D-UP8 (open work: `docs/superpower/link-analysis-roadmap.md` §D4). Tests: `ControlApiAnalystUploadTest` (geo-link).

## 6.7 Masked-alias refusal on identity writes (as built 2026-10-18)

`EntityIdentityRoutes.refuseAlias` (predicate `MaskTokens.TOKEN_PREFIX`, the one the other routes use) answers **422 before any fact is appended** when `POST /inv/entity-identities` (`a` or `b`) or `POST /inv/entity-identities/import` (any Dataset row) carries an alias-shaped value such as `msisdn:masked:<16hex>`. Why: a masked analyst only holds aliases, and asserting one appended a permanent Space-wide `identity.asserted` fact on a garbage literal key. That is an **integrity** hole, not a read leak (the response re-masks it). The server does not resolve the alias (that would need `canRevealLinkEntities`); it refuses. Retract takes a seq and resolve/seed carry no identity facts, so they are unaffected. Probes: `ControlApiEntityIdentityTest.assertRefusesAMaskedAliasAndWritesNothing`, `ControlApiEntityIdentityImportTest.aMaskedAliasInTheDatasetRefusesTheImportAndWritesNothing`.

## 6.8 Category exclusion lists in Investigations (LA-GROW-EXCLUDE-1 phase 4, as built 2026-10-22)

The list side (attributes, `PUT /entity-lists/{id}/attributes`, `canManageExclusionCategories`) is in the entity-list hub
[spec](../entity-list/spec.md), "Category lists". What Link Analysis does with them:

- **Auto-apply (GE-9).** `InvestigationRoutes.create` reads the fact log and turns every live admin exclusion list with
  `autoApply` (`EntityList.autoApplies()`, creation order) into an `excludeBy` step numbered from 1: params from the same
  `params()` an append uses, reason `auto-applied category list` (`EntityListFacts.AUTO_APPLY_REASON`), the list sealed by
  the same `sealList`. With any such step the header and the steps are written in ONE move (`store.createFork`, as a fork or
  an instantiation), so an Investigation never exists without them; with none, `create` is unchanged. A list that cannot be
  sealed (Entity Type out of force 409, more than `input.list_members` 422) refuses the create, naming the list: fail closed,
  an Investigation never starts silently without a policy list. `GET /inv/exclusion-lists` tells the administrator first
  (`LA-GROW-EXCLUDE-RESIDUALS-1` item 3, 2026-10-24): a live auto-apply list the same two checks would refuse carries
  `problem` (*its Entity Type 'x' is no longer in force* / *it has N members; a list op seals at most M*; the key is absent
  on a list that seals), and Settings ▸ Link Analysis shows a warning per such list naming that new Investigations are
  refused. The create answer adds `autoApplied: [listId]` (only when
  non-empty; the sealed header is unchanged). Template instantiation prepends the same steps, skipping a list the template's
  own method already excludes by; a fork (reorder) carries the parent's steps and adds none. Being ordinary steps, they
  replay, undo and appear in the Dossier like any `excludeBy`, and a later change to the list moves nothing already sealed.
- **Seal.** A restricted list's seal carries `visibility: "restricted"`; an open list seals exactly what it did before.
- **`seedBy` over a restricted list is refused (403)** to a caller without either grant (`requireSeedable`, on append and
  on template instantiation): a seed admits every member the Dataset holds as an entity. `excludeBy` stays allowed.
- **Restricted answers (GE-8, plan §7).** `RestrictedLists.of(ex, writeRoot, log)` names the list ops over a list sealed
  restricted OR restricted now (fail closed, as masking reads a list's flag), for a caller holding neither
  `canManageExclusionCategories` nor `canRevealLinkEntities`. Render time only, after every hash. For that caller: a step
  answer (`commit`, so `/ops`, a Draft's ops, an approved expand) has `list.unmatched: null`, `list.membersHidden: true`,
  `protected: null` and `list.removed` rounded (nearest 5, never below 5, 0 stays 0); the log view's "Excluded N entities"
  is rounded; `/replay` (main and Draft) drops the `excludedKeys` those steps remembered and says `excludedKeysHidden: true`.
  ⚠ Not hidden, deliberately: the step's Working Set **delta** still names the admitted entities it removed (the canvas
  needs them, and the analyst saw them a step earlier).
- **The Dossier** (`LA-GROW-EXCLUDE-DOSSIER-RESTRICTED-1`, fixed 2026-10-23) hides the same for that caller, in every
  form it leaves in (`json`, `steps`, `method`, `html`, the bundle and the hand-off — all go through
  `DossierRoutes.maskedDossier`): `DossierRoutes.restrictedSteps` hands `GraphDossierBuilder.Input.restrictedSteps` the
  `RestrictedLists` steps, and each such `excludeBy` renders as *"Excluded about N entities on Entity List `x` (…)
  (reason: …); which entities is not shown: the list is restricted (the count is rounded)."* in the ledger (row
  `membersHidden: true`), the undone list and the method statement; `negativeSpace.excluded` drops its ids and
  `negativeSpace.excludedHidden` carries one row per step `{step, count (rounded), reason, author, basis, membersHidden}`;
  `excludedCount` and `summary.excluded` add the rounded counts. Render time only: the manifest hashes the store, so the
  same `/dossier/verify` agrees. ⚠ Still exact, deliberately: the ledger's `entitiesAfter` (the Working Set size after
  each step) — the same difference the step's delta already shows that analyst.
- **Grow previews.** `GrowPreview.listLine` rounds a list's `removed` only when the list is restricted (now, sealed, or
  no longer known) and the caller holds neither grant; an open list's count is exact. The Basic explorer's
  `POST /inv/projection/neighbors/preview` refuses `excludeBy` over a restricted list to such a caller (403), because two
  previews, with and without the list or over a one-id frontier, would name its members by difference.
- **`GET /inv/exclusion-lists`** (`ExclusionListRoutes`, feature `geoLink`, so Basic serves it) →
  `{lists: [{id, title, entityType, ownerKind, autoApply, visibility, size}]}`: live exclusion lists, admin first, then by
  title and id; never members. Read-gated like the Entity List reads (Space access; 503 without a write root). Rate class
  exempt.
- Tests: `ControlApiInvestigationCategoryListsTest` (geo-link, 7: auto-apply recorded and replayed with an optional, an
  analyst and a retired list beside it; the unsealable list refusing the create with nothing written; the chips in Basic;
  503; restricted hiding and rounding for an analyst against the true values for a `canRevealLinkEntities` lead; the
  Dossier in all four formats for each of them, its manifest still verifying; the Undo race below, [architecture-investigation-ui.md](architecture-investigation-ui.md) §6.9).
