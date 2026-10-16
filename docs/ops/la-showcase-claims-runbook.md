# Link Analysis claims showcase runbook (insurance, Special Investigations Unit)

*Status: written 2026-10-16 with the build of the `la-claims` Space Template. Everything the data and the graph promise is pinned by tests
(section 5). **Nothing here has been driven live in a browser or against a booted server yet**: every scene that touches the screen is marked
**UNVERIFIED (rehearse first)** until `tools/la-claims-smoke.mjs` and a browser pass have run on a fresh bake (section 6).*

What this is: the script for presenting vertical C of the [multi-vertical showcase plan](../superpower/la-multi-vertical-showcase-plan.md),
"one garage, one lawyer, twelve strangers". The graph here is built from **shared attributes** (one phone, one address, one garage), not from
money moving, which is exactly where communities, identity resolution and Entity profile pages shine. Everything is synthetic: no real
person, address, brand or police report. Names, ids, phones and addresses are invented; places are city centroids plus jitter.
The sibling verticals are lanes A (mobile money) and B (audit); this lane touches none of their files. The shipped telecom and mule demo
stays in the [demo runbook](la-demo-runbook.md).

## 0. Set up

| # | Step | Command | Expect |
|---|---|---|---|
| 1 | Build a Demo Auth bundle (Preview edition bundles everything) | `inspecto\package.ps1 -Edition Preview -DemoAuth -Ui la-app` | a bundle with `serve-demo.bat` |
| 2 | Lay down the Space | `node tools/seed-la-claims.mjs --scaffold <bundle>\spaces` | `scaffolded: _templates, la-claims` (it also stamps the `la_index_build` owner; a plain file copy does not) |
| 3 | Start with the embed flag | `set PORT=8096` then `set INSPECTO_JAVA_OPTS=-Dla.embed.enabled=true` then `serve-demo.bat` | the Space ingests six extracts on boot |
| 4 | Seed the runtime state | `node tools/seed-la-claims.mjs --base http://127.0.0.1:8096 --apply` (without `--apply` it prints every call) | `created: ...`; it waits (up to 40 x 3 s) for the first `claims_links` build |
| 5 | Smoke | `node tools/la-claims-smoke.mjs --base http://127.0.0.1:8096 --embed` | `PASS` lines and `n/n probes passed` |

The Space has **no landed link Dataset until the `claims_links` Job has run** (it runs on a 10 minute cron; the smoke presses Run as `siu.manager`).
`la_index_build` then fires on the `claims_links` Dataset being produced, so the first Investigation read can be served from the index.

### Personas (Demo Users of the template)

| User | Role | Does |
|---|---|---|
| `siu.analyst` | lead | builds the Investigation, authors Alert Rules, runs Graph Runs |
| `siu.manager` | second approver | approves the held expand, presses Run on Jobs |
| `partner.reviewer` | read-only | reads the Investigation; every write is 403 |
| `outsider` | not a member | the Investigation is 404 |
| `admin` | lead and Reveal | audited Reveal, Settings, mints the embed link |

## 1. The planted stories (ground truth; pinned by `LaClaimsGoldenTest`)

| # | Story | Ground truth | Ids |
|---|---|---|---|
| 1 | Staged-accident ring | 12 claimants on 9 claims share 4 phones and 3 addresses; one garage, one clinic, one lawyer. **Louvain** and **Label-propagation** each return one community of exactly those 12 + 3 providers. Largest **clique** is 7 (5 claimants + clinic + lawyer), all inside the ring | claimants `CL-0029 CL-0061 CL-0111 CL-0134 CL-0173 CL-0193 CL-0206 CL-0223 CL-0247 CL-0276 CL-0332 CL-0377`; `GAR-03`, `CLI-04`, `LAW-02` |
| 2 | Repeat passenger | `CL-0134` is a passenger on 6 claims with 6 different drivers in under 7 months; 11 co-claimants at hop 1, 67 triangles in the two-hop set | `CL-0134` |
| 3 | Geo cluster | 11 accidents within 400 m of one junction in Derby, 02:00 to 03:59, none with a police report (9 are the ring's, 2 are ordinary claims); every other accident is over 1 km away | Geo View `claims_junction_cluster_map` |
| 4 | Inflated clinic | `CLI-04` bills at least 3 times its peers per claim and is referred to by exactly `LAW-02` (a `referred_to` link). In the ring's two-hop set HITS puts `GAR-03`, `LAW-02`, `CLI-04` on top as authorities; among providers `LAW-02` is the hub; PageRank, eigenvector and Katz peak inside the ring | `CLI-04`, `LAW-02` |
| 5 | Early-claim policies | 7 policies claimed on within 20 days of inception, all through broker `BRK-05`; no other claim is inside 60 days | Dataset `claims_policy_timing_dataset` |
| 6 | Identity resolution | `CL-0332` (ring) and `CL-0141` have names one letter apart, the same date of birth and the same phone, the latter written nationally (`07700...`) against E.164 (`+447700...`). The fold alone does **not** link them, so before resolving the duplicate is outside the ring; after it, the ring grows by one (`CL-0141`'s only co-party, `CL-0197`) | the seeded identity `party:CL-0332` = `party:CL-0141` |
| 7 | Benign look-alikes | a family of four (`CL-0103 CL-0217 CL-0218 CL-0249`) shares one phone and address and files two claims in 18 months: it is a clique of five with its garage, in its own community. The busy GP clinic `CLI-01` tops **every** whole-graph centrality (degree, PageRank, eigenvector, Katz, closeness, HITS authority, triangles) and is not in the ring | `CLI-01` |

Placeholders are data-quality noise the fold must ignore: 8 blank and 6 all-zero phones, 5 `UNKNOWN` and 3 blank addresses. They would
make 15 false `shares_phone` pairs if they counted; the pinned count is 20 `shares_phone` rows in all.

The link Dataset `claims_links_dataset` (`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`) has **2 574** rows of seven kinds:
`claimed_with` 678, `same_accident` 291, `serviced_by` 1298, `represented_by` 241, `referred_to` 22, `shares_phone` 20, `shares_address` 24.
Symmetric kinds are stored once (`src` < `dst`); `via` names the claim, the accident or the shared value; `event_date` is the day of `ts`, so the data-trust strip reads.

## 2. The 12-minute version

Start as `siu.analyst`. Always **seed first**: an all-nodes canvas is a dust cloud.

| Min | Scene | Click | Say | Expect |
|---|---|---|---|---|
| 0-2 | **Seed and the busy clinic** | Open the saved view `claims_network`; seed `CL-0134`, **Expand** twice. Then seed `CLI-01` | "One repeat passenger. Her neighbourhood is twelve strangers." The GP clinic arrives with a high-connectivity marker and **holds** | ~21 entities after two expands. `CLI-01` is held: high connectivity is not guilt |
| 2-4 | **Communities** | Toolbox > **Communities** > Louvain (client run), then **Run on server** | "The ring is one community, and it includes the garage, the clinic and the lawyer." | one community of the 12 claimants + `GAR-03`, `CLI-04`, `LAW-02` |
| 4-5 | **Cliques and triangles** | **Cliques** (minimum 4), **Triangle count** | "Shared phones make cliques; the family is a clique too, so sharing is not guilt." | largest clique 7; `CL-0134` 67 triangles; the family clique of 5 is in a different community |
| 5-6 | **Providers** | **HITS**, **PageRank** on the working set, then open `claims_provider_money` | "The lawyer is the hub, the clinic the authority; the clinic bills three times its peers." | authorities `GAR-03`, `LAW-02`, `CLI-04` on top |
| 6-7 | **Geo** | Open Geo View `claims_junction_cluster_map`, zoom on Derby | "Eleven accidents on one junction between two and four in the morning, none with a police report." | a stack of 11 markers (2 stray ones elsewhere) |
| 7-8.5 | **Identity resolution** | Entities > identity groups: `CL-0332` = `CL-0141` (seeded); re-run **Resolve** | "Two records, one person: same birth date, same phone, one letter apart." | the ring gains its 13th claimant (`CL-0197`). **UNVERIFIED on the canvas**: server Graph Runs keep one node per id, the contraction is pinned on the graph only |
| 8.5-10 | **Entity profile and masking** | Open the **Entities** page > type Party > `CL-0134`; then the canvas | profile: first and last seen, where it appears, degree and its percentile. Under typed masking the ids on the canvas read as `masked:<hex>`; as `admin`, **Reveal** one (audited) | no raw `CL-nnnn` until Reveal |
| 10-11 | **Dossier** | Investigation > **Dossier** > build, **Verify**; show the 3 external references (police-report requests) | "The reports are requested, never fetched, never trusted: a reference is a pointer." | verified; references listed with `trusted:false` |
| 11-12 | **Partner embed** | as `admin`: mint an embed link (curl below), open the portal page | "Your partner sees the read-only masked view and nothing else." | the masked Investigation inside the portal page; revoke makes it a 404 |

### The partner embed beat (admin; the host runs `-Dla.embed.enabled=true`)

```
# mint (admin token in $ADMIN); the response carries grantId, the token (shown ONCE) and the url
curl -s -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d '{"ttl_hours":4,"label":"partner"}' http://127.0.0.1:8096/api/v1/spaces/la-claims/inv/investigations/ring-case/embeds
# serve the static portal from the origin the Space allows (link-analysis.toon embed.frame_ancestors = http://127.0.0.1:8099 and http://localhost:8099)
python -m http.server 8099 --directory tools
# then open (the link is the token before the url's last slash)
http://127.0.0.1:8099/la-claims-partner-portal.html?host=http://127.0.0.1:8096&link=<grantId>.<secret>
# revoke
curl -s -X POST -H "Authorization: Bearer $ADMIN" http://127.0.0.1:8096/api/v1/spaces/la-claims/inv/investigations/ring-case/embeds/<grantId>/revoke
```

The page [`tools/la-claims-partner-portal.html`](../../tools/la-claims-partner-portal.html) holds no credential: the link is the whole grant, read-only,
masked and revocable. **UNVERIFIED**: the viewer path `/embed/inv/<token>` is the one the embed test frames; whether the la-app bundle serves the viewer shell is to be confirmed on the first rehearsal.

## 3. Algorithm scenes (the nine assigned to this vertical)

Run each **client first** (instant, small), then **Run on server** (a Job-class Algorithm; progress, cancel and result-parity beats). Scope: the Working Set after
seeding `CL-0134` and expanding twice (about 21 entities) unless the row says otherwise. Server ids: `louvainCommunities`, `detectCommunities`,
`cliques`, `triangleCount`, `pageRank`, `hits`, `eigenvectorCentrality`, `katzCentrality`, `closenessCentrality`. A server run of a masked Investigation returns masked ids.

| Algorithm | Seed and scope | Expected top result (measured by the golden test over the shipped corpus) | Story |
|---|---|---|---|
| Louvain communities | `CL-0134`, two hops | one community of the 12 claimants + 3 providers (whole graph: the same 15 members, nothing else) | 1 |
| Label-propagation communities | as above | the same 15 members | 1 |
| Cliques (minimum 4) | as above | largest 7: 5 phone-sharing claimants + `CLI-04` + `LAW-02`; the family is a separate clique of 5 | 1, 7 |
| Triangle count | as above | `CL-0134` 67, then `LAW-02` 57, `CLI-04` 56, `GAR-03` 47; **whole graph: `CLI-01` first with 653** | 2, 7 |
| PageRank | as above | `CLI-04` 0.180, `GAR-03` 0.148, `LAW-02` 0.116; **whole graph: `CLI-01` first** | 4, 7 |
| HITS | as above | authorities `GAR-03`, `LAW-02`, `CLI-04`; hubs `CL-0134`, `CL-0061`, `CL-0029`; among providers `LAW-02` is the hub; **whole graph: `CLI-01` top authority** | 4, 7 |
| Eigenvector centrality | as above | `CL-0134`, `CL-0111`, `CL-0061`, `CL-0029`, `CL-0247` (all ring); **whole graph: `CLI-01` first** | 4, 7 |
| Katz centrality | as above | the same ring claimants lead; **whole graph: `CLI-01` first**; the scores are huge, read the order | 4, 7 |
| Closeness centrality | as above | `CL-0134` and `GAR-03` tie at 0.769; **whole graph: `CLI-01` first** | 2, 7 |

The "whole graph" lines are the point of story 7: run any centrality on the full link Dataset (a Graph Run with `input: "index"`) and the busy clinic wins, so the
presenter shows that **centrality alone is not guilt** and the ring is found by the community and the structure around it. Run times are not recorded for this corpus (it is far under every ceiling); record them on the first rehearsal.

## 4. Decisions, deviations and findings of this build

1. **No shared kit.** The plan's S1 helper was dropped (operator): this vertical has its own generator, seed script and smoke runner and imports only pure helpers from `tools/la-demo-smoke.mjs` and `tools/seed-la-showcase.mjs`.
2. **A seventh link kind, `referred_to` (deviation).** The plan lists six kinds, but story 4 ("lawyer as hub, clinic as authority") needs a lawyer-to-clinic edge: claimants only point at providers, so a lawyer can never be a hub. `referred_to` (lawyer to the clinic they refer a claim to) is the minimum addition.
3. **One Entity Type for both endpoints.** An Investigation binds one Entity Type per endpoint column, and `src` and `dst` hold claimants and providers, so the Space declares one masked type `party` (classification `PARTY`). Under `typed` masking the garage, clinic and lawyer are therefore masked too, and `typed` and `all` look the same on this Dataset; the masking scene is typed (masked) against Reveal. A follow-up could split the endpoints into claimant and provider columns.
4. **No claims domain profile.** The plan asks for one profile per vertical; the SPA has `finance`, `telecom`, `supply-chain`, `cyber` and `generic`. Adding an insurance profile is an SPA change and is not made here; the views use the generic vocabulary.
5. **The index build is signal-driven** (`job.dataset.produced` for `claims_links`), because the link Dataset is produced by a Job rather than committed by a Pipeline.
6. **Staged next-period files** (plan section 5) are not built; the corpus is one extract per feed. The "new data lands" beat is the mule one in the demo runbook.
7. **Police-report PDFs are not shipped.** The ring has none by design. The seed attaches three external references with invented URLs on the reserved `records.example` domain (never fetched).
8. **Pre-existing defect, not touched:** `LaShowcaseTemplateTest` (`theCompanionSwitchesTheFeatureOffAndSharesThePersonas`, `personasHoldExactlyTheirCapabilities`) fails on master since `91a03666c` gave the la-showcase analyst `canRevealLinkEntities`; the test still expects the old role text. Unrelated to this lane.
9. **Claims we do not make:** no precision or detection-rate claim from synthetic data; the plants are planted and labelled so. The ring is found because it is built to be found.

## 5. Evidence

| What | Test |
|---|---|
| Generator is byte-identical on regeneration, six feeds, under 1 MB | `node --test tools/gen-la-claims-data.test.mjs` |
| Every plant, Louvain and Label-propagation, cliques, HITS, PageRank, eigenvector, Katz, closeness, triangles, identity resolution, benign look-alikes | `LaClaimsGoldenTest` (`-pl la/inspecto-la-graph`) |
| Real Pipelines and the `claims_links` SQL: contract columns, 7 kinds with their rows, de-duplication, placeholders, event day, early-claim policies | `LaClaimsLinksJobTest` (`-pl platform/inspecto-engine`) |
| Settings, Jobs, personas, Datasets, saved views | `LaClaimsTemplateTest` (`-pl inspecto`) |
| Geo Views over the real `POST /geo/projection` (11 on the junction) | `ControlApiLaClaimsGeoSceneTest` (`-pl la/inspecto-geo-link -am`) |
| Seed requests and scaffold | `node --test tools/seed-la-claims.test.mjs` |
| Smoke verdicts and dry run | `node --test tools/la-claims-smoke.test.mjs` |

## 6. Still to do (rehearsal)

* Boot a Demo Auth bundle, run the seed and `tools/la-claims-smoke.mjs --embed`; record the output here.
* Drive the scenes in a browser (they are marked **UNVERIFIED** above) and file defects as BACKLOG rows; run the 29-algorithm walk-through timings for this vertical.
* Confirm the embed viewer shell and the identity-resolution canvas beat (section 2).
