---
type: Concept
title: Screening — Match Scores against Entity Lists and the review of Screening Hits
description: What SCREENING-1 built — the optional inspecto-screening module (add-on screening), the name and identifier methods, the on-demand check route, the screening.run Job Type, the signed Screening Hit records and their review states, gates, audit and the recorded limits.
resource: features/inspecto-screening/src/main/java/com/gamma/screening/ScreeningRoutes.java
tags: [control-plane, screening, entity-list, compliance, add-on, job-type]
timestamp: 2026-10-14T00:00:00Z
---

# Screening

**Screening** compares subjects (a name and/or an identifier) against [Entity Lists](../entity-list/spec.md) of any purpose
(sanctions, PEP, deny lists) and reports a **Match Score** per candidate entry. A **Screening Run** raises each new match
as a **Screening Hit** for a person to decide. Domain-neutral: it knows no list format and no vertical vocabulary; the
AML function pack (`PACK-AML-1`) builds on it. Vocabulary: `docs/GLOSSARY.md` §11.

## Module and gates
- Optional module `features/inspecto-screening` (artifactId `inspecto-screening`, manifest id and feature id `screening`,
  package `com.gamma.screening`), add-on `screening` in `offerings/standard.toon`: Professional, Enterprise and Preview;
  absent on Personal, where its four routes answer 503 from the manifest's `absentMessage`. `requires.modules: entity-list`.
- It reads the Entity List fact log directly through `inspecto-entity-store` (`EntityFactLog`, `EntityRegistry`,
  `EntityListFacts`); it never writes to it. A confirmed hit writes no Identity Fact (`D-M1`: never by similarity).
- A Space that switches the module off (`modules.toon`) answers its routes 404 `MODULE_DISABLED` and skips its Jobs
  (`provides.jobTypes: screening.run`).

## The two methods (`NameMatcher`, `Screener`)
- **identifier** — the list's own matching (`EntityList.match`: exact under the list's sealed normaliser, then its prefix /
  range / CIDR entries). Score 1.0. Screening cannot drift from `POST /entity-lists/{id}/match`.
- **name** — against the list's live members (range entries are identifier-only):
  - *fold*: lower-case, NFKD, combining marks stripped, Cyrillic / Greek look-alike letters mapped to their Latin twin
    (Cyrillic у is *y*, not *u*), `ß æ œ ø đ ł þ ı ð` expanded, a digit inside a letter token read as its look-alike
    letter (`0→o 1→i 3→e 4→a 5→s 7→t`; an all-digit token stays digits), punctuation to space, honorifics dropped;
  - *score* = max(**token-set**, **joined**). Token-set: each token of the name with fewer tokens takes its best
    Jaro-Winkler partner (greedy, each used once; order-free), a pair below **0.8** counts as 0, weighted by the pair's
    mean token length, times `0.8 + 0.2·min/max` of the two total token lengths; it applies when the token counts are
    equal or the shorter name has two or more tokens. Joined: Jaro-Winkler of the tokens written together, only when
    the counts differ ("Abdul Rahman" = "Abdulrahman"). Symmetric, 0..1, 4 decimals.
  - *the Jaro-Winkler itself* comes from the Analytics Kernel's similarity kit (ANALYTICS-CORE-1 S1,
    [analytics-kernel § Similarity kit](../analytics/spec.md#similarity-kit)): `NameMatcher.TOKEN_JARO_WINKLER` =
    `JaroWinkler(UTF16_CHAR, HALF, 0.1, 4, boost threshold 0)` — UTF-16 chars, textbook transpositions, prefix scale
    0.1 up to 4, the prefix boost always applied — exactly the arithmetic Screening had before (`NameMatcherGoldenTest`
    pins it bit for bit). Still dependency-free (SCR-D2: the kernel carries no third-party library). The fold,
    honorifics, transliteration, phonetic reading and blocking stay here; only the token similarity's definition moved.
- **Phonetic reading** (`SCREENING-1` 2): when the plain score is below 0.95 the same score is taken over per-token
  transliteration skeletons (`dh gh kh`→first letter, `ou oo`→u, `ei ai ay ey`→i, doubled letters collapsed, a token of
  6+ letters starting q / g read as k) and capped at 0.95; the larger wins. Gaddafi / Qadhafi / Kadhafi / Gadhafi / Khadafi
  all give `kadafi` (0.95); Hussein / Husain / Hussain give `husin`. Dependency-free, no config (SCR-D2); the 6-letter
  floor keeps Gamal / Kamal out of the fold (they stay apart unless the plain score already flags them).
- **Blocking**: a name is compared only with members sharing, for at least one token, the first folded letter OR the
  first letter of its phonetic skeleton (`NameMatcher.blockKeys`, a superset of the old initials).
- The golden pairs (`NameMatcherTest`) pin the behaviour at the default threshold **0.85**: variants and transliterations
  match (Mohammed / Muhammad 0.85, Usama bin Ladin / Osama bin Laden 0.91, Vladimir Vladimirovich Putin / Vladimir Putin
  0.90); look-alike spellings fold to exactly 1.0; different people stay below (John / Jane Smith 0.56, Kim Jong Un / Il
  0.78, "Ali" / "Ali Hassan Mohammed" 0.81); **near spellings are flagged by design** (Chen Wei / Chen Wen 0.92,
  Maria Garcia / Mario Garza 0.88) — recall over precision, a reviewer dismisses them.
- A retired list matches nothing; an expired entry never matches; a masked list's entry is shown as its mask token.

## Routes
| Route | Gate | What |
|---|---|---|
| `POST /screening/check` | read-shaped exemption | `{subjects[1..1000]:[{key?, name?, identifier?}], lists[1..20], threshold? (0.5..1, default 0.85), maxMatches? (1..20, default 5)}` → `{threshold, results:[{key, name, identifier, matches:[{listId, purpose, entry, method, score}]}]}`, best first. Persists nothing; a POST so names never ride in a URL. Unknown list 422. |
| `GET /screening/hits?state=` | Space access | every hit, newest first; `state` = `open · escalated · confirmed · dismissed` (else 422). |
| `GET /screening/hits/{id}` | Space access | one hit; an id that is not `sh-<14 digits>-<6 hex>` is a 404 and never names a file. |
| `POST /screening/hits/{id}/decide` | `canWorkIncidents` | `{decision: confirm · dismiss · escalate, reason (1..1000), version}` → the hit. |

Decide gates in order: capability → no write root 503 → body 422 → unknown hit 404 → a record that fails its integrity
check 409 → stale `version` 409 → transition not allowed 409 → save under the store lock. Test: `ControlApiScreeningTest`
(armed Subjects: 401 / 403 for no capability and for a builder role / 200).

## The `screening.run` Job Type (`ScreeningJobType`)
Params `dataset`, `keyField`, `nameField` and/or `idField` (plain column names), `lists` (comma separated), `threshold`
(default 0.85), `maxRows` (default 100 000, ceiling 1 000 000). It reads the Dataset through `DatasetRelation` +
`QueryExecutor` under the default SQL sandbox, ordered by the subject columns, one row past the cap: **more rows than
`maxRows` fails the Run** (never a silently partial screen); a read error carries only its class (a DuckDB message can
quote a cell). Each match with no hit yet is raised; a classified column's value is stored in the hit masked
(`EvidenceMasker`), matching uses the raw value. Emits the signal `screening.hits.raised`; the Run message counts
screened subjects, matches and new hits. Test: `ScreeningJobTest`.

## Screening Hits (`ScreeningHits`)
- One HMAC-signed JSON document per hit at `<write-root>/screening-hits/<id>.json` — the Action Requests storage pattern
  (atomic temp + move, path-jailed, signed with the Space's Pending Change key under domain `screening-hit`). A forged or
  edited document reads back `integrity: invalid` and cannot be decided or re-signed. Not an OperationalDb family.
- `screening-hits/` is reserved from every import (`ReservedConfigPaths`) and skipped by the whole-Space export
  (`BundleExporter`); a backup carries it (compliance evidence).
- Fields: `id, state, version, dedupeKey, listId, purpose, entry, method, score, threshold, subjectKey, subjectName,
  subjectIdentifier, source {job, runId, dataset}, raisedAt, raisedBy, decidedBy, decidedAt, reason, history[]`.
- **Identity**: `dedupeKey = sha256(listId, entry, subjectKey)`; a Run never raises a second hit for a key that has one in
  any state, so a dismissed false positive stays dismissed until the list entry changes.
- **States**: `open → confirmed · dismissed · escalated`, `escalated → confirmed · dismissed`; `confirmed` and `dismissed`
  are final. Every decision bumps `version` and appends `{state, by, at, reason}` to `history`.
- **Audit**: `EventType.AUDIT` rows `screening.run.hits` (one per Run: counts) and `screening.hit.decided` (hit id, list,
  from → to, decider). Never the screened name.

## The SPA pane (`/screening`, nav *Operations ▸ Screening Hits*)
`modules/admin/screening/` over `ScreeningService` (`inspecto/api`). The nav entry carries `navFeature: 'screening'`,
so it hides when `/bootstrap` `features.screening` is not `true`. Open / Escalated / All filter, a data-table of hits
(Match Score as a percentage, state as a status badge: open info, escalated warning, confirmed error, dismissed
success), and a detail card with the score, subject, source run and history. The decision row (Escalate / Dismiss /
Confirm match, a required reason, the hit's `version` sent with it) shows only with `LensService.canWorkIncidents()`,
only for the decisions the state still allows, and never on a record whose integrity check failed. A 503 from the list
is latched into an explained notice, not a toast.
**Check a name** (header button, `check-name.dialog.ts`, shown to every viewer: `POST /screening/check` is a read-shaped exemption with no capability gate): one name per line (1..1000, 512 chars each), Entity List checkboxes from `GET /entity-lists` (active lists only; 1..20), optional threshold (0.5..1, blank = 0.85) and most-matches (1..20, blank = 5), results as a read-only table (name, Entity List, matched entry, method, Match Score) under a persistent *nothing is raised — this check does not create Hits* notice; server 422 text is shown verbatim, and an absent `inspecto-entity-list` module explains itself in the list area. Raising a hit from a check stays open (`SCREENING-1` item 5). Specs: `screening.component.spec.ts` (axe included) and the
`navigation.service.spec.ts` case for the feature flag; proved end to end in the preview over a seeded Space.

## Four-eyes and the Case (SCREENING-1-D1 / D2, 2026-10-14)
`decide` refuses `confirm` from the person who moved the hit to `escalated` (409 `four-eyes`; `ScreeningHits#escalatedBy` reads the history; an `open` hit has no escalator). A confirm then opens, through `ScreeningCases` and the ops `ObjectService`
(`OpsEngine.of(api)`: 503 when absent, before anything changes), an INCIDENT carrying the subject key and the run's Dataset as its entity identity and a CASE that CONTAINS it, owner = the confirmer; both descriptions cite the hit id, list, method and score and
never the (possibly masked) name. `caseId` / `incidentId` are stamped on the hit before it is saved; a save failure discards both objects. The module's manifest `requires` `ops`
(the pom depends on `inspecto-ops`, as regulatory reporting does), not case-management: the Case is a plain `ObjectService` object. Opening them is the decider's act under `canWorkIncidents` (the confirm), not a second capability check.

## Reveal (SCREENING-1-D3, 2026-10-14)
`GET /screening/hits/{id}/reveal`, `canRevealLinkEntities` (the grant that already means "may see raw keys"; admin and super). A hit stores a classified name or id masked, so
the route reads them again: the raising Job's config (`source.job`) names the Dataset and columns, `ScreeningJob.subjectValues` queries that Dataset for the subject key
(one row, default SQL sandbox), and `Screener.unmaskEntry` resolves a masked list entry to the live member that renders to it. A gone Job, Dataset, row or entry gives a null
field plus `note`. Audit `screening.hit.revealed` (hit id, list; never a value); the file keeps its masks; a forged record is 409. The pane's *Reveal subject (audited)*
button shows only with `LensService.canRevealLinkEntities()`. Tests: `ControlApiScreeningTest#revealIsGatedOnCanRevealLinkEntities`, `ScreeningRevealTest`, the spec.

## Decisions (SCR-D1 … SCR-D14, lane 2026-10-09, operator away)
Matching in Java, not DuckDB SQL (folding, token-set scoring and golden tests are plain there; DuckDB only reads rows) ·
threshold per request / per Job, no Space settings file (a new fixed config name needs reserved-path and import-inventory
work) · `canWorkIncidents` decides, no new capability (module-contributed capabilities are declined, P1-D3; revisit when
four-eyes on `confirmed` is required) · the Action Requests record pattern rather than a store family (a family needs a core
`SpaceRoot` accessor, count tripwires and backup lockstep) · a dismissed hit is never re-raised for the same entry · a
Run refuses a subset. Provenance: `docs/archived-documents/plans-archive/screening-addon-plan.md`.

## Save-time check of a `screening.run` Job (2026-10-14)
`JobTypeProvider#saveRefusal(JobConfig)` (default null) is asked by `JobRoutes#persistJob` after the type check: a Job Type that
can judge its params without running refuses them at save with a 422 (`job '<name>' refused at save: ... - nothing was written`).
`screening.run` answers with `Params.of` (threshold, maxRows, column names, `lists` ids: always) and, for an ENABLED Job in a
Space with a write root, an unknown Dataset or Entity List; a draft (`enabled: false`) may name ones that do not exist yet. An
unreadable store never blocks a save (the Run reports it). Test: `ControlApiScreeningTest#aScreeningJobWithBadParamsIsRefusedAtSave`.
The Check a name result has one row per name: a name with no match shows *Checked: no match at or above T*.

## Limits and what is open (`SCREENING-1` residuals in `docs/BACKLOG.md`)
- Blocking still misses a first-letter difference that is not a known transliteration (a typo in the initial of every
  token). Muhammad / Mohamed now match (the phonetic skeleton folds `o` to `u`, 2026-10-14); a Cyrillic token that holds a
  letter with no Latin look-alike (Виктор, Сергей) is transliterated by sound (viktor, sergei) and so reaches its Latin
  entry, while a look-alike-only or Latin-mixed token (`Pаvel`) keeps the look-alike fold.
- A Run lists every hit document to dedupe (no index); fine for thousands of hits, not for millions.
- `check` cannot raise hits (D4, decided 2026-10-14: stays read-only; raising belongs to the Job under `canWorkIncidents`); no Space-level threshold default (D5: not until a customer asks; SCR-D2 declined a settings file); no dedupe index (D6: measure at 100 000 hits first); an initial-letter difference that is not a known transliteration is never compared (D7: needs a full cross-join). Decisions D1-D7 (recommendation and cost each) are on the `SCREENING-1` row. Re-driven 2026-10-14 on an Enterprise Demo Auth bundle: items 9, 10, 11 and Muhammad / Mohamed PASS; presenting verdict GO beats 6 and 7 built the same day.

## Demo script and rehearsal (2026-10-14, Enterprise Demo Auth bundle, Space `screening-demo` from the `aml` template)
Boot: `node tools/seed-screening-demo.mjs --spaces inspecto-demo/spaces` (roster), serve the bundle, sign in as Demo Admin, run it
again with `--base http://127.0.0.1:<port>` (creates the Space from the `aml` template, a 134-row party register with eight planted
synthetic rows, an unmasked `party` Entity Type, and the lists `aml_sanctions` (party, block) and `aml_pep` (subscriber, watch, masked
on purpose)). Personas: `compliance.officer` (operations), `compliance.manager` (power), `ra.viewer` (business), `admin` (admin;super).
Then enable and trigger the Job `aml_screening` (the template ships it disabled).

| # | Beat | Result | Evidence |
|---|---|---|---|
| 1 | Check names: exact / fuzzy / transliterated / clean / identifier | PASS | Dmitrenko Sorokin 1.0; Dmitrenco 0.98; Kadir Almansuri vs Qadhir Almansouri 0.95; Husain Alrashidi 0.95; Tobias Lindqvist clean; NID-9000001 identifier 1.0; word order and case folded (0.92 / 1.0) |
| 2 | Check a name dialog, any persona, nothing raised | PASS | six names, two lists, 5 rows plus a *Checked: no match* row for the clean name (item 10 shipped 2026-10-14) |
| 3 | `screening.run` over the party Dataset | PASS | 134 screened, 7 matches, 7 hits; re-run 0 new (dedupe); cron `0 5 * * *` shown with next fire |
| 4 | Hits signed; a tampered file is refused | PASS | edited a hit file: GET `integrity: invalid`, decide 409 "a record no server wrote" |
| 5 | Review pane: dismiss with a reason (UI), escalate then confirm by a second person (API) | PASS | history carries both deciders; a final state refuses a move (409); a stale version 409 |
| 6 | Four-eyes on `confirmed` | BUILT 2026-10-14 (`ControlApiScreeningTest`) | the escalator confirming their own escalation is 409 `four-eyes`; a second person confirms; an `open` hit may be confirmed by one |
| 7 | Hits to Incident / Case | BUILT 2026-10-14 | a confirm opens an Incident + a Case that CONTAINS it; `caseId` / `incidentId` on the hit and in the pane |
| 8 | Audit trail | PASS | `screening.run.hits` and `screening.hit.decided` rows in `/audit/search?type=AUDIT`; `/audit/verify` chain ok |
| 9 | Masking | PASS with a gap | PII-classified name and id are stored as `masked:` tokens and matching still works; the masked PEP list shows its entry masked to everyone; no persona could unmask; `/reveal` now serves admin (`SCREENING-1` 8, D3) |
| 10 | Non-privileged persona (business) | PASS | sees hits (masked), no decision buttons in the UI, decide 403 `canWorkIncidents` |
| 11 | Unhappy paths | PASS | empty list = no matches; 1001 subjects 422; 513-char name 422; 1000 subjects in 94 ms; threshold 0.4 / 1.5 / "abc" 422; unknown list 422; 21 lists 422; no auth 401; Job with `maxRows` below the row count fails the Run ("refusing to screen a subset"); a bad Job threshold, column or an unknown list/Dataset on an enabled Job is refused at save (422, nothing written; item 9 shipped 2026-10-14) |

Gotcha found: the template's `full_name` / `national_id` were classified `CONFIDENTIAL`, which is not in `ColumnClassification.SENSITIVE`
(`MSISDN IMSI ACCOUNT PII`), so a hit stored the raw name; they are `PII` now.
