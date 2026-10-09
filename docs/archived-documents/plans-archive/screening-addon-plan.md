# Screening add-on — plan (`SCREENING-1`)

✅ **BUILT + ARCHIVED 2026-10-09** (lane `lane-screening`) — provenance only; the truth is `docs/okf/backend/control-plane/screening.md`, the open items are the `SCREENING-1` row of `docs/BACKLOG.md`. Prerequisite of `PACK-AML-1`. The operator was away; every
decision below was taken by the lane and is recorded with its reason (rule: decide rationally, hold only the
irreversible). Nothing here is irreversible: breaking changes are free and the module is new.

## What it is
**Screening** compares subjects (a name and/or an identifier) against **Entity Lists** (sanctions, PEP, deny lists:
any list, any purpose) and reports a **Match Score** per candidate entry. A match at or above the threshold, found by
a **Screening Run**, becomes a **Screening Hit** that a person reviews and decides. Domain-neutral: no list format,
no vertical vocabulary.

## Decisions (SCR-D1 … SCR-D14)
| # | Decision | Why |
|---|---|---|
| SCR-D1 | New optional module `features/inspecto-screening` (artifactId `inspecto-screening`, manifest id + feature id `screening`, package `com.gamma.screening`), offering role optional, binding boot, shipped Professional and above beside `entity-list`; `requires.modules: entity-list`. Add-on `screening` in `offerings/professional.toon`, status built. | A separately saleable add-on by the D-MR9 test (own buyer: compliance; base coherent without it). It reads the Entity List fact log through `inspecto-entity-store`; lists are authored by `entity-list`, so without it there is nothing to screen against. |
| SCR-D2 | Matching runs in **Java**, not DuckDB SQL. DuckDB only reads the Dataset rows of a Run. | Confusable folding, token-set scoring and golden-pair tests are plain and deterministic in Java; DuckDB's `jaro_winkler_similarity` covers one third of the method. |
| SCR-D3 | **Name method**: fold (lower-case, NFKD, strip combining marks, Cyrillic / Greek look-alike letters → Latin, `ß æ œ ø đ ł þ` expansions, digits inside a letter token → look-alike letter (`0→o 1→i 3→e 4→a 5→s 7→t`), punctuation → space, honorifics dropped) → score = max(token-set, joined). Token-set: each token of the name with fewer tokens takes its best Jaro-Winkler partner (greedy, each used once), a pair below 0.8 counts 0, weighted by the pair's mean length, times `0.8 + 0.2 · min/max` of the total token lengths; applies when token counts are equal or the shorter has ≥ 2 tokens. Joined: Jaro-Winkler of the tokens written together, only when counts differ. Score 0..1, 4 decimals, symmetric. ⚠ **Revised during the build**: the first draft took max(whole-string Jaro-Winkler of the sorted tokens, token-set without a floor); the golden pairs showed it scored John Smith / Jane Smith 0.88 and Mark Lee / Mike Lee 0.83, so the whole-string term and the floor-less pairs were dropped. | Token pairing handles reordered names and a missing middle name, the 0.8 floor stops a different first name riding on a shared surname, folding handles transliteration noise and deliberate look-alike spelling, the ≥ 2-token rule stops one common token ("Ali") from matching every long name. |
| SCR-D4 | **Identifier method**: exact under the list's sealed normaliser plus its prefix / range / CIDR entries (the existing `EntityList.match`), score 1.0. | One matching truth for identifiers; Screening must not drift from `POST /entity-lists/{id}/match`. |
| SCR-D5 | Names are matched against a list's **live members** only (ranges are identifier-only); expired entries never match; a retired list matches nothing; an unknown list is refused (422 on a route, a failed Run). | Same expiry and retirement semantics as Entity Lists (`entity-lists.md`). |
| SCR-D6 | **Threshold** is a per-request / per-Job parameter `threshold` in 0.5..1.0, default **0.85**; `maxMatches` per subject 1..20, default 5. No Space-level settings file. | A new fixed config file name needs a reserved-path entry and import-inventory work; the parameter is enough for the MVP and is visible where the Run is defined. |
| SCR-D7 | Two entry points: **`POST /screening/check`** (on-demand, 1..1 000 subjects, read-shaped: persists nothing, a POST so names never ride in a URL) and the **Job Type `screening.run`** over a Dataset (params `dataset`, `keyField`, `nameField` and/or `idField`, `lists`, `threshold`, `maxRows`), which raises Screening Hits. | Interactive onboarding checks and batch re-screening are different uses; only the batch one creates review work. |
| SCR-D8 | A Run **refuses to screen a subset**: more than `maxRows` (default 100 000, ceiling 1 000 000) Dataset rows fails the Run. | Same rule as `risk.score`: a silently partial screen reads as a clean one. |
| SCR-D9 | **Blocking**: a name is compared only with list entries that share the first folded letter of at least one token. | Keeps a Run near O(rows × list/≈10) instead of O(rows × list). Cost: a typo in the first letter of every token is missed — recorded as a known limit. |
| SCR-D10 | **Store**: one HMAC-signed JSON document per hit at `<write-root>/screening-hits/<id>.json`, the Action Requests pattern (atomic temp + move, path-jailed, a forged or edited file reads back `integrity: invalid` and cannot be decided). Reserved from every import (`ReservedConfigPaths`), skipped by the whole-Space export (`BundleExporter`), carried by backup. Not an `OperationalDb` family. | The lightest existing precedent for per-Space records with a state machine; a store family would need a core `SpaceRoot` accessor, count tripwires and backup lockstep for no MVP gain. Hits are compliance evidence, so backup keeps them; they hold screened names, so a Space bundle does not. |
| SCR-D11 | **Hit identity**: `dedupeKey = sha256(listId, entry, subjectKey)`. A Run never raises a second hit for a key that already has one in any state: a dismissed hit stays dismissed until the list entry changes. | A false positive decided once must not come back every night; a changed entry is a new question. |
| SCR-D12 | **States**: `open → confirmed | dismissed | escalated`, `escalated → confirmed | dismissed`; `confirmed` and `dismissed` are final. A decision carries `reason` (1..500, the shared `EntityListFacts.reason` rule) and the hit's `version` (stale → 409 `CONFLICT`); every transition is appended to `history`. | The usual L1 / L2 review: an analyst clears or escalates, a senior decides the escalated ones. |
| SCR-D13 | **Who decides**: `canWorkIncidents` (working a triage item). No new capability. | A new capability edits `Roles`, the seed grants, the OIDC vocabulary test and the security doc, and module-contributed capabilities are declined (P1-D3). Revisit when four-eyes on `confirmed` is required. |
| SCR-D14 | **Audit**: `EventType.AUDIT` rows `screening.run.hits` (one per Run: counts) and `screening.hit.decided` (hit id, list, from → to, decider). Never the screened name. The matched entry is stored and shown as the list renders it (a masked list's entry is a mask token). | The Action Requests audit pattern; masking follows the list, as `POST /entity-lists/{id}/match` does. |

## Routes (feature `screening`; a Space that switched the module off answers 404 `MODULE_DISABLED`)
- `POST /screening/check` `{subjects:[{key?, name?, identifier?}], lists:[...], threshold?, maxMatches?}` →
  `{threshold, results:[{key, name?, identifier?, matches:[{listId, purpose, entry, method, score}]}]}`. Read-shaped exemption.
- `GET /screening/hits?state=` → `{hits:[...]}` newest first; `GET /screening/hits/{id}`.
- `POST /screening/hits/{id}/decide` `{decision: confirm|dismiss|escalate, reason, version}` → the hit. `canWorkIncidents`.

## Out of scope for the MVP (to `docs/BACKLOG.md` when it ships)
Phonetic keys / transliteration tables beyond look-alike folding; a Space-level threshold default; four-eyes on
`confirmed`; raising hits from `check`; an index file for dedupe (a Run lists every hit document); the SPA pane.

## Test plan
Golden pairs (variants that must match, look-alikes that must, different people that must not) in
`NameMatcherTest`; `ScreenerTest` over a real fact log; the Job over a real Dataset; the routes over real HTTP with an
armed Subject (401 / 403 / 200), stale version 409, final-state 409, forged file; manifest / route parity and the
`RouteModuleContract` / `JobTypeProviderContract` TCKs.
