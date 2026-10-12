# Link Analysis next features - scoping (embed view, standing detection, Investigation ops, Collector coverage)

**Status 2026-10-12: decisions TAKEN (section 6a). Built since: embed API S1-S3 (dark), the `lastWeekday` rule, the coverage S1 pinning test; standing detection closed. Parked: embed SPA + host-wide frame headers, coverage S2-S3.** Originally written as docs-only scoping with decisions open. The operator chose to build the four parked
Link Analysis rows in BACKLOG section 3.12. This page says, per row, what is genuinely left (checked against code on
`origin/master` `299becc07`), the smallest build slices, the gates each touches, and the decisions the operator must take
before a slice starts. It is a plan: when a row ships, distil the as-built facts into
[`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md), move leftovers to the BACKLOG and
`git mv` this page to the plans archive. The tracker for everything else is
[`link-analysis-roadmap.md`](link-analysis-roadmap.md).

## 0. Verdict table

| Row | Verdict | What is really left | Effort |
|---|---|---|---|
| `LA-EMBED-VIEW-1` | **NEEDS DECISION** (design complete; nothing built) | the whole feature | L (4-5 slices) |
| `LA-LIVE-DETECTION-1` | **MOSTLY DONE - needs decision** (LD-1 to LD-7 built; template sharing + documented residuals left) | template sharing (declined D-LD6) | S-M |
| `LA-INVESTIGATION-OPS-DEFERRED-1` | **MOSTLY DONE - stale row headline** | three narrow open items; four DECLINED | S each |
| `LA-COLLECTOR-COVERAGE-1` | **NEEDS DECISION** (shipped; only the expected-Collector roster remains) | a roster so a Collector that never delivered is visible | M |

## 1. Verify before building (the rows look stale; grep the symbols)

1. **Embed**: a grep for `EmbedGrant`, `canShareInvestigation`, `/public/investigations`, `la.embed.enabled` over `*.java`, `*.ts`, `*.json`
   finds NOTHING (2026-10-12), so the feature is genuinely absent. `ControlApi.isSelfVerifyingPublic` (`inspecto/.../control/ControlApi.java`
   ~line 1157) hard-codes exactly `/public/dashboards/` and `/public/delivery-status/`. No `frame-ancestors` or `X-Frame-Options` string
   exists in any `inspecto*` source. `ShareTokens` is still package-private in core. `InvestigationStore` DOES now exist
   (`la/inspecto-la-core/.../InvestigationStore.java`, `InvestigationStoreProvider`, `InvestigationStores`), so the design's "behind the
   seam from day one" is now possible, which the 2026-10-05 design could only anticipate.
2. **Embed**: the design file the row cites (`archived-documents/plans-archive/la-embed-view-design.md`) is archived but its header still
   says "ACTIVE PLAN - docs/superpower/". Its decisions are EMB-1 to EMB-14; this page re-asks only the security-shaped or changed ones.
3. **Live detection**: the design's header says LD-4 to LD-6 "not started" - stale (the roadmap says LD-1 to LD-7 built). Re-grep before
   touching: `la/inspecto-la-api/.../StandingDetection.java` (refusal codes `NOT_ENABLED`, `NO_OWNER`, `BINDING_CHANGED`, `ROLE_SHARE_ONLY`,
   `NOT_LEAD`, `MASKING_TIGHTENED`), `platform/inspecto-engine/.../job/LaDetectJob.java`, `AlertAccess.evaluateInvestigationRules()`,
   `la/inspecto-geo-link/.../InvestigationMeasureRoutes.java`.
4. **Ops**: the row's headline ("the unbuilt half") is wrong. The OKF concept shows threshold, snapshot (marker), compare (sealed op and read
   route, `inherit`, `activity`, `minAbsDelta`), temporal (sealed op, link and entity series), time-respecting paths, calendar exclusions
   (dates, ranges, yearly and nthWeekday rules), the playback strip, and the `layering-network` / `circular-financing` packs (with closure)
   are all shipped. Re-grep each before listing it as work.
5. **Collector coverage**: the row is already "SHIPPED, residual only". Verify `CollectorCoveragePort` / `CollectorCoveragePorts`
   (`inspecto-la-core`), `HostCollectorCoveragePort` (`inspecto-geo-link`), `InvestigationCoverageRoutes` (`inspecto-la-api`) exist before
   assuming the join is there.
6. Confirm with peers that nobody holds these rows; the BACKLOG row text was read at `299becc07`.

## 2. LA-EMBED-VIEW-1 - read-only embeddable Investigation view

**Goal.** A person with no Inspecto login opens a URL (or an iframe) and sees a masked, read-only, live view of ONE Investigation until
the link expires or is revoked. Full design: the archived `la-embed-view-design.md` (read it before slice S1).

### What is left
Everything: principal, grant store, mint / list / revoke routes, public GET, header work, log redaction, SPA shell, audit, tests.

### Slices
| Slice | Content | Effort |
|---|---|---|
| S1 | `EmbedGrant` record (auth-spi, NOT a `Subject`) and `RequestAttrs.ATTR_EMBED_GRANT`; grant store behind `InvestigationStore` (file implementation; only the token SHA-256 stored); mint / list / revoke routes behind `canShareInvestigation`; kill switch `-Dla.embed.enabled` default off; audit `embed.mint` / `embed.revoke` | M |
| S2 | Public `GET /public/investigations/{token}`: masked view JSON (header, Working Set, annotations, references), uniform 404, per-IP rate bucket, no-store / no-referrer / nosniff, per-grant `frame-ancestors`; make the public-prefix list a registered extension point instead of a third hard-coded `startsWith`; token redaction in access logs and telemetry; `embed.view` / `embed.denied` audit with rollup counters | M |
| S3 | Auto-revoke on delete / close / masking loosened; sweep purges expired hashes (keeps the audit row); ABAC action `embed` and max-classification 422 | S |
| S4 | SPA: mint dialog and grant list in the Investigation pane (token shown once); read-only shell `/embed/inv/:token` in `projects/link-analysis` (no editor chrome, one GET only) | M |
| S5 (optional, host-wide) | `frame-ancestors 'none'` and `X-Frame-Options: DENY` on every non-embed response | S, wide blast radius |

### Security and gate implications
* **Four route gates, three far from the route** (memory trap: a new mutating route clears four gates): (1) `CapabilityManifest` rows - mint
  and revoke gated, list read-shaped, the public GET a `self-verifying-public` exemption with a LITERAL path (the scanner is literal-only);
  (2) an `openapi-v1.json` entry per route (a miss SKIPS later modules); (3) the authgate coverage test needs a BARE route literal;
  (4) `AbsentGeoLinkRoutes` for hosts without LA. Plus route-gating `--check` and the vocabulary guard. Tests without a Subject make
  `withCapability` a NO-OP, so mint tests need an armed Authenticator.
* **Capability / PDP.** One new verb `canShareInvestigation` (Roles, controls matrix, role docs). The holder reaches no capability gate by
  construction (no Subject). Mint and revoke pass the PDP like any gated mutation.
* **Masking.** Always masked, no reveal, even when the Space `maskingMode` is `off`; reuse `EntityMasking`, never a second masking path. No
  Dataset names beyond what the graph needs, no raw SQL, no pending four-eyes expands, no Case, no drafts.
* **Four-eyes.** None on the holder (read-only, nothing to approve). Mint may reuse the approval queue (recommended off for v1).
* **Dossier / bundle hash seal.** The grant store is a SIDECAR, never an Investigation log entry, so no sealed hash, Dossier root or bundle
  verify changes. Hard rule: do NOT append `embed.*` as a log step. A test must prove the Dossier root is byte-identical before and after
  minting a grant.
* **Token design.** Opaque random 256-bit URL-safe value; only SHA-256 stored; shown once; never in a list response, log line or audit event.
  Scope is exactly `(space, investigationId)`. Expiry mandatory (default 72 h, max 30 d, server clock, uniform 404). Revocation is a row
  flag, immediate: per grant, bulk and automatic. Audit actor `embed:<grantId>` with `issuedBy` as a field. GET only (other verbs 405).
  Uniform 404 (identical status AND body) for unknown / tampered / expired / revoked / deleted / disabled.
* **CSP / framing / cookies.** No cookie read or set, no CORS header, token in the path. The public view sends
  `Content-Security-Policy: frame-ancestors <per-grant https allowlist, max 5>` (empty = `'none'`). Shell page CSP: `default-src 'self'`,
  no inline script.
* **No raw ids.** The view carries masked ids only; audit events carry the grantId and a hash prefix of a failed token, never an entity id.
* **Honest limit.** A holder can screenshot; the control is expiry, revocation and audit.

### Tests
Real HTTP, armed Authenticator, mutation-check every negative: mint capability (read-only member 403, kill switch 503, TTL clamp, 422
classification); token absent from list, logs and audit (scan captured output); the six 404 causes identical; no escape (token on `/inv/...`,
`/bi/query`, `/public/dashboards/...` refused, non-GET 405, no reveal field); headers per grant; revoke immediate; delete / close
auto-revoke; sweep purge; Dossier root unchanged after mint; SPA shell spec (axe, no editor controls). Operator-run one-line mutations
(the classifier refuses agent edits of guards): drop the expiry check, drop the revoked check, attach a Subject on the public path,
unmask the view, let a non-GET through, log the token.

## 3. LA-LIVE-DETECTION-1 - standing detection

### What already ships (do NOT re-scope)
`POST /inv/investigations/{id}/standing-detection` (owner enables a value-measure rule); `DELETE .../standing-detection/{rule}`;
`GET .../alert-rules` (bound rules and state); `PUT .../alert-rules/{rule}` (edit in place, drops recorded authority);
`GET /inv/investigation-templates` (owner-only summaries); the `la.detect` Job Type (`LaDetectJob`, a clock over
`AlertAccess.evaluateInvestigationRules()`, fails the Run closed when `alerts` or LA is absent, a dry run evaluates nothing); the sweep
principal `sweep:<investigationId>` holding no capability, authority recorded at enable and re-decided before every Dataset read, with
coded and audited refusals `NOT_ENABLED`, `NO_OWNER`, `BINDING_CHANGED`, `ROLE_SHARE_ONLY`, `NOT_LEAD`, `MASKING_TIGHTENED`; the SPA
Value Measures panel. D-LD2 to D-LD18 were confirmed by the operator 2026-10-10.

### What is really left
1. **Template sharing** (D-LD6, declined for now): templates are owner-only, so a second analyst cannot reuse a detection recipe.
2. **Documented residual limits** (not defects): the synthetic Subject's grants are an enable-time snapshot with no role names, so a PDP
   DENY keyed on `subject.roles` cannot be reproduced; a role-share-only Dataset cannot be swept (a `user` share is needed); owner
   `canManageIncidents` / `canAuthorAlertRules` cannot be re-checked off a request; a sealed-Working-Set rule is not standing detection.
3. **Possible gap (verify with the operator)**: cadence is the Job's cron only - there is no continuous evaluator. The row's original
   "live (Monitoring) evaluation" is met only by a short cron.

### Slices
| Slice | Content | Effort |
|---|---|---|
| S1 | Template sharing: shared-template read route; `instantiate` by a non-owner runs through the instantiator's OWN R3 / Dataset gate and capabilities (the template carries no authority) | M |
| S2 | Optional: role-share support by re-resolving role grants at sweep from a stored role-name list (needs a stated refresh rule) | M, security-sensitive; lean no |
| S3 | Optional: per-sweep refresh of owner token-time capabilities through an owner delegation record | M, new authority concept; lean no |

### Gate and security implications
S1: new routes mean the four gates; capability reuse (`canAuthorAlertRules`) vs a new verb; a shared template must NOT carry the owner's
Dataset shares or masking basis; masking on instantiate re-derives from the instantiator; audit `template.share` / `template.instantiate`.
Sealed hashes are unaffected (templates are outside the log). S2 and S3 widen what a service principal can read, so the PDP
strictest-wins argument must be written first. Invariants to keep: narrow only, aggregate-only output (no entity id in an Alert, event,
log line or Signal), masking basis only tightens, the sweep never approves anything.

### Tests
A non-owner cannot list or read another's unshared template; a shared template instantiated by a user without the Dataset share is
refused and the refusal leaks no id; mutation: skip the instantiator's R3 check; the audit event carries no ids.

## 4. LA-INVESTIGATION-OPS-DEFERRED-1 - the unbuilt half of the Investigation model

### Precisely what is NOT built (everything else in the row is shipped, see section 1 item 4)
| Item | State | What it would take | Effort |
|---|---|---|---|
| Playback on an Investigation canvas | OPEN | The sealed Working Set carries no per-link times. Either a read-only overlay that re-reads the Dataset per frame for the Working Set's links (live, unsealed, like `compare`), or sealing times into the Working Set (changes every hash: NO) | M (overlay) |
| "Last weekday of month" recurring rule | OPEN | One new rule kind in `InvestigationTime`, the SPA text syntax, a sealed-rule golden; a window with no rules must still seal byte-identically (pin test exists) | S |
| Easter-style computed holidays, lunar calendars | OPEN, no demand | A calendar-rule resolver, per locale | M-L, defer |
| `snapshot` sealing a `/inv/snapshots` Artifact | DECLINED 2026-10-05 | second source of truth | - |
| Re-evaluable `threshold` | DECLINED 2026-10-05 | an exclusion deletes links a re-admission needs | - |
| Window applied to the sealed `temporal` scan | DECLINED 2026-10-05 | would diverge from the panel it seals | - |

The board trigger is "an operator asks for one by name", so the question is which of the three OPEN items to build (decision D8).
Dossier PDF and audited export belong to `LA-DOSSIER-OUTPUT-1`, not this row.

### Gate / security implications
The playback overlay's read route must apply the same R3 and masking as `compare` (per-side slot / day masks via
`InvestigationTime.window`); no new capability; no seal impact because nothing is appended. A new recurring rule changes the sealed rule
schema: older logs must replay and verify unchanged, so the kind is additive. Never change the canonical encoding of an existing op (it
changes every sealed hash and the promote byte-identical oracle).

### Tests
Last-weekday rule: golden dates across leap years and 30/31-day months, 422 on bad inputs, replay-verify of an old log, SPA text-syntax
spec. Playback overlay: masked ids only, excludes honoured, empty-frame state, a11y spec (reduced motion, keyboard stepping).

## 5. LA-COLLECTOR-COVERAGE-1 - residual

### What ships
`GET /inv/investigations/{id}/coverage` returns `collectors:{assessed, collectors:[{collector, coveredDays, missingDays}]}` through
`CollectorCoveragePort` (bridge `HostCollectorCoveragePort`): it joins each Pipeline's `batches` CSV (Consignment to output store) to its
`lineage` CSV (Consignment to event-day partition) and attributes to `PipelineConfig.collector().id()`. The SPA shows the per-Collector
table. The Dossier does NOT seal live coverage (deterministic root); it points at the route.

### What is left
A Collector that never delivered into the Dataset is invisible: there is no expected-Collector roster. Two routes, both declined for now:
* **Derived roster** (Pipelines whose output lands in the store): needs the Pipeline-schema-to-`output_table` mapping, which is
  selector-dependent in `PipelineConfig.Schemas`. A wrong guess paints false "incomplete" alarms.
* **Authored roster** (`expectedCollectors` on the Dataset or Investigation): a new write route (four gates) and a storage home.
Also open: partition days are the Pipeline's pinned zone, not the window zone, at window edges.

### Slices
| Slice | Content | Effort |
|---|---|---|
| S1 | Pin the schema-to-store mapping with a test over every shipped Pipeline config (precondition; no behaviour change) | S |
| S2 | Derived roster in `HostCollectorCoveragePort`: an expected Collector with zero batches in the window reports all days missing with `neverDelivered:true`; falls back to today's behaviour when the mapping is not unique | M |
| S3 | Zone edge: label the pinned zone in the payload (or convert to the window zone) | S |
| S4 | Authored roster (only if D9 answers "authored") | M |

### Gate / security
S1 to S3 extend an existing read route: no new route, no new gate; masking not applicable (no entity id). A roster can leak the existence
of Pipelines the caller cannot read, so filter by the caller's read access to each Pipeline. Sealed hash unaffected (coverage stays
unsealed). S4 adds a write route: four gates and a four-eyes question.

### Tests
Mapping pin test; a Collector that delivered nothing shows `neverDelivered`; an ambiguous mapping falls back to `assessed:false` and never
alarms (a negative test with a probe that WOULD otherwise alarm); a caller without Pipeline read does not see that Collector; zone golden.

## 6. OPEN DECISIONS (numbered; options and recommendation)

**D1. Embed: build it now?** The 2026-10-03 call was keep deferred until a named consumer asks. Options: (a) build now as chosen;
(b) build the JSON API only (S1 to S3) and defer the SPA shell; (c) stay deferred. **Recommend (b)**: the API, audit and revocation are the
risky half and testable alone; the shell follows when a consumer is named. Record that the build overrides the old deferral.

**D2. Embed: iframe embedding allowed?** (a) link-only, always `frame-ancestors 'none'`; (b) per-grant https allowlist, max 5;
(c) global allowlist. **Recommend (b)**, default empty = none.

**D3. Embed: masking.** (a) follow the Space `maskingMode`; (b) always masked, no reveal, even if the mode is `off`. **Recommend (b)**.

**D4. Embed: expiry.** (a) default 72 h, max 30 d; (b) default 24 h, max 7 d (BI-like); (c) no max. **Recommend (a)**, shorter than BI
because Investigation evidence is more sensitive; configurable per Space.

**D5. Embed: host-wide frame-deny headers (slice S5).** (a) yes now; (b) embed responses only; (c) yes, behind a flag. **Recommend (c)**:
it changes behaviour for the whole host; ship off, flip after a smoke run.

**D6. Embed: mint governance.** (a) new `canShareInvestigation` verb, no four-eyes; (b) same verb plus four-eyes on mint; (c) only for a
non-sensitive Investigation. **Recommend (a)** plus the ABAC `embed` action and a max-classification 422 so policy can forbid sharing a
classified Investigation; add four-eyes only if a regulator asks.

**D7. Standing detection: template sharing and cadence.** (a) keep owner-only (status quo); (b) share within the Space with the
instantiator's own gates; (c) share via the registry envelope. **Recommend (b)** only if a second analyst needs it, otherwise leave
declined. Also decide whether a cron on `la.detect` counts as "live": **recommend yes, no continuous evaluator**.

**D8. Ops: which open items to build.** (a) none; (b) last-weekday-of-month only; (c) (b) plus a live playback overlay on the
Investigation canvas; (d) all including computed holidays. **Recommend (b)**, and (c) only if an analyst names the need; the overlay must
stay an unsealed live read. Do not reopen the three DECLINED items without new evidence.

**D9. Coverage: expected-Collector roster.** (a) derived from Pipeline schemas after S1 pins the mapping; (b) authored list; (c) keep
declined. **Recommend (a)**; if S1 shows the mapping is not unique for shipped configs, stop at (c).

**D10. Coverage: zone at window edges.** (a) keep the pinned Pipeline zone and label it in the payload; (b) convert to the window zone.
**Recommend (a)**: cheaper, no false precision.

**D11. Embed: who may mint and who sees the grant list.** (a) lead roles of the Investigation intersected with the verb; (b) any holder
of the verb; (c) owner only. **Recommend (a)**; the list is read-shaped for the Investigation's own readers.

**D12. Embed: grant store backing.** (a) file implementation behind `InvestigationStore` now; (b) Postgres first. **Recommend (a)**: the
seam exists and a Postgres implementation follows its contract test.

## 6a. Decisions taken 2026-10-12 (operator interview)

* **D1** build the embed API first (S1-S3); the SPA shell (S4) and host-wide frame headers (S5) are deferred. This overrides the 2026-10-03 deferral.
* **D2** link-only: every embed response carries `frame-ancestors 'none'`; no per-grant allowlist.
* **D3** the embedded view FOLLOWS the Space masking mode (not always-masked; the recommendation was declined). Because a Space on mode `none` would show raw ids to anyone holding the link, the mint route must say so in its response and the audit record carries the mode in force.
* **D4** token expiry: default 72 h, maximum 30 d, configurable per Space.
* **D6** a new `canShareInvestigation` verb, no four-eyes on mint, plus an ABAC `embed` action and a max-classification 422. **D11** minting = the Investigation's lead roles intersected with the verb; the grant list is read-shaped for its readers. **D12** the grant store is a file implementation behind `InvestigationStore`.
* **D7** standing detection: nothing more to build; a cron on `la.detect` counts as live; templates stay owner-only; the stale design header is corrected and the row closes.
* **D8** Investigation ops: build only the last-weekday-of-month recurring rule; the playback overlay, computed holidays and the three declined items stay out.
* **D9** coverage: derive an expected-Collector roster from Pipeline schemas AFTER the pinning test (S1); stop and keep it declined if the mapping is not unique. **D10** keep the pinned Pipeline zone at window edges and label it.

## 7. Recommended build order

1. **Coverage S1** (pin the mapping; a pure test, no risk) - answers D9 with evidence.
2. **Ops: last-weekday rule** if D8 is (b) - additive, golden-tested.
3. **Embed S1 to S3** (D1 = b): principal, store, routes, public GET, audit, auto-revoke - the large, security-sensitive item; queue the
   operator-run mutation list; include the four-gate checklist and the Dossier-root-unchanged test.
4. **Coverage S2 and S3** once S1 shows the mapping is unique.
5. **Embed S4** (SPA) and **S5** (flagged host-wide headers) once a consumer exists.
6. **Standing detection template sharing** only if D7 is (b); nothing else in that row needs work.

Items 1 and 2 can run in parallel lanes; the embed lane must not share files with a geo-link lane (route tables, openapi).

## 8. What this page could not verify

* No Maven or ng run (another gate holds the machine); every claim is from reading code and docs, not from a run.
* Whether the BACKLOG row text is the latest on origin (read at `299becc07`).
* The size of the token-redaction work: where access logs and telemetry record request paths was not traced.
* Whether any shipped Pipeline config has an ambiguous schema-to-store mapping (the point of coverage S1).
* Whether the sweep's behaviour matches D-LD1 to D-LD18 beyond the codes and files named above (not re-read in full).

## References

* [`link-analysis-roadmap.md`](link-analysis-roadmap.md) - embed section and the live-detection section
* [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) and
  [`okf/backend/modules/link-analysis.md`](../okf/backend/modules/link-analysis.md)
* [`archived-documents/plans-archive/la-embed-view-design.md`](../archived-documents/plans-archive/la-embed-view-design.md) and
  [`archived-documents/plans-archive/la-live-detection-design.md`](../archived-documents/plans-archive/la-live-detection-design.md)
