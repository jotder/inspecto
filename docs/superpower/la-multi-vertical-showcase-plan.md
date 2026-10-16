# Link Analysis showcases across three verticals (plan, synthetic data only)

**Status: PLAN 2026-10-16, nothing built.** Goal: one small, believable, fully synthetic showcase per vertical, together exercising **every** Link Analysis
capability (the 29 algorithms in the SPA and on the server, Dossier, embed links, masking, four-eyes, Drafts, standing detection, Geo Views). Verticals:
**Mobile money**, **Finance and audit**, and a third chosen here, **Insurance claims (Special Investigations Unit, SIU)**.
Reuses, does not replace: the [demo runbook](../ops/la-demo-runbook.md) (mule transfers + telecom, scenes S1 to S15), the `la-showcase` Space Template,
the [IMS showcase](la-ims-showcase-plan.md) (real data, kept outside the repo), and the generator pattern of `tools/gen-la-showcase-data.py`
(deterministic, seeded `random.Random`, golden test pins the planted facts).
When this ships: distil the as-built facts into `okf/frontend/features/link-analysis.md`, move open items to `docs/BACKLOG.md`, then `git mv` this plan to `archived-documents/plans-archive/`.

## 1. Ground rules

1. **Synthetic only, invented values** (names, ids, bank accounts, coordinates). No real person, brand, operator or bank. Cities only for Geo.
2. **Small on purpose.** Per vertical: at most ~600 entities, ~8 000 link rows, under 1 MB of CSV, loads in under a minute. Scale is shown from the recorded benchmark, never from these corpora.
3. **Planted stories with known answers.** Every vertical plants 5 to 7 findings, each with a measurable ground truth (a hop count, a score rank, a ratio) pinned by a golden test, so a presenter can promise it and a regression cannot silently break it.
4. **Each story needs a benign look-alike** (a big biller, a shared family phone, a busy approver). Link Analysis that only finds guilt is not credible; the hub-hold and "high connectivity is not guilt" beats come from these.
5. **Deterministic output** (byte-identical on regeneration), like the existing generators. One shared Python helper module; three thin generators.
6. **Plausible sense-making over feature coverage.** A feature appears only inside a scene an analyst of that vertical would actually perform. The coverage matrix (section 3) checks nothing is left out; it does not drive the story.
7. Canonical vocabulary (`docs/GLOSSARY.md`): Collector, Pipeline, Dataset, Measure, Alert Rule, Incident, Case, Entity List. No bare "rule", no "flow", no "source".

## 2. The three verticals at a glance

| | **A. Mobile money** | **B. Finance and audit** | **C. Insurance claims (SIU)** |
|---|---|---|---|
| Question the analyst asks | Where did this cash go, who is behind these wallets? | Is this vendor real, who approved, is the control chain sound? | Is this a staged-accident ring, who services it? |
| Shape of the graph | Directed money flow, hub-and-spoke, cycles | Bipartite-ish: people, vendors, accounts, approvals; plus ledger flows | Bipartite claims-to-people-and-providers, projected to a co-occurrence graph |
| Entity types | Wallet, Agent, Device, Merchant, National-ID | Vendor, Employee, Bank account, Address, Invoice, GL account, Legal entity | Claimant, Policy, Vehicle, Garage, Clinic, Lawyer, Phone, Address, Claim |
| Link kinds | `p2p`, `cash_in`, `cash_out`, `merchant_pay`, `shared_device`, `shared_id` | `approved`, `paid_to`, `shares_account`, `shares_address`, `reports_to`, `journal_flow` | `claimed_with`, `serviced_by`, `represented_by`, `shares_phone`, `shares_address`, `same_accident` |
| Strongest feature fit | Money movement, cycles, propagated risk, value Measures, Alerts | Dossier as audit workpaper, Draft/review, k-core, bridges, Jaccard | Communities, entity resolution, Entity profiles, Geo, embed to a partner |
| Embed audience | Regulator / financial-intelligence portal | Audit-committee portal (read-only) | Police or partner-insurer referral portal |
| Size target | 420 wallets, 40 agents, 6 000 transactions, 14 days | 150 vendors, 60 employees, 1 800 invoices, 12 months, 40 journals | 380 claimants, 25 providers, 520 claims, 18 months |

Why insurance as the third: it is the one vertical where the **graph is built from shared attributes, not from money moving** (one phone, one address, one garage),
which is exactly where identity resolution, communities and Entity profile pages shine, and it is not a restatement of the money story in A and B or of the telecom
demo that already ships. Telecom stays as the shipped fourth showcase (`la-showcase` telecom corpus) and is not rebuilt. Alternatives considered: customs and trade
(good flow and geo story, but thin on entity resolution), health-claims only (same shape as C, narrower audience).

### A. Mobile money: "the agent who never sleeps"

Plants (ground truth in brackets, to be pinned):
1. **Agent-assisted structuring.** One rogue agent cashes-in for 25 distinct wallets, each just under the KYC tier limit, all within 36 hours [fan-in 25, every amount in the top 5 % band below the limit].
2. **Mule chain.** Wallet W-hub receives from the 25, forwards 98 % within 10 minutes to two relay wallets and on to one cash-out agent [pass-through 0.98; max flow into the cash-out agent equals the planted total; shortest path hub to cash-out = 3 hops].
3. **Device farm.** One handset id holds 9 wallets with 9 different National-IDs [Jaccard similarity of their counterparty sets above 0.6; shared-device and shared-ID links agree on the same group, a good entity-resolution beat].
4. **Round-trip loop.** Four wallets pass the same amount around in a circle for fee and bonus farming [the only directed cycle, length 4].
5. **Dormant awakening.** A wallet idle 11 months wakes up and moves 40 times its prior lifetime volume in 2 days (temporal pattern pack).
6. **Benign hub: the electricity biller.** A merchant with 300 payers and high PageRank, correctly held on expand and correctly not flagged by the Suspicion score once its one-way, many-payers shape is understood.
7. **Alert story.** Standing detection fires on #1 (fan-in over a threshold), aggregate-only, opens an Incident and a Case; the analyst reaches the Investigation from the Alert.

Existing asset to assess first: the `mobile-money` Space Template (wallets, KYC limit and round-trip Datasets) and the `la-showcase` mule corpus. Step 0 decides whether A extends
one of them or is a clean new template (default: new template, borrowing generator code, so the existing demo stays untouched).

### B. Finance and audit: "procure-to-pay and the journal"

Plants:
1. **Fictitious vendor.** A vendor whose bank account and address equal an employee's (conflict of interest) [shares_account and shares_address both link vendor to employee E-31; 14 invoices, all approved by E-31's manager].
2. **Shell cluster.** Five vendors sharing one bank account behind different names [connected component of 5 on `shares_account`; link prediction proposes the sixth, deliberately left unlinked].
3. **Threshold splitting.** One approver sees 30 invoices to one vendor, each 1 to 3 % under the approval limit, over 6 weeks (branching and temporal pattern packs).
4. **Single point of control.** One approver signs 70 % of invoices for 20 vendors [articulation point and bridge in the approval graph; k-core and betweenness agree]; the org chart is a tree [`isForest` true, a clean positive], the approval graph is not.
5. **Intercompany round-trip.** Four legal entities pass the same amount around at quarter end through GL accounts [find cycles on `journal_flow`; max flow quantifies it].
6. **Duplicate payments.** Same vendor, same amount, same week, two invoice numbers (13 pairs) [Jaccard / pattern match].
7. **Benign look-alike:** the busiest legitimate vendor (utilities) and a CFO with 40 approvals, both held on expand and explained.

The Dossier is the headline: the finished Investigation is sealed and verified as an **audit workpaper**, then one byte is altered and Verify fails (tamper check), which is the point of that vertical.

### C. Insurance claims: "one garage, one lawyer, twelve strangers"

Plants:
1. **Staged-accident ring.** 12 claimants over 9 claims share 4 phones and 3 addresses, all served by one garage, one clinic and one lawyer [Louvain finds one community of 12 claimants + 3 providers; cliques of size 4 among the phones' owners].
2. **Repeat passenger.** One person is a passenger on 6 claims with 6 different drivers in 7 months [degree and triangle count; a co-occurrence projection, hop 2].
3. **Geo cluster.** 11 accidents inside 400 m of one junction, all at 02:00 to 04:00, none with a police report [Geo View, temporal pattern pack].
4. **Inflated provider.** A clinic whose bills per claim are 3 times its peers and which is referred by exactly the ring's lawyer [HITS: lawyer as hub, clinic as authority; PageRank and eigenvector centrality agree].
5. **Early-claim policies.** 7 policies claimed on within 20 days of inception, bought through one broker (temporal).
6. **Identity resolution.** Two claimants whose names differ by one letter and who share date of birth and phone are the same person (resolve, then the ring grows by one).
7. **Benign look-alike:** a family of four sharing one phone and address, one claim a year, and a busy general-practice clinic. Must not join the ring.

## 3. Coverage matrix: every capability has a primary home

● = the primary demonstration (scripted scene), ○ = shown again briefly. The three verticals each carry a distinct share, and every row has at least one ●.

### 3.1 The 29 algorithms (SPA client run and the server Graph Run both shown; Java and TypeScript parity is a Measure, not a scene)

| Vertical | Algorithms (primary) |
|---|---|
| **A. Mobile money** (12) | Shortest path, All paths, Weighted shortest path, Maximum flow, Find cycles, Propagated risk, Suspicion score, Degree centrality, Connected components, Neighborhood, Ego network, Descendants |
| **B. Finance and audit** (8) | k-core, Bridges, Articulation points, Betweenness centrality, Jaccard similarity, Link prediction, Maximum spanning forest, Is forest |
| **C. Insurance claims** (9) | Louvain communities, Label-propagation communities, Cliques, Triangle count, PageRank, HITS, Eigenvector centrality, Katz centrality, Closeness centrality |

Algorithm scenes show **client run** first (instant, small), then **Run on server** for the Job-class ones (betweenness, Louvain, cliques, find cycles, max flow, link prediction,
eigenvector, Katz, HITS, propagated risk, suspicion) with the progress, cancel and result-parity beats. A run is always seeded; an all-nodes canvas is a dust cloud.

### 3.2 Platform features

| Capability | A | B | C | Note |
|---|---|---|---|---|
| Seed, expand, degree ladder, hub hold, "Expand anyway" | ● | ○ | ○ | A: biller hub; B: CFO and utilities; C: busy clinic |
| Four-eyes expand approval (two Demo Users) | ● | | | the existing S8 scene, re-staged on A |
| Connect seeds (shortest route between 2 to 20 seeds) | ○ | ● | ● | B: employee to vendor; C: two claimants |
| Presets / domain profiles, saved views (3 each) | ● | ● | ● | one profile per vertical |
| Working Set tiles, footer counts, data-trust strip | ● | ○ | ○ | the strip needs an event-day column in every link Dataset (learning from the IMS pass) |
| Value Measures (pass-through, cash-out) | ● | ○ | | B reuses the shape for the journal |
| Pattern packs (branching, temporal) | ○ | ● | ● | A: dormant awakening |
| Recursive paths / traversal | ○ | ● | | B: approval chain depth |
| Suspicion score + factor breakdown | ● | ○ | | |
| Entity Lists, identity resolution (resolve groups) | ○ | ○ | ● | C #6; A #3 as secondary |
| Entity profile pages | ○ | ● | ● | B: vendor profile; C: claimant profile |
| Reference rows enrichment (KYC, vendor master, policy master) | ● | ● | ● | one masked reference Dataset each |
| Masking `typed` vs `all`, audited Reveal | ● | ○ | ● | C holds health-adjacent PII, so the masking story is natural |
| Investigation op log, Replay, Re-read (drift check), Compare | ● | ● | | |
| Drafts: fork, step, rebase, promote | ○ | ● | | B: an auditor's what-if over the approval limit |
| Members, roles, read-only persona | ○ | ● | ○ | B: lead auditor, audit manager, committee (read-only) |
| **Dossier** build, Verify, tamper check, external references bundle | ○ | ● | ● | B is the headline, C attaches police-report PDFs as external references |
| **Embed link** (mint, public view, revoke, frame ancestors) | ● | ● | ● | one static portal page per vertical (section 5) |
| Alert, Incident, Case, bound Alert Rule, standing detection | ● | ○ | ○ | aggregate-only Alerts everywhere |
| Geo Views (map, route map, convergence) | ○ | | ● | A's existing geo scene stays as the secondary |
| Link index (engages, served-from-index badge, staleness) | ● | ○ | ○ | `la_index_build` Job per Space |
| Settings (limits, masking, embed allow-list, Max fan-out) | ○ | ● | | |
| Graph page UX: expand markers, link labels, legend filters | ● | ○ | ○ | |

## 4. Data design

One feed per fact, landed through a **Collector** and a **Pipeline** (schema, daily or monthly partition), folded by `sql.template` Jobs into **one link Dataset per vertical**
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, and a `via` key for the shared-attribute links). The raw Pipelines stay raw; normalisation lives in the Jobs
(the IMS lesson). `la_index_build` runs after the link Job; `la_detect` runs on a cron. Counts below are targets, tuned by the golden test.

| Vertical | Feeds (CSV) | Link Dataset | Reference Datasets | Rows (about) |
|---|---|---|---|---|
| A | `wallet_txn` (transfers, cash-in, cash-out, merchant pay), `wallet_master` (msisdn, device, national-id, tier, opened), `agent_master` | `mm_links` | `mm_kyc` (masked) | 6 000 + 420 + 40 |
| B | `ap_invoice`, `ap_approval`, `vendor_master` (bank account, address, phone), `employee_master`, `gl_journal` (entity, account, amount, period) | `ap_links` (+ `gl_links`) | `vendor_master`, `employee_master` | 1 800 + 1 800 + 150 + 60 + 400 |
| C | `claim`, `claim_party` (role: driver, passenger, claimant), `policy`, `provider_bill`, `accident_location` | `claims_links` | `policy`, `provider_directory` | 520 + 1 100 + 380 + 700 + 520 |

Design rules, each from a past defect on this feature:
* Every link Dataset carries a real **event-day column** (data-trust strip) and a `via` key; never an identifier column that a tile could sum (fixed 2026-10-16, `working-set-stats`).
* Entity ids are **typed and classified** (masking `typed`), and Entity List membership must match what the canvas shows; test that explicitly under masking.
* Shared-attribute links are **materialised once, de-duplicated**, with the shared value as `via`; a blank or placeholder value (an empty IMEI, a default phone) is excluded and a test pins it.
* The ordinary background graph is kept **sparse and acyclic where a story promises "the only cycle"**, as `gen-la-showcase-data.py` does, so the promises hold.
* Names, addresses and ids come from small invented pools; amounts from a seeded lognormal; no value is derived from a real source.

## 5. Delivery shape

* **Three Space Templates** (working names `la-mobile-money`, `la-audit`, `la-claims`; **to be checked against the existing `mobile-money` and `aml` template names** in step 0), each following the `la-showcase` layout: Collector + Pipeline per feed, Datasets, Jobs,
  `link-analysis.toon` (masking `typed`, four-eyes threshold, index ON, Drafts), three saved views, Demo Users and roles, staged next-period files for a live "new data lands" beat.
* **One seed script generalised** from `tools/seed-la-showcase.mjs` (`--vertical`), creating the runtime state that has no template home: Investigations, members, Entity Lists, identity groups, a Case, a bound Alert Rule, an Investigation Template.
* **Personas per vertical** (same five roles as today, renamed to the vertical): analyst (lead), manager (second approver), reviewer (read-only), outsider (not a member), admin (Reveal).
* **Embed portal pages**: a ~40-line static HTML page per vertical, served by the demo host or opened from disk, framing the minted embed link (frame ancestors allow-listed for it). It makes the embed beat visible: the regulator, committee and partner each see a read-only masked Investigation inside their own page.
* **Runbooks**: one per vertical (12-minute version and a full scene list with beat-level checks), plus a **30-minute "tour"** that takes one scene per capability across the three, in this order: ladder and masking (A), four-eyes (A), algorithms (A, B, C), Dossier tamper (B), embed (C), Alert to Case (A).
* **Smoke**: extend `tools/la-demo-smoke.mjs` with `--vertical`; offline, API level, pins each plant's ground truth against the live Space.

## 6. Build steps

| # | Step | Done when (verify) |
|---|---|---|
| S0 | **Recon and decisions**: read the existing `mobile-money`, `aml` and `la-showcase` templates, the seed script and the smoke runner; settle names and reuse (decisions below) | one-page note appended here; no code |
| S1 | **Shared kit**: common generator helper (pools, seeded amounts, CSV writer, determinism test), seed script `--vertical`, smoke `--vertical`, runbook skeleton | helper unit tests green; regeneration is byte-identical (cmp) |
| S2 | **Vertical A**: generator, feeds, Pipelines, Jobs, `mm_links`, saved views, Entity Lists, Alert Rule, geo reuse | `*GoldenTest` pins all 7 plants; smoke `--vertical mm` PASS |
| S3 | **Vertical B**: same, plus Draft what-if, journal flow Dataset, Dossier build/verify/tamper | golden test pins 7 plants; Dossier tamper check fails as designed |
| S4 | **Vertical C**: same, plus identity resolution, Geo Views, reference PDFs attached to the Dossier | golden test pins 7 plants; resolve grows the ring by one |
| S5 | **Embed portals and settings**: three static portal pages, embed allow-lists per Space | each portal shows its read-only masked Investigation; revoke makes the frame 404 |
| S6 | **Algorithm walk-through**: scripted client run vs server run for all 29 over the right vertical; record parity | 29 of 29 listed in the runbook with seed, expected top result and run time |
| S7 | **Live rehearsal** per vertical in the browser (not just API), then the 30-minute tour; log findings as BACKLOG rows | every scripted beat passes on a fresh bake; defects filed, none open that blocks a scene |
| S8 | **Docs**: OKF concept update, runbooks, `docs/INDEX.md`, archive this plan | doc-link, vocabulary and INDEX guards green |

S2, S3 and S4 are independent once S1 lands and can run as three parallel lanes, each owning its own template folder and generator; the shared seed script and smoke runner are touched
only in S1 (and the re-align after each lane merge, a lesson from the config-limits lane work). Tests per change at unit level (`-Dtest=` on the golden test classes); the full reactor
gate only before the push.

## 7. Decisions and risks

Defaults are applied unless the operator says otherwise.

| # | Decision | Default |
|---|---|---|
| D1 | Third vertical | Insurance claims (SIU); alternative customs/trade |
| D2 | A extends the `mobile-money` template, or is new | **New template**, borrowing generator code; the shipped demo is untouched |
| D3 | Embed portal served by the demo host or opened from disk | From disk first (zero host change); host-served only if frame ancestors need a real origin |
| D4 | Is the mule corpus in `la-showcase` retired once A lands | **No**; it is the claimed-proven base of the runbook and the whitepaper demo |
| D5 | Edition for the live runs | Preview edition bundle (it bundles everything), per the standing build default |

Risks: (1) **a story that depends on the ordinary traffic not producing accidental cycles or cliques** (tested by the golden test, as in `la-showcase`); (2) **masking vs Entity List matching** found
in the IMS pass may not be fixed yet (check BACKLOG before S2); (3) **index staleness** across the three Spaces on one host (use one Space per port or per `SPACES_ROOT`); (4) **scope creep into scale**:
resist enlarging the corpora, scale is a recorded benchmark; (5) **claims we must not make**: no precision or detection-rate claim from synthetic data, and the plants are labelled as planted.

## References

* [Demo runbook](../ops/la-demo-runbook.md) · [whitepaper demo plan](la-whitepaper-demo-plan.md) · [IMS showcase plan](la-ims-showcase-plan.md) · [demo readiness plan](la-demo-readiness-plan.md)
* Backend concept: [`okf/backend/modules/link-analysis.md`](../okf/backend/modules/link-analysis.md); SPA concept: [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md)
