---
type: Concept
title: Link Analysis — HTTP surface and Investigation UI — Outbound integration, CSV upload, masking and exclusion lists
description: Part 2 of 4 of Link Analysis — HTTP surface and Investigation UI: Third-party integration (outbound) (LA-EDITIONS-1 phase 6, built 2026-10-17); Analyst CSV upload (LA-ANALYST-UPLOAD-1, as built 2026-10-18); Masked-alias refusal on identity writes (as built 2026-10-18); Category exclusion lists in Investigations (LA-GROW-EXCLUDE-1 phase 4, as built 2026-10-22)
tags: [module, la-core, link-analysis, architecture, http, investigation, spa]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — HTTP surface and Investigation UI — Outbound integration, CSV upload, masking and exclusion lists

Part 2 of 4 of [Link Analysis — HTTP surface and Investigation UI](architecture-http-and-ui.md) · previous: [Gate order, embeds, entity profiles and inbound integration](architecture-http-and-ui-gates-embeds.md) · next: [Grow, Held and Exclude on the graph](architecture-http-and-ui-grow-exclude.md).

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
  (`LINK_EXPANSION_REQUESTED`). Nothing is sent from a Space that switched `geoLink` or `investigations` off (the
  integration routes' two features; `MODULE-REORG-P4-3`). The notification webhook channel did not fit (one JVM-wide URL, unsigned, no retry).
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
[spec](../entity-list/spec-entity-list.md), "Category lists". What Link Analysis does with them:

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
  Dossier in all four formats for each of them, its manifest still verifying; the Undo race, §6.9 below).
