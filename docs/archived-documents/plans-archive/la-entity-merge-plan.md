# LA entity merge / dedupe candidates - plan (i2-gap 1)

Status: PLAN ONLY, nothing built. Every slice is blocked by an operator decision (see 'Decisions owed').

## 1. Ground truth (read 2026-10-18)

* `resolve` op = a VIEW over the Identity Fact log (`identity.asserted` / `retracted`, union-find), deterministic only.
  Traversal, exclusion and counts stay raw (OKF frontend link-analysis 8.3; backend 3.5).
* **D-M1 (operator 2026-09-26): "no fuzzy or similarity merge, NOT EVEN AS A SUGGESTION."** A fuzzy candidate list
  therefore reverses a standing decision, including the read-only endpoint. No slice is decision-free.
* Op vocabulary: the backend OKF counts 15 apply labels (task brief said "twelve"; the doc is the authority and
  should be re-checked before any op is proposed). Adding one needs a decision entry.
* Manual analyst assertion of identity ALREADY exists (D-M10: analyst assertions on the Identity Fact log, hash-chained
  per Space). So "manual merge" is partly built; the missing pieces are candidate discovery and Investigation-local recording.
* Similarity kit: `platform/inspecto-analytics/.../similarity/Similarity.java` (`jaroWinkler`, `jaccard`, `tokenJaccard`,
  `levenshteinSimilarity`, `normalize`) and `SimilaritySql`. No new string code is needed.

## 2. Design

**Candidate generation (read-only).** Input: an Investigation step's node set (or an Entity Type's list), per Entity Type.
Blocking first (same type, same normalised-key prefix / length band / shared neighbour set) to avoid O(n^2), then score:
`jaroWinkler` on normalised keys, `tokenJaccard` on labels, `jaccard` on neighbour sets (structural signal).
Output `{a, b, score, signals{}}` capped (e.g. 200 pairs, n capped by existing node caps). Under masking, only aliases are
compared structurally (neighbour Jaccard) - string similarity on a masked key is meaningless and must not run on raw values
(LA-19, below).

**Review.** Analyst accepts / rejects / defers per pair. Rejections must persist or the same pair re-surfaces.

**Recording - three options for accept:**

| Option | What | Pro | Con |
|---|---|---|---|
| A. Identity Fact | accept = `identity.asserted` on the Space fact log; reject = a new `identity.rejected` fact kind | no new op; hash-chained already; every Investigation's `resolve` picks it up | Space-global effect from one analyst's hunch; needs four-eyes/permission; new fact kind; accepted merge is NOT in that Investigation's op log, only referenced by head hash |
| B. New op `mergeDecision {a,b,verdict}` | sealed in the Investigation log; folded as an Investigation-local overlay on `resolve` groups | decision is in the hash-sealed log as asked; Investigation-scoped; replay-safe | twelfth+ op: decision entry, evaluator/hash/fork/template/rebase/Draft work, closed-vocabulary change |
| C. A then B-lite | accept writes the fact (A) and the Investigation then seals a `resolve` at the new head (existing op) | no new op; the seal records the merged groups and head hash | rejections not recorded in the log; two steps |

Recommendation: **C** for accepts (no vocabulary change, uses sealed `resolve`), with rejections as a Space-level
`identity.rejected` fact (needs the same decision as A). Choose B only if the operator insists a rejection is part of the
Investigation record.

**Merges stay a VIEW** (consistent with 8.3). Rewriting raw ids would break sealed reads (D-E3: replay never re-reads), the
op-log hash and masked aliases; recommend declining rewrite.

## 3. Slices (all blocked)

1. `POST /inv/investigations/{id}/entity-merge-candidates` read-only suggester (la-api route, expensive rate class, Investigation
   read gate, R3/PDP, masking-aware; real-HTTP test class per the endpoint skill, every gate). Blocked by D-1 (D-M1).
2. SPA review panel in the Investigation tab (accept/reject/defer). Blocked by D-1..D-3.
3. Persistence of verdicts (fact kind or op). Blocked by D-2, D-3.
4. Docs distill (OKF), openapi, route-gating evidence, CapabilityManifest rows.

## 4. Decisions owed

* **D-1 Reverse D-M1?** Allow fuzzy/similarity candidates as SUGGESTIONS ONLY (never auto-merge)? Without a yes nothing here may be built.
* **D-2 Op vocabulary:** add an op (Option B) or stay within the closed set (Option A/C)? If a new op, name it and bump the label count in the OKF.
* **D-3 Where a decision lives:** Space-global fact (A/C) vs Investigation-local overlay (B). Who may accept (a new capability? four-eyes for a Space-global merge?). Is a rejection recorded, and where?
* **D-4 Merge stays a view or rewrites?** Recommend view; confirm.
* **D-5 Masking (LA-19):** may candidates be scored on raw keys server-side when the Space masks the type (output aliased only), or structural-only? Also whether a masked analyst may accept a pair they cannot read. The LA-19 question itself is unrecorded in the docs (roadmap says reconfirm with operator) - this may overlap it.
* **D-6 Scope/cost:** candidate caps, blocking keys, rate class, and whether candidates run on an Investigation step or an Entity List.

## 5. Coordinator decisions (assistant-decided on operator delegation 2026-10-18; PENDING OPERATOR CONFIRMATION)

* **D-M1 STANDS.** Sections 2-3 (fuzzy candidate generation, scoring, suggester endpoint) are PARKED: build only if the operator reverses D-M1.
* Scope is MANUAL merge/unmerge: no new op (vocabulary closed), merges stay a view (no id rewrite), Space-wide identity fact under the
  existing assertion permission and four-eyes rules, no new capability. Accept = assert fact then seal existing `resolve` at the new head (option C).
* LA-19: a masked analyst cannot assert a pair they cannot read; refuse with the existing masking error.

## 6. Verification: the manual path is already built (2026-10-18)

* Backend: `POST /inv/entity-identities {a,b,reason}` (201), `POST /inv/entity-identities/{seq}/retract`, `GET` list/group with `at=`,
  mapping-Dataset import; pinned by `ControlApiEntityIdentityTest`, `ControlApiEntityIdentityImportTest`, resolve/merged tests.
* SPA: `LinkAnalysisIdentitiesComponent` (embedded in the Investigation tab) has the *Assert same entity* form (two typed keys, cross-type allowed),
  group lookup, and *Retract* per assertion; `InvService.assertIdentity` / `retractIdentity`.
* **Missing (the real residuals):** (1) no picking of two entities from the canvas/Working Set - analyst must type typed keys;
  (2) no one-step "assert then seal `resolve` at new head" - the analyst must add the `resolve` op separately, and nothing prompts it;
  (3) not verified here: whether assert REFUSES a pair of masked aliases the caller cannot read (existing tests cover masked READ keys);
  needs a real-HTTP probe before relying on the LA-19 rule above.
* Suggested smallest follow-up (SPA only, no backend): node-context action "Merge with..." on two selected nodes prefilling the existing form,
  followed by an offered `resolve` op. Not built: it is a UI change on a surface already shipped and needs the operator's confirmation first.

## 7. LA-19 probe result (2026-10-18) - FAILED, step 2 (SPA "Merge with...") NOT built

Approved order (pending operator confirmation): probe first; on failure stop and report. Probe in `ControlApiEntityIdentityTest`:
* PASS (kept): `retractResponseNeverCarriesARawMaskedKey` - retract is by seq; its response renders masked keys only.
* FAIL (committed `@Disabled`): `assertRefusesAMaskedAliasAndWritesNothing` - asserting `msisdn:masked:<16hex>` (the alias a masked
  analyst holds for a node) returns 201 and appends a permanent Space-wide `identity.asserted` fact on a garbage literal key.
  Not a read leak (the response re-masks it), but it is an integrity hole and it blocks "Merge with..." from selected nodes,
  because the SPA only holds aliases for masked nodes. No existing masking error code exists to reuse.
* Decision owed: refuse an alias-shaped value in `EntityIdentityRoutes.typedKey` (422, new reason text or a new error code), or
  have the server resolve the alias (reveal needs `canRevealLinkEntities`, in-Investigation only).

## 8. Alias refusal and "Merge with..." as built (2026-10-18; decisions pending operator confirmation)

* **Fix (fail-closed, refuse not resolve):** `EntityIdentityRoutes.refuseAlias` (uses `MaskTokens.TOKEN_PREFIX`, the predicate the other
  routes use) 422s an alias-shaped value in `POST /inv/entity-identities` (`a`/`b`) and in `POST /inv/entity-identities/import` (any Dataset
  row), BEFORE any fact is appended. Server-side alias resolution is declined (would need `canRevealLinkEntities`). Probes:
  `ControlApiEntityIdentityTest.assertRefusesAMaskedAliasAndWritesNothing`, `ControlApiEntityIdentityImportTest.aMaskedAliasInTheDatasetRefusesTheImportAndWritesNothing`.
  Other fact writers: retract takes a seq (safe, probe passes); resolve/seed carry no identity facts.
* **SPA:** selection-bar *Merge with...* (Investigation only; `mergePairOf`) prefills the existing assert form through
  `InvestigationSessionStore.mergeDraft` - nothing is written until the analyst adds a reason and confirms. After a successful assert in an open
  Investigation, *Add a resolve step* is OFFERED (`{op:'resolve'}`, added to the SPA op union; the server op already existed). Disabled with a
  tooltip when the capability is missing, the selection is not exactly two entities, or either is a masked alias.
* Residual: the toast tells the analyst to go to the Investigation tab; there is no automatic tab switch. The canvas button has no browser
  walk-through (unit-tested via `mergePairOf` and the identities component; `mergeUnavailable` itself has no spec).
