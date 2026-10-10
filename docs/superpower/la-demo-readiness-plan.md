# Link Analysis — demo readiness plan (defending the architecture page with the live application)

**Status: DRAFT 2026-10-10 — requirements filed, nothing built.** Written after a five-way audit of
[`okf/backend/modules/link-analysis.md`](../okf/backend/modules/link-analysis.md) (the claims) against the application
on `origin/master` (code, the live API of the Enterprise demo bundle on :8096, and the real UI driven as Demo Users).
Backlog rows: `LA-DEMO-SEED-1`, `LA-DEMO-DEFECTS-1`, `LA-DEMO-INDEX-1`, `LA-UI-MEMBERS-DRAFTS-1`,
`LA-UI-SETTINGS-MASKING-1`, `LA-UI-EVIDENCE-1`, `LA-ALGO-UI-PARITY-1`, `LA-DEMO-GUARDS-1` (all in BACKLOG §3.12).
This page lives in `docs/superpower/` while the work is in flight; when it ships, distil the as-built facts into
`okf/frontend/features/link-analysis.md` and archive it.

## 0. Operator decisions (2026-10-10)

| Question | Decision |
|---|---|
| Scope and date | **No date: the full plan** (all 40 requirements, no cut line); built in four waves, waves 1 and 2 started together |
| Masking in exploration views | **Mask the same way everywhere**: the canvas, node lists and exploration reads show aliases for masked types in `typed` / `all` mode, with a visible masking badge; seeding and expanding resolve through the alias (DR-D2) |
| Where the seeded demo lives | **A new `la-showcase` Space Template** (the tracked `demo` Space is not edited); runtime state (Investigation, members, lists, Case, bound rule) comes from an idempotent seed script if no template mechanism exists |
| Second approver | **`fm.manager`** holds Approve link expansions and is a reviewer on the seeded Investigation; `admin` keeps Reveal |
| Alert authoring | **`fm.analyst` also holds Author Alert Rules** in the showcase (one persona can run the whole story) |
| Link index default | **ON by default, product-wide** (a Space with a fresh published index serves reads from it; otherwise the flat read with a closed `Reason`; explicit `false` still disables). Not the recommended option (the alternative was showcase-only); it needs the full reactor gate and a staleness review |
| Demo variants | **la-app and Personal as launch configurations**; the Postgres store as a documented runbook step (no Docker automation) |

## 1. Verdict

**What a stakeholder can be shown today with no code change** (after seeding): the op log and its sealed replay (Replay
versus *Re-read* for drift), Dossier build and tamper-verify, masking with an audited Reveal, Entity Lists, identity
resolution, value Measures over the mule data, "Watch this measure" and standing detection with plain-language
refusals, the gate responses (401, 403, 404, 422) by `curl`, the route inventory (75) and the audit events.

**What the architecture page claims but the demo cannot show or cannot defend**, in order of damage:

1. **The Demo Space is empty.** No Investigation, Draft, member, pending request, Dossier, Entity List, identity group,
   Case, bound Alert Rule, Investigation Template, `la.index.build` Job or `la.detect` Job exists in any Space.
   `link-analysis.toon` does not exist, so four-eyes never triggers and `index.enabled` is false.
2. **"The index refreshes itself" is wrong as documented and untested.** `job.dataset.produced` is emitted only by the
   `sql.template` Job; a landed file emits `pipeline.commit`. No test runs commit → Job → index version.
3. **The index never engages in the demo**, the settings pane cannot enable it, the *Edge index* tool is a dead end
   from the empty state, and no screen says whether an answer came from the index or the Dataset.
4. **Members, Drafts, the Dossier bundle, `/compare`-from-the-UI and `/references` have no UI.** The page and the
   frontend concept describe Members and Drafts as built.
5. **Masking is visibly inconsistent** (canvas shows `MULE-HUB-01`, Working Set shows `masked:5b3d…`; after Seed the
   selection reads "not in the Working Set" and *Expand one hop* is disabled) and masking mode cannot be changed or even
   seen in the UI. A presenter will think the product broke.
6. **One BROKEN control:** *Compare and seal* reloads the whole page and loses state (reproduced twice).
7. **Four-eyes is a two-persona story that today needs `admin`**: only `admin` holds Approve and Reveal.
8. **Several claims are not demonstrable at all** (hash-chain internals, dependency fences, 10^8 scale): they need
   evidence slides and, for the fences, a named guard.

## 2. Defence matrix (claim group → how it is defended today → gap)

Status vocabulary: **READY** (UI, after seeding) · **API** (curl-able, no UI) · **DATA** (capability exists, demo has
nothing to show) · **UI** (backend exists, no UI) · **ROUGH** (works but a stakeholder would stumble) ·
**BROKEN** · **EVIDENCE** (not live-demonstrable; defend with a test or slide) · **DOC** (the page is wrong).

| # | Claim group (page §) | Defended today by | Status | Gap → requirements |
|---|---|---|---|---|
| 1 | Append-only sealed op log, 14 ops, pure fold, replay without Dataset (§3.1, invariant 2) | UI: Investigation tab ops ladder, *Replay*, *Re-read (drift check)* | READY after seed; ROUGH (Compare BROKEN, seed/expand toggle) | DR-S1, DR-D1, DR-D3, DR-T8 |
| 2 | Hash seal and Dossier verify (§3.1, outputs) | UI: Dossier build / Verify / download; tamper = edit manifest, upload | READY after seed | DR-S1; bundle: DR-U6 |
| 3 | Masking: tokens out, raw stays inside; `typed` default; Reveal audited (§3.4, invariant 1) | UI: Working Set tokens, Oversight Reveal (`admin`) | ROUGH | DR-D2, DR-U3, DR-T4 |
| 4 | Four-eyes: sensitive Expand is pending, requester cannot decide (§3.1) | UI: Oversight Approve/Deny | DATA (thresholds blank; only `admin` can approve) | DR-S2, DR-S6 |
| 5 | Members and roles; 404-as-absence; 403 after R3/PDP (§3.1, §6.1) | API: members routes; gate probes need a seeded Investigation | UI missing; DATA | DR-U1, DR-S1, DR-S8 |
| 6 | Drafts: fork, ops, rebase, promote, states, caps (§3.3, §9) | API only | UI missing | DR-U2, DR-T7 |
| 7 | Identity Fact log, Entity Lists, resolution groups, Entity Types, sidecar (§3.5) | UI: Entity Lists, Identity panels; head hash only via API | READY after seed; UI missing for chain/head and import | DR-S4, DR-U8, DR-T10 |
| 8 | Store port: FS default, Postgres (Preview/Enterprise), crash-safe promote (§3.2, §8) | Tests; active backend not visible | EVIDENCE; DATA (PG variant) | DR-S9, DR-U3 |
| 9 | Link index: layout, modes, staleness, limits, scheduled build (§4) | Settings API; Edge index tool (unreachable) | DATA; DOC (trigger); UI missing | DR-T1, DR-U4, DR-U5, DR-U10, DR-S2, DR-S3 |
| 10 | Graph Run: Materialised vs IndexRef, 28 algorithms, budgets, pool, cache (§5, §9) | UI: 18 *Run on server*, 3 *Run on index*; catalogue by API | ROUGH (7 API-only; server tools gated by the query-graph toggle) | DR-U11, DR-D3, DR-T7 |
| 11 | HTTP surface 75 routes, capabilities, gate order, audit (§6, §6.1) | curl probes (401/403/404/422), route inventory, `LINK_*` events | READY as `curl`; DOC fixed | DR-T2, DR-T5, DR-S7, DR-S8 |
| 12 | Editions and packaging: optional jars, `-Ui la-app`, Postgres/PDP only Preview+Enterprise (§8) | Enterprise demo only | DATA (Personal 503, la-app, PG variants) | DR-S9 |
| 13 | Live detection: bound rule, standing detection, `la.detect`, refusal codes (§7) | UI: Value Measures, *Watch*, standing detection, Monitoring | DATA; ROUGH | DR-S3, DR-S5, DR-D6, DR-U9 |
| 14 | Resource bounds: rate limit 20 + 1/3 s, pool 2/16, builds 1/4, Drafts 50 (§9) | API numbers; UI has no 429/413 wording | API; UI missing | DR-D6, DR-T7 |
| 15 | Scale: proven to 10^8 edges, 10^9 extrapolated (§10) | Roadmap measurements (synthetic corpus) | EVIDENCE | slide only; DR-T9 |
| 16 | Architecture fences: ports inward, la-graph leaf, engine names no la-* class (§2) | `tools/check-module-deps.mjs`, maven-enforcer | EVIDENCE; engine rule unguarded | DR-T3 |

## 3. Requirements

Priority is for a stakeholder demo: **MUST** (the story fails without it), **SHOULD**, **COULD**. Each has an
acceptance test a reviewer can run. "→ row" names the BACKLOG row that carries it.

### 3.1 Seed and environment (`LA-DEMO-SEED-1`, P2)
| ID | Requirement | Acceptance | Pri |
|---|---|---|---|
| DR-S1 | A seeded Investigation over `mule_transfers_dataset`: ≥ 8 steps (seed `MULE-HUB-01`, expand, exclude with reason, annotate, threshold, window, resolve, marker) and members: `fm.analyst` lead, `ra.analyst` reviewer, `admin` reviewer, `demo.manager` non-member | list as `fm.analyst` returns it; the same id as `demo.manager` is 404; a mutating call as `ra.analyst` is 403; Dossier verifies | MUST |
| DR-S2 | A seeded `link-analysis.toon`: explicit `maskingMode`, a low four-eyes fan-out threshold, `index.enabled`, Drafts limits | expanding the hub creates a pending request; `GET /inv/index` says `enabled:true` | MUST |
| DR-S3 | Seeded Jobs: `la.index.build` on `mule_transfers_dataset` triggered by `pipeline.commit` (guard on the Pipeline), `la.detect` on a short cron; a staged next-day file and a gap-day variant | copying the next-day file yields a new index version and, with DR-S5, an Alert, without a click | MUST |
| DR-S4 | Seeded Case assigned to `fm.manager` and linked; an Investigation Template; Entity Lists (the SMURF accounts, one range or CIDR entry, one expiring entry); one identity assertion | `fm.manager` reads the linked Investigation; the list and group are visible | SHOULD |
| DR-S5 | A bound pass-through Alert Rule with standing detection enabled (owner `admin`, a `user` share on the Dataset, not a role share) | `la.detect` run produces a sweep event and an Alert; editing the rule produces `NOT_ENABLED` | MUST |
| DR-S6 | A second approver persona: a non-`admin` persona holds Approve link expansions (and the reviewer membership); optionally an analyst holds Author Alert Rules | requester cannot approve own request; the second persona can | MUST |
| DR-S7 | A Space with the Link Analysis feature switched off in `modules.toon` | its `/inv/*` returns 404 `MODULE_DISABLED` while its other routes work | SHOULD |
| DR-S8 | A sample policy (Enterprise PDP) denying an Investigation read for one persona | the denied persona gets 404 even as a member | SHOULD |
| DR-S9 | Demo variants as launch configurations: la-app, a Personal bundle (absent-module 503), a Postgres-store run (`-Dinvestigations.backend=db`) | each starts and answers the probes below | SHOULD |
| DR-S10 | Demo data tuned so the planted hub ranks in the top 3 of *Suspicion score* and the planted ring is the first cycle shown | `MULE-HUB-01` in top 3; `SHELL-A→B→C→D→A` listed first | MUST |

### 3.2 UI defects found by driving the application (`LA-DEMO-DEFECTS-1`, P2)
| ID | Defect (observed) | Requirement | Pri |
|---|---|---|---|
| DR-D1 | *Compare and seal* reloads the page and loses state (plain form submit; reproduced twice) | the control never navigates; a spec drives a real click | MUST |
| DR-D2 | Masking inconsistent: canvas and node list real ids, Working Set / log / Dossier tokens; selection "(not in the Working Set)" after Seed | one rule everywhere with a visible "Masking: typed/all/none" badge and "alias (masked)" rows; the selection resolves through the alias | MUST |
| DR-D3 | The *Show the query graph* checkbox gates Seed, Expand one hop and every server tool; the message "open or start an Investigation first" is wrong when one is open | untick automatically after Seed; the message names the real reason | MUST |
| DR-D4 | Value Measures: free-text columns, ISO date text, *Run Measure* stays enabled with a stale error; a 3650-day window is refused at 31 days | prefill Dataset and columns from the Investigation, date pickers, disable while invalid, state the 31-day cap up front | MUST |
| DR-D5 | *Find paths (server)* lists 16 identical rows; *Cycles* buries the ring; *Pattern match* with no motif lists every edge | de-duplicate by node sequence; sort cycles shortest first; preselect a motif | SHOULD |
| DR-D6 | *Watch* shown to users without Author Alert Rules (403 leaks the capability id); a name with spaces fails silently; *Show sweep counts* prints nothing; template instantiate needs a typed id; 413 and 429 have no sentence | hide or disable by capability; inline name error; "0 sweeps"; template picker; plain 413/429 messages with the limit and a next step | SHOULD |
| DR-D7 | Start Investigation does not prefill the time column | prefill from the Dataset's time column | SHOULD |
| DR-D8 | *Re-order steps…* silently forks | label says it creates a fork | COULD |

### 3.3 Index story (`LA-DEMO-INDEX-1`, P2)
| ID | Requirement | Acceptance | Pri |
|---|---|---|---|
| DR-T1 | An integration test: a committed ingest batch fires a configured `la.index.build` Job and an index version appears; the next read is served from it | the test fails if the trigger is `job.dataset.produced` and passes with `pipeline.commit` | MUST |
| DR-U4 | Answers say where they came from: "answered from index vN" or "answered from the Dataset because ‹reason›" on paths, neighbours and Expand; step detail "read from index vN" | visible on all three; fallback reasons are the closed list | MUST |
| DR-U5 | *Edge index* works from the empty state (first build) and labels index versus flat; a settings control enables it | enable → build → advice → read from index, all in the UI | MUST |
| DR-U10 | The Jobs editor offers `la.index.build` and `la.detect` with their fields | author both without hand-written TOON | SHOULD |

### 3.4 UI that does not exist
| ID | Requirement | Row | Pri |
|---|---|---|---|
| DR-U1 | Members panel: role chips, grant, revoke, last-lead refusal, lead only | `LA-UI-MEMBERS-DRAFTS-1` (P2) | MUST |
| DR-U2 | Drafts panel: fork, working set, conflicts, rebase, promote, discard, state badge | same | SHOULD |
| DR-U3 | Settings > Link Analysis edits masking mode, shows Entity Types, `index.enabled`, `graphRun` budgets, `max_disk_bytes`, `max_set_bytes`, `max_investigation_bytes`, active store backend, per capability | `LA-UI-SETTINGS-MASKING-1` (P2) | MUST |
| DR-U6 | Dossier bundle download and verify | `LA-UI-EVIDENCE-1` (P3) | SHOULD |
| DR-U7 | `/references` UI; Compare through the UI works (DR-D1) | same | SHOULD |
| DR-U8 | Entity Lists shows head hash and "chain verified"; identity import UI | same | SHOULD |
| DR-U9 | A fired standing-detection Alert names the breaching entities and links to the Investigation | same | SHOULD |
| DR-U11 | All 28 algorithms reachable (7 are API-only: connected components, k-core, triangle count, isForest, descendants, Jaccard, PageRank); server run available below the browser cap | `LA-ALGO-UI-PARITY-1` (P3) | SHOULD |
| DR-U12 | An installed-modules view filtered to Link Analysis | same | COULD |

### 3.5 Tests and guards that keep the defence true (`LA-DEMO-GUARDS-1`, P3)
| ID | Requirement | Pri |
|---|---|---|
| DR-T2 | A test pins the route count and operations to the manifests (75) so the page cannot drift | MUST |
| DR-T3 | A guard: no `com.gamma.la.*` or `com.gamma.geolink.*` import in `inspecto/src/main` | SHOULD |
| DR-T4 | A test scans every `/inv` response on a masked Space for known raw ids | MUST |
| DR-T5 | A demo smoke runner executes the scripted probes against the seeded demo and asserts each status (401, 403, 404, 404 `MODULE_DISABLED`, 422, replay hash unchanged after a Dataset change, tamper verify fails, route inventory 75) | SHOULD |
| DR-T6 | A pre-demo check reproduces the manual's §10 figures on the mule data (13 accounts near 0.985, `TILL-06` at 82 %) | MUST |
| DR-T7 | Burst scripts for the limits: > 20 POSTs → 429, 19 concurrent runs → 503, a 6th concurrent build → 503 | SHOULD |
| DR-T8 | A replay-versus-drift script: append a file to the bound Dataset; Replay hash unchanged, *Re-read* reports drift | MUST |
| DR-T9 | `/inv/index` exposes bytes, per-table file counts and the dropped-NULL count | COULD |
| DR-T10 | A tamper-evidence script for the Identity Fact log on a disposable Space | SHOULD |

## 4. Demo script (what the system can run once the MUST items land)

| Step | Persona | Action → expected | Needs |
|---|---|---|---|
| 0 | — | Smoke runner passes; modules ACTIVE; route inventory 75 | DR-T5, DR-T2 |
| 1 | `fm.analyst` | *Follow the money* card → 170 nodes, 1,803 links; shortest path `SMURF-01`→`RELAY-01` via `MULE-HUB-01`; cut points = `MULE-HUB-01`; centrality `ACC-1008` | ready |
| 2 | `fm.analyst` | Open the seeded Investigation; replay equal; append the next day's file; replay still equal, *Re-read* shows drift | DR-S1, DR-T8 |
| 3 | `fm.analyst` | Value Measures pass-through: 13 accounts flagged on the shipped la-showcase data at default thresholds (`SHELL-A` 1.08, the two relays 0.985, `MULE-HUB-01` 0.979; the DR-T6 test pins these) | DR-D4, DR-T6 |
| 4 | `fm.analyst` | Masking: Working Set shows tokens; `admin` Reveal is audited | DR-D2 |
| 5 | `fm.analyst` + second approver | Expand the hub → pending; requester cannot approve; approver does | DR-S2, DR-S6 |
| 6 | any | Dossier build/verify; tamper one hash → verify fails | ready |
| 7 | `curl` | 401 no token; 403 `ra.analyst` on identities (`fm.manager` holds Manage Incidents, so it reads them); 404 non-member; 404 `MODULE_DISABLED` on the switched-off Space; 422 bad id | DR-S7, DR-S1 |
| 8 | `admin` | Watch the measure, enable standing detection, run `la.detect`; Alert fires; edit the rule → `NOT_ENABLED` | DR-S3, DR-S5, DR-U9 |
| 9 | `admin` | A new day lands → `pipeline.commit` → index version N+1 → *Edge index* shows it; a path answer says "from index vN" | DR-T1, DR-U4, DR-U5 |
| 10 | slides | 10^8: full build 852 s, append 31 s (synthetic corpus, 1.8 M nodes); 10^9 is an extrapolation | evidence |

**Seed status (2026-10-10, the `la-showcase` template + `tools/seed-la-showcase.mjs`; see `docs/okf/capabilities/spaces/spaces.md` 3.5.5).** DR-S1..S6, S7 (`la-showcase-off`),
S8 (inert sample policy), S9 (launch configs `inspecto-la-app`, `inspecto-personal`; Postgres runbook step) and S10 (`LaShowcaseGoldenTest`) are built; the seed script is unit-tested
against a mocked `fetch` and not yet run live. Steps that now have their data: 2 (the seeded Investigation; the staged next-day file), 5 (`fm.manager` is the second approver), 7
(`la-showcase-off`), 8 (bound rule + `la_detect`), 9 (`la_index_build` on `pipeline.commit`). Steps still waiting on UI or behaviour lanes: 3 (DR-D4), 4 (DR-D2), 8 (DR-U9), 9 (DR-U4, DR-U5). The
showcase corpus is smaller than the `demo` Space's: step 1's figures (170 nodes, 1,803 links, centrality `ACC-1008`) are for `demo`; on the showcase the hub ranks first.

## 5. Corrections already applied to the page (2026-10-10, verified in code)
Trigger signal is `pipeline.commit` for file ingest (also fixed in `ingestion.md` and the frontend concept); 75 routes
(69 + 6); Identity reads are capability-gated and `/import` has no UI; Graph Run and Index capabilities gate only the
starting POST (cancel is owner-or-admin); gate order gains the idempotency step and the linked-Case READ path; rate
limit numbers (capacity 20, refill 1 per 3 s); Standard is a Professional alias; the Postgres store needs
`-Dinvestigations.backend=db`; Drafts heavy-op permits are `max(1, min(4, cores/3))`; cost class is a hint and the node
ceiling is the inline-versus-job threshold; `REJECTED` is HTTP 503; defaults are Space settings; the filesystem path is
`audit/snapshots/investigations/<id>/`; "engine names no la-* class" is grep-true but unguarded.
**Still to correct in code comments (needs the reactor gate):** the `la.index.build` trigger comments in `JobService`
and `LaIndexBuildJob` still say `job.dataset.produced`; the evaluator "twelve ops" Javadoc; the FS layout Javadoc;
`graph-analysis.ts` "25 of the 27 algorithms".

## 6. Method and limits of this audit
Five read-only audits ran in parallel on `origin/master` (about 220 claims and capabilities): domain core, link index
and Graph Run, HTTP surface and gates, end-to-end data path and outputs, and a UI walkthrough as RA Analyst, FM Analyst
and Admin (Manager personas were not driven). API probes were GET-only plus the Demo User token exchange. The UI audit
created test data in the Demo Space (two Investigations, one Entity List) and it was removed afterwards. Not verified:
the Postgres backend live, the 429 and 503 bursts, the `MODULE_DISABLED` and PDP paths (no fixtures exist), the
Edge-index first-build branch, and Alerts rendering of a fired standing-detection Alert.
