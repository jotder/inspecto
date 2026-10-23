# Inspecto LA onboarding - acceptance live drive (`LA-ONBOARD-1` §1)

*The acceptance of `LA-ONBOARD-1` (archived plan
[`la-onboarding-plan.md`](../../../../archived-documents/plans-archive/la-onboarding-plan.md) §1): a first-time user goes
from an empty install to a finding with the screens alone. Run 2026-10-22 on the phase 8 worktree (base `5f39c3328`),
results in §3 and §4. Re-run it after any change to the la-app first-run path; the beginner path it follows is the
[end user guide](user-guide-end-user.md) §2 *Getting started*.*

## 1. Set-up

| Item | Value |
|---|---|
| Bundle | Basic, `la-app` UI: `pwsh -File inspecto\package.ps1 -Ui la-app -NoRuntime -SkipBootCheck` (Java 27 on the path) |
| Spaces root | a NEW empty folder holding only a copy of the bundle's `spaces/_templates` (templates, no Space) |
| Server | `serve.bat` with `PORT=18388`, `SPACES_ROOT=<that folder>`; own port, own browser tab |
| Data | `transfers.csv` (2,215 transfers between 312 accounts: a mule ring `VICTIM-01 → MULE-1..6 → MULE-HUB → CASHOUT-A/B/C` and a busy `MERCHANT-BIG` with 700 transfers) and `accounts.csv` (312 accounts, `account_id, holder, kyc_level`); synthetic, kept outside the repository |
| Rule | only the screens: no call to the Inspecto API by hand. The files were handed to the page's own file chooser by a script (a person would pick them); a few buttons were pressed by a DOM click when the pane's scaled viewport made a pointer click unreliable - each such button is the screen's own |

## 2. Script (Basic, no sign-in)

1. Open the app. Expect **Getting started**, the Space guide at 0 of 4, and **What can I do next?** in the left menu.
2. **What can I do next?** → **Create a Space**: name *Mobile money*, kind *Mobile Money*, **Create**. Expect to land on
   **Onboard a file**.
3. *Events between entities*, choose `transfers.csv`. Check the columns: what was detected is applied with its evidence.
   **Create and run**. Expect the run summary in plain words.
4. **Add details about these entities**, choose `accounts.csv`; confirm any guess; **Create and run**.
5. Link Analysis → **Start from...**: Dataset *transfers*, **One entity**, Entity Type *Account*, type `MULE-H`, pick
   **MULE-HUB** from the type-ahead, **Start**. Expect MULE-HUB and its ring.
6. **Grow** (preview, then **Grow**). Expect the preview to say what it adds, then the graph grown.
7. Analysis → **Find something interesting** → **How does A reach B**: `VICTIM-01` → `CASHOUT-A`; and **Which groups stick
   together**.
8. **The whole Dataset** → **Who is busiest** → *MERCHANT-BIG ... Add it to an exclusion list?* → **Add to an exclusion
   list**: create *Busy merchants* (purpose *Never follow these (exclusion)*).
9. **How does A reach B** on the whole Dataset, **Start from these**, then **Save as a case** *Mule ring*.
10. Home: **My work** lists *Mule ring*; open it. Expect the ring drawn again.
11. Read **Settings → Getting started counters**. Scan every screen for Pipeline words.

## 3. Results (Basic, 2026-10-22)

| Step | Result | What the screen showed |
|---|---|---|
| 1 Open | PASS | *Getting started*: **Try it with sample data** / **Use my own data**, Space guide *0 of 4 done*. **What can I do next?** offered **Create a Space** (why: "Nothing exists yet: a Space is a place for one kind of data...") and **Try it with sample data**, with the Space guide under them. |
| 2 Space | PASS | The Create a Space dialog asked a name and *What kind of data?* (three kinds, each with example columns); **Create** landed on *Onboard a file* in the new Space. |
| 3 transfers | PASS | Format, separator, header and every column type applied with evidence; *from_account* / *to_account* → Account, From/To, kind *channel*, time *ts*, amount *amount*, main kind Account - nothing to confirm. Result: "2,215 rows were read from transfers.csv; 2,215 rows were kept. The link index was built: 2,215 links between 312 entities." Dataset card *one-time import* with **Add a file** / **Run now**; *Keep this up to date* locked with the Standard reason. |
| 4 accounts | PASS | *account_id* → Account, "Which column identifies each entity?" *account_id*; one guess (*The header line*) had to be confirmed first; "312 rows were read ... 312 rows were kept". |
| 5 Start | PASS | The type-ahead listed "MULE-HUB - 9 links, first seen 3 Sep 2026, last seen 30 Sep 2026"; **Start** drew "1 start and 9 linked wallets" (10 nodes, 9 links), MULE-HUB in the middle. |
| 6 Grow | PASS | Preview: "This step adds about 1 wallet and 6 transfers"; after **Grow** 11 nodes, 15 links, with **Undo**. A second Grow added nothing (the ring is closed) and said so. |
| 7 Ring | PASS | "VICTIM-01 reaches CASHOUT-A in 3 transfers: VICTIM-01 → MULE-1 → MULE-HUB → CASHOUT-A." Groups: "Group 1: 6 wallets ... (CASHOUT-A, CASHOUT-B, CASHOUT-C and 3 more)", "Group 2: 5 wallets ... (MULE-1, MULE-2, MULE-3 and 2 more)". |
| 8 Exclude | PASS after a fix | Whole Dataset: 312 nodes; "MERCHANT-BIG takes part in 270 transfers, the most on this graph" with the exclusion hint. **First run: FAIL** - the list picker offered nothing ("No choice matches") and no *Create a new list…*: the explorer graph had no Entity Type. Fixed in this phase (the start Dataset's primary Entity Type; the dialog now says why when no list can take them); re-run: *Create a new list…* preselected with *Never follow these (exclusion)*, "1 added to “Busy merchants”", MERCHANT-BIG hidden with Undo. |
| 9 Case | PASS | **Start from these** on the path: "Drew 4 starts and 7 linked wallets"; **Save as a case** → "Saved the case “Mule ring”." |
| 10 Come back | PASS | Home: Space guide gone (done), **My work** → *Mule ring*, "Saved 22 Oct 2026 · No Entity List yet" (the name, not the id: the phase 8 fix); *What changed*: "transfers: imported 22 Oct 2026, +2,215 rows", "accounts: ... +312 rows". Opening it drew "4 starts and 7 linked wallets" again. |
| 11 Counters | PASS | Settings → Getting started counters: first launch 22:32, **first Space after 1 min 1 s, first Dataset after 1 min 28 s, first graph after 2 min 40 s**, Space guide done after 9 min 31 s. Leaving Add data with a file read counted *Add data: checking the columns*. |
| Pipeline words | PASS after a fix | First scan: the Link Analysis footer said "... the lineage, component or pipeline planes ..." with no graph drawn - reworded. After it, no Pipeline / Collector / schema word on Getting started, Add data, Link Analysis, Entities (both tabs), Entity Lists, My work, What can I do next? or the Settings sections, except the role id `pipeline-developer` listed under Settings → Access (filed, `LA-ONBOARD-RESIDUALS-1`). |

**Time to first graph (from the counters):** 2 min 40 s from the first launch (the server start) to the first drawn
graph, against the 10-minute target. The drive is scripted, not a think-aloud: a first-time user's time stays to be
measured with real people (`LA-GROW-EXCLUDE-THINK-ALOUD-1` covers that kind of session).

## 4. The sign-in variant (2026-10-22)

**How it was run.** The demo sign-in bundle (`package.ps1 -Edition Advanced -DemoAuth -Ui la-app`) could not be packaged
offline: it needs the PostgreSQL driver 42.7.12 in the local Maven repository, which this sandbox lacks. The variant was
run instead from the Basic bundle's own jars plus `inspecto-ops.jar` (the Investigation surface) and
`inspecto-demo-auth.jar` (built with `mvn -o -pl providers/inspecto-demo-auth -am package`), launched with the
`serve-demo` flags (`-Dauth.mode=demo -Dcontrol.bind=127.0.0.1 ...`) and **`-Daddons.laInvestigations=false`**, port 18390.
`/bootstrap` answered `edition: standard`, `laLevel: explorer`, `featureReasons.investigations` = "Needs the Investigations
add-on, which this installation does not hold." Demo sign-in reads its users from a Space, so the spaces folder held one
Space, *mobile-money*, with only `demo-users.toon` and `roles.toon` copied from the `la-showcase` template (as an
administrator would set up).

| Check | Result | What the screen showed |
|---|---|---|
| Add data locked (no right) | PASS | Demo FM Analyst: *Onboard a file* is the reason "Adding data needs the right to upload data in this Space - ask an administrator"; **What can I do next?** shows **Add data** locked with the same line. |
| Add data locked until turned on | PASS after a fix | Demo Admin: "Add data is turned off in this Space: its data comes from Pipelines - ask an administrator to turn it on" - but there was no screen for the administrator to turn it on (only `PUT /settings/data`). Built in this phase: **Settings → This Space → Add data** (*People may add data in this Space*, read-only for others) and, on the locked screen, "You may turn it on: Settings, Add data" for the person who may. Turned on → `transfers.csv` imported (2,215 rows, index built). |
| Masking on | PASS | Demo Admin, Start from: "This Entity Type is masked: type the whole value - nothing is suggested"; MULE-HUB typed whole → "Drew 1 start and 9 linked nodes"; the query panel: "Masking: typed (ids shown as aliases)". |
| Investigation actions locked | PASS | Demo FM Analyst, toolbox **Open the Investigation** → **Start an Investigation** locked: "Needs the Investigations add-on, which this installation does not hold." |
| Analyst sees the imported data | FAIL (filed) | The Dataset the administrator added is `owner: admin`, `shares[0]`: private to its owner. The FM Analyst's Start from... said "No Dataset in this Space has a link yet" and Inspecto LA has no screen to share it (`LA-ONBOARD-RESIDUALS-1`). Fixed 2026-10-23: an import is shared with its Space (`architecture.md` §6.12). |
| Sign out, sign in as someone else | FAIL (filed) | After signing out and in as another Demo User in the same tab, Link Analysis showed the previous person's page state (the page is kept alive across navigation) until a reload (`LA-ONBOARD-DEFECTS-1`). Fixed 2026-10-23 (`architecture.md` §6.12). |
