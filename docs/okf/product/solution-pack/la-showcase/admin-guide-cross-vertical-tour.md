# Inspecto LA: the 30-minute cross-vertical tour (mobile money, audit, claims)

*Status: written 2026-10-24 from a full browser rehearsal on one locally packaged Demo Auth bundle (`package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo`, build `31539d9c5`, `serve-demo.bat` on a free port with `-Dla.embed.enabled=true`), driven as the real Demo Users in the built-in browser. Row `LA-SHOWCASE-REHEARSAL-1`. Every figure below was read on screen in that pass unless marked **not driven**. The items left then were driven on 2026-10-28 on the reorganized Studio (section 5). Synthetic data only; the plants are planted, so no figure here is a detection rate.*

The tour shows three verticals back to back on **one server**: A [`la-mobile-money`](../la-mobile-money/admin-guide-showcase-runbook.md) (the agent who never sleeps), B [`la-audit`](../la-audit/admin-guide-showcase-runbook.md) (the vendor and the journal), C [`la-claims`](../la-claims/admin-guide-showcase-runbook.md) (one garage, one lawyer, twelve strangers). The per-vertical runbooks stay the detailed scripts; this page is the order, the clicks that worked and the numbers to expect. Beats: the ladder and masking (A), four-eyes (A), the algorithms (A, B, C), the Dossier tamper (B), the embed (C), Alert to Case (A).

## 0. Set-up (once, before the audience arrives)

| # | Step | Expect |
|---|---|---|
| 1 | One bundle: `inspecto\package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo` (unzip or use `inspecto-demo\`, inside the checkout) | `serve-demo.bat`; `-NoBakeDemo` because the bake needs an interactive console |
| 2 | Lay the four Spaces down offline: `node tools/seed-la-showcase.mjs --scaffold <bundle>\spaces`, then `seed-la-mm.mjs`, `seed-la-audit.mjs`, `seed-la-claims.mjs` the same way | `la-showcase`, `la-showcase-off`, `la-mobile-money`, `la-audit`, `la-claims`; the Demo User roster merges (no id is defined two ways) |
| 3 | Start with `PORT=<free>` and `INSPECTO_JAVA_OPTS=-Dla.embed.enabled=true`; wait for the ingest | `/health` 200 |
| 4 | **The seeds press Run on their link Job themselves** on a fresh Space (`mm_links` as `mm.manager`, `claims_links` as `siu.manager`, `ap_links` as `audit.manager`) and wait for its rows. Then `la_index_build` in each Space as the **manager** persona (`admin` has no `canOperateRuns`: 403) | Run `SUCCESS`; a re-run of a seed skips what exists (the identity is matched on its reason) |
| 5 | Seed each Space `--base http://127.0.0.1:<PORT> --apply`, then run the smokes | rehearsal: `la-demo-smoke` 16/16 (20/20 with `--staged --inbox --embed`), `la-mm-smoke` 10/10, `la-audit-smoke` 30/30, `la-claims-smoke --embed` 32/32 |

Two things the audience must not see go wrong:

* **The app stays in the Space you used last.** After signing in as a different persona press the header button *Link Analysis <space>, switch space* and pick the persona's Space. A deep link `…/link-analysis?investigation=<id>` is the fast way back in.
* **Date fields in Value Measures and the *Expand* settings.** Type numbers in the field (works); the two date boxes are native date inputs and took a typed date only when set through the field's own change event in this pane, so prepare the window beforehand or pick it with the date picker.

## 1. The run of show (30 minutes)

| Min | Beat | Vertical, persona | One line to say |
|---|---|---|---|
| 0:00 | Sign in, switch Space | A, `mm.analyst` | "Five Spaces on one server; each persona sees its own." |
| 0:01 | **Ladder and masking** (3) | A, `mm.analyst`, then `admin` | "The analyst sees structure, not wallets; Reveal is `admin`'s, audited." |
| 0:04 | **Four-eyes** (3) | A, `mm.analyst`, `mm.manager` | "Unbounded expand waits for a second person." |
| 0:07 | **Algorithms A** (4) | A, `mm.analyst` | "Client first, then the same answer from the server." |
| 0:11 | **Algorithms B** (4) | B, `audit.lead` | "The cut vertex, not the score, is the finding." |
| 0:15 | **Algorithms C** (4) | C, `siu.analyst` | "Nine server runs; the ring is a community, the busy clinic is not." |
| 0:19 | **Dossier tamper** (4) | B, `audit.lead` | "One flipped digit, and Verify says which part." |
| 0:23 | **Embed** (3) | C, `admin` | "A read-only masked view in the partner's own page; revoke and it is gone." |
| 0:26 | **Alert to Case** (2) | A, `mm.manager` | "Aggregate only; the Case holds the Investigation." |
| 0:28 | Close (2) | all | claims we do not make (section 4) |

### 1.1 Ladder and masking (A, 3 min)

* **Click:** Space *mobile money*; `?investigation=mm-structuring-case` (8 steps: seed, bounded expand, exclude with a reason, annotate, threshold, window, resolve, snapshot). Since the 2026-10-28 Studio reorganization there is no *Connect several numbers* field: seeds come from the query graph (Investigation tab > *Show the query graph (to pick seeds)*, pick the node, *Seed*), and the expand is the *Expand…* dialog (From *The whole Working Set*, a fan-out, the degree). Add `HUB-01`, `AG-39`, `AG-18`, `LOOP-1`, `DORMANT-1` that way (Working Set 8 to 11), then expand the whole Working Set three times at fan-out `30`.
* **Expect (read):** 11, then 43, 96, **300 entities / 460 links** (2026-10-24 figures). Every id is `masked:<hex>` under `typed`. **Aliases are keyed per Investigation**, so the same wallet has a different alias in each Investigation and another in saved views and Entity pages (the Space key): never quote an alias across them. To find a named entity in a masked saved view, open *Entities* > the type > type the raw value: the profile's address is its Space alias. As `admin` (or any role with `canRevealLinkEntities`), the Investigation's *⋯* > *Settings…* > *Investigation settings* > *Oversight and approvals* > *Masked entities* > *Reveal* answers inline "`→ MER-ELECTRIC (reveal audited)`" (it is an inline reveal, not a dialog).
* **Trap:** *Members* and *Share and embed* are separate folds of *Investigation settings*; target the embed *Revoke* in its own fold. Revoke and Discard ask to confirm since 2026-10-27 (`LA-REVOKE-DISCARD-NO-CONFIRM-1` closed).

### 1.2 Four-eyes (A, 3 min)

* **Click:** the seed leaves a pending request `p1` ("exceeds the Space's four-eyes limit: maxFanOut unbounded (threshold 100)"). Sign in as `mm.manager`, open the same Investigation, *⋯* > *Settings…* > *Investigation settings* > *Oversight and approvals*: *Approve*.
* **Expect (read):** nothing in the log until the approval; then 300 to **494 entities / 1,436 links**, step "approved by mm.manager". The requester sees "Waiting for a different person with the approve-expansions capability"; `audit.lead` and `audit.committee` self or read-only approve answers 403 `canApproveLinkExpansions`.
* **Four-eyes on the *Investigate* card:** the card sends the active Domain Profile's expand presets; these Spaces set no profile fan-out, so its one-degree expand is held above `four_eyes_fan_out_above` and the degree line says to ask an approver or use *Expand…* with a smaller fan-out. For a solo demo, open a seeded Investigation or set a fan-out first.

### 1.3 Algorithms (A, B, C; 12 min). Client first, then **Run on server**, then compare

Since 2026-10-28 the Analysis tab is **one catalogue** with a *Browser* / *Server* toggle at its top: run a card on *Browser*, flip to *Server*, open the same card and press *Run on server*. Server cards need an open Investigation (on a saved view they say "Running on the server works on an Investigation's Working Set"). Pickers list masked aliases (see 1.1).

Run times are the server's own "Ran on the server in N ms" line.

| Vertical, scope | Algorithm | Client | Server | Parity |
|---|---|---|---|---|
| A, `mm-structuring-case`, 300 entities | Degree centrality | `MER-ELECTRIC` 67, `HUB-01` 26 | - | benign hub first |
| A | PageRank | same top six order (shows 0.06, 0.04, 0.04: two decimals) | 12 ms, 0.0556, 0.0392, 0.0351 | order identical |
| A | Find cycles | one cycle, 4 nodes (the `LOOP-` wallets) | - | matches runbook M-5 |
| A | Suspicion score | biller first (100), `HUB-01` third (40) | - | runbook M-6 |
| A | Value Measures, Structuring, band 4 750 to 5 000, 2026-10-01 to 2026-10-15, min payers 10, min legs 10 | - | 1 entity, 25 legs, 25 payers, 121 726.88 | runbook M-3 |
| B, `approval-control-review`, 163 entities, 281 links | Cut points | 18 nodes, 41 bridges | Articulation points 18; Bridges 41 | equal |
| B | k-core | top core 3 | top core 3 | equal |
| B | Maximum spanning forest (Strongest backbone) | 162 links over 163 nodes | 163 nodes, 162 links | equal |
| B | Link prediction | 20 predicted, top three 18.2 | 20 predicted, 4 ms | equal count |
| B | Betweenness | - | 42 ms, top 7 557.29 | benign hub V-001 first |
| B | Is forest | **no client control**: the client test only gates the tree layouts | No. | see below |
| B, `gl_intercompany` view, 8 nodes, 72 links | Find cycles | `LE-05 → LE-06 → LE-07 → LE-08 → LE-05` | the same (1 selection, 4 nodes) | equal |
| B | Maximum flow `LE-05` to `LE-07` | **3**, min cut 3 links | **3**, min cut 1 link (6-node Working Set) | equal; counts links, not money |
| C, `ring-case` rebuilt: seed `CL-0134`, fan-out 40, expand twice | Working Set | 21 entities, 101 links | | matches runbook |
| C | Louvain | 4 communities 8 / 5 / 5 / 3 | 4 communities, same sizes, 0 ms | equal |
| C | Label-propagation | 1 community of 21 | 1 community of 21, 2 ms | equal |
| C | Cliques | largest 7 | 28 groups, largest 7 | matches runbook |
| C | Triangle count | - | `CL-0134` 67 first, then 56, 56 | first matches |
| C | PageRank / HITS / Eigenvector / Katz / Closeness | - | PageRank 0.1862, 0.1556, 0.12; HITS hubs `CL-0134` first; Eigenvector `CL-0134` 0.3842; Katz `CL-0134` 1.85e12 (read the order); Closeness `CL-0134` and one provider tie at 0.7692; all 0 to 3 ms | tie matches runbook |

**Is forest (B S5).** The Analysis tab has no client *Is forest* button. The client computes it only to enable the tree layouts in *Graph layout*; the answer you can show on screen is the server run (*All algorithms (server)* > Is forest: "No." on the approval graph) and the greyed tree layouts. Say that, do not promise a client click.

**Scope matters for C.** The runbook's "one community of 12 claimants and three providers" is the **whole-graph** golden test. In the application Working Set of about 21 entities the same Louvain run gives **four** communities and Label-propagation **one**; both are consistent client and server. To show the ring as one community, name it in the Working Set of the dedicated `ring-case` Investigation and say the figure is from the whole-graph test (`LaClaimsGoldenTest`).

**Maximum flow is a count of transactions.** B's "7 200 000" in the audit runbook is the sum of three journals; the application answers 3. The amount view is a Value Measure, not Maximum flow.

### 1.4 Dossier tamper by upload (B, 4 min)

* **Click:** as `audit.lead`, `?investigation=vendor-v117-workpaper`, *⋯* > *Settings…* > *Investigation settings* > *Dossier* > *Build dossier* > *Verify this manifest*. Then *Verify an uploaded manifest…* with a manifest file in which one hex digit of an artefact hash is changed (prepare it beforehand; the manifest is the `manifest` member of `GET …/dossier`).
* **Expect (read):** the untouched manifest: **Verified**, "Self-consistent yes", Changed / Missing / Added / Content changed all "none" (root `sha256:00e09f6c…` at step 7). The edited file: **NOT verified**, "The manifest's root does not match its own body", Self-consistent "no", **Changed: `log.jsonl#2`** (the part whose digit was flipped). On A the same flow showed "Verified" for the built Dossier (root `sha256:d5643c0c…`) and NOT verified for an edited upload.
* **Drafts and Members on the same Investigation (extra):** the seeded Draft: *Conflicts* "No conflicts", *Rebase* "Rebased onto step 6", *Discard*; a fresh *Fork a Draft* > *Work on this Draft* > *Mark this position* > *Promote* made step 7 on the Investigation. Members: *Grant* `audit.outsider` as analyst, then *Revoke* (no confirmation).

### 1.5 Embed (C, 3 min)

* **Click:** as `admin` (a lead member) in the claims Space: *⋯* > *Settings…* > *Investigation settings* > *Share and embed* > *Create link*, then *Create link* in the dialog (label, contents, masking, lifetime; the link is shown once; the viewer page ships in the `-Ui la-app` build only: the console opens it as "Page not found", defect `LA-CONSOLE-EMBED-VIEWER-1`; it looks like `…/embed/inv/<grant>.<secret>`). Open it in a second window with no session, then *Revoke* and confirm.
* **Expect (read):** the viewer opens at `/embed/inv/shared` (the token is scrubbed from the address bar): title "Staged-accident ring (masked:…)", "Read-only view at step N. Entity ids may be masked", graph and Working Set table, Notes. With the Space allow-list (`127.0.0.1:8099`), `python -m http.server 8099 --bind 127.0.0.1` in `spaces/_templates/la-claims/portal` and `partner-portal.html?host=http://127.0.0.1:<PORT>&link=<grant>.<secret>`, the partner page frames the same masked view (44 entities on 5 rings). After the revoke the public read is **404**. On A the same Share panel and viewer worked (494 entities, step 13); the panel's "0 views" does not refresh until reload (the server counted 1).

### 1.6 Alert to Case (A, 2 min)

* **Click:** as `mm.manager`, left rail *Alerts*: `mm-structuring` CRITICAL OPEN, "Entities breaching structuring in mm-structuring-case is 1" (aggregate, no wallet). Row actions *Open Investigation* (lands on `mm-structuring-case`), *Acknowledge*, *Resolve*. The Investigation header shows "Linked to Case CASE-…" with *Unlink*.
* **Trap (defect row `LA-BOUND-RULE-AUTHOR-READONLY-1`):** the Value Measures *Watch* line and the bound Alert Rule say "your role cannot author Alert Rules" for `mm.analyst` and `fm.analyst`, although the API lists `canAuthorAlertRules` among their permissions.

## 2. Panel checklist (what each persona can reach)

| Panel | Where | Rehearsal result |
|---|---|---|
| Graph page, saved views | start card or *Open saved views* > the view > *Load view* | A `mm_money_trail` 465 nodes, 4 000 links, "Truncated" banner; B `gl_intercompany` 8 / 72; C `claims_network` 388 / 2 574 (force, hulls), `claims_provider_money` 320 / 806 (drawn as a vertical sliver under dagre on 2026-10-24; force since 2026-10-28, `LA-CLAIMS-PROVIDER-MONEY-LAYOUT-1`) |
| Domain profile | the Query panel picker | the start page offered "Mobile Money" on A; after *Follow the money* it read "finance" (defect row `LA-SAVED-VIEW-DOMAIN-PROFILE-1`) |
| Draft, Members | Investigation tab | B: all of 1.4; A: Members shown, Drafts "No Drafts" |
| Settings | left rail > Settings (admin) | Link Analysis group (masking mode, link index, propagated-risk weights, embed lists, Domain profile, category lists) and *Analysis limits* (about 20 groups: Canvas, Investigation, Traversal, Dossier, Identity, Upload…). Editing worked: masking `all`, save ("Link Analysis settings saved"), "In force: all", then back to `typed` |
| Entity profile | left rail > Entities > Party > type `V-117` > Enter | opens `/entities/party/masked:af93…`, first seen 15 Dec 2022, 40 active days of 597; the weekly grid prints "not read" outside the newest 400 days |
| Reveal | Investigation tab > Oversight > Masked entities | inline, audited (1.1) |
| Run-on-server Jobs | the nine in 1.3 (C) | all finish in 0 to 3 ms on 21 entities |

## 3. Everything the three verticals share

* One server holds all of them; the Demo User picker lists every persona of every Space (the outsiders are told apart: `audit.outsider` is "Demo Outsider", the claims `outsider` is "Demo Claims Outsider").
* Typed masking everywhere: raw ids only through the audited Reveal. Free text is not masked: the claims references and the Investigation title carry ids typed by the seed.
* Link Jobs first, index second: `mm_links` / `ap_links` / `claims_links`, then `la_index_build` (a cron or the produced-Dataset signal).

## 4. Claims we do not make

No precision or detection rate (the plants are planted); the 40x dormant-wake figure of M-10 is arithmetic from the KYC reference (prior volume 150.00) and the six legs of 11 and 12 October: the Entity profile of `DORMANT-1` shows opened 2 Nov 2025, active on 11 and 12 Oct with six rows each, not the 150.00; the **client versus server parity** above is for the Working Set shown, not for the whole graph.

## 5. Rehearsal record

**2026-10-24** (first pass): everything in sections 1 and 2 above.

**2026-10-28** (the items left on 2026-10-24), driven by the assistant alone in the built-in browser on the Advanced Demo Auth console bundle of current master (the reorganized Studio at `/studio/link-analysis`), a second server on `:8099` over a fresh Spaces root laid down by `seed-la-mm` / `seed-la-audit` / `seed-la-claims --scaffold`, then `--apply` (all three seeds created everything; `seed-la-mm`'s optional Alert stage warned that no `mm-structuring` Alert appeared after 20 polls of `la_detect` and was skipped).

| Vertical | Item | Result | Evidence (what the screen showed) |
|---|---|---|---|
| A | Shortest path `HUB-01` to `AG-39` | **PASS** | Investigation `rehearsal-chain` (seeds `HUB-01`, `AG-39`, three bounded expands at fan-out 100, the smoke's set-up; 219 nodes, 277 links). Browser: 4 nodes, 3 hops. Server: "Ran on the server in 3 ms over 219 nodes and 277 links", 4 nodes and 3 links selected. Runbook M-4: 3 hops |
| A | Maximum flow `HUB-01` to `AG-39` | **PASS** | Browser (the max-flow and backbone card): "Max flow = 25; 2 min-cut link(s) highlighted". Server: 3 ms, "Maximum capacity between the two nodes: 25", minimum cut 2 links. Runbook: 25 |
| A | M-10 `DORMANT-1` Entity profile | **PASS** (as documented) | *Entities* > Wallet > `DORMANT-1`: first seen 2 Nov 2025, last seen 12 Oct 2026, 3 active days of 389 (1 row on 2 Nov, 6 on 11 Oct, 6 on 12 Oct); *Where* lists `mm_kyc_dataset.wallet_id` 1 row. No 150.00 anywhere on the profile: the 40x stays the presenter's arithmetic (section 4) |
| B | Link prediction on `ap_vendor_identity` (`V-136`) | **PASS** | View drawn 291 nodes, 1 787 links. Browser *Predict links*: 13 pairs; `V-136` is proposed with `V-134`, `V-135`, `V-133` and the account `BA-7701` at 1.12 (ranks 8 to 11; seven unrelated pairs at 1.44 rank above, because this view also carries `paid_to`). The runbook's "top three" is the shares-links-only server run |
| B | Find similar / Jaccard from `V-131` | **PASS** (order) | Browser: `V-132` 0.71; `V-133`, `V-134`, `V-135`, `BA-7701` 0.57; `V-136` 0.13 last. The runbook's 1.0 / 0.33 is on the approved plus paid_to graph; this view's scores differ, the order holds. Server Jaccard needs an Investigation (not run on the view) |
| C | `claims_shared_identity` | **PASS** | 227 nodes, 343 links, community hulls drawn (runbook: 227 / 343) |
| C | Geo View `claims_junction_cluster_map` | **PASS** | *Geo Map Analysis* > saved views > *Load view*: one cluster badge **11** at the junction (`claims_night_unreported_dataset`, lat/lon, entity `accident_id`); runbook: 11 |
| C | Identity resolution `CL-0332` + `CL-0141` | **PASS** | Investigation `rehearsal-identity` as `siu.analyst`: seed both, *Expand* (fan-out 40), *Resolve*: Working Set **12 entities, 12 links**; the two records share one identity group. Runbook: 12 |

Click-path notes from this pass (the text above is updated): no *Connect several numbers* (seed from the query graph); one Analysis catalogue with the *Browser* / *Server* toggle; server cards need an Investigation; aliases are per Investigation, so a named entity in a masked view is found through its Entity page. No product defect was found; the seed's Alert-stage warning is not re-driven here.

**2026-10-28, second pass (the full tour on fresh Spaces).** Same bundle, a fresh `:8099` root from all four seeders (every stage created; this time `seed-la-mm`'s Alert stage made `mm-structuring` CRITICAL, "Entities breaching structuring in mm-structuring-case is 1"). The browser pane was closed by the operator mid-run, so the beats below are evidenced by the smokes and the API rather than re-clicked; the Investigation-level click paths were confirmed on `mule-hub-case` (same Studio) and are written into 1.1, 1.2, 1.4 and 1.5.

| Beat | Result | Evidence |
|---|---|---|
| A ladder and masking | PASS (API) | `la-mm-smoke` 10/10: masking typed (every Measure entity a token), bounded expand under the four-eyes threshold |
| A four-eyes | PASS (API) | the unbounded expand is held (pending), not run; requester view "Waiting for a different person" seen in the browser on the showcase Space |
| A algorithms | PASS (API) | shortest path 3 hops, maximum flow 25, one 4-node cycle around `LOOP-1`, structuring 1 entity / 25 legs / 25 payers, pass-through 0.98 |
| B algorithms | PASS (API) | `la-audit-smoke` 30/30: Run on server accepts articulationPoints; the plants (V-117 / E-31, shell cluster of 10 pairs, 30 split invoices, E-07 70 %, 12 intercompany legs of 2 400 000, 13 duplicate pairs) |
| C algorithms | PASS (API) | `la-claims-smoke --embed` 32/32: the nine server runs (Louvain, label propagation, cliques, triangles, PageRank, HITS, eigenvector, Katz, closeness) finish; three Geo Views project |
| B Dossier tamper | PASS (API; UI on A) | untouched Dossier verifies, one altered hash fails; the same upload flow driven in the browser on `mule-hub-case` (NOT verified, Changed `log.jsonl#1`) |
| C embed | PASS (API) / viewer FAIL in console | public GET with link-only headers, revoke, mangled and revoked both 404; console viewer missing (`LA-CONSOLE-EMBED-VIEWER-1`) |
| A Alert to Case | PASS (API) | `mm-structuring` CRITICAL, aggregate only (no wallet in the message) |
| B / C roles | PASS (API) | `audit.committee` and `partner.reviewer` read-only (403 on write), outsiders 404 |

**Still open:** a spoken run with an audience (needs a person); the portal on `:8140`; the B Alert beat; a click-through of this second pass in the browser on an `-Ui la-app` bundle (with the embed viewer).
