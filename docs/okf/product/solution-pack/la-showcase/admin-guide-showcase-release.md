---
type: Feature
title: "Link Analysis showcase release — build and boot-test"
description: "Build a Link Analysis showcase release: the Basic and Preview demo products, the 18 Space and 18 data downloads from one commit, checksums and README, then boot-test every Space live on both products; with the record of the 2026-11-09 release."
tags: [la-showcase, admin-guide, release, showcase]
timestamp: 2026-11-09T00:00:00Z
---

# Link Analysis showcase release — build and boot-test

A showcase release is two product downloads plus a Space download and a data download for each of the 18 showcases,
all built from **one commit** (layout and packer: [catalogue architecture](architecture-showcase-catalogue.md)).
Operator decisions 2026-11-09: the downloads carry the **product version as is** (no tag of their own), the release
is **built locally only** and the operator publishes it, and there are **two products**: Basic with the Link Analysis
UI, and Preview with Demo Auth.

## Build

Work in a clean lane worktree, never in the shared checkout, with a private Maven repository so a parallel lane cannot
overwrite a sibling jar mid-build ([build and test spec](../../layer/platform/spec-build-test.md)):

```powershell
git fetch
git worktree add --detach C:\sandbox\la-release origin/master
cd C:\sandbox\la-release
$env:MAVEN_ARGS = '-o -Dmaven.repo.local=C:/sandbox/.m2-la-release -Dmaven.repo.local.tail=C:/.m2/repository'
pwsh -NoProfile -File inspecto\package.ps1 -Edition Basic -Product la
pwsh -NoProfile -File inspecto\package.ps1 -Edition Preview -Product la -Distribution demo -NoBakeDemo
```

- Maven reads `MAVEN_ARGS` on every `mvn` call `package.ps1` makes; the build only packages, so the private repo stays
  empty and everything resolves read-only from the `.tail` repository. `package.ps1` runs `npm ci` for the UI itself.
- `-NoBakeDemo` keeps the Preview product free of a pre-seeded `la-showcase`: a showcase release attaches every Space
  from its own downloads.
- Without a Linux GraalVM jmods cache on the build box (`-GraalvmCache`, `GRAALVM_CACHE`, `<repo>/.graalvm-cache` or the sibling
  `<repo>/../.graalvm-cache`),
  `package.ps1` builds the `windows_amd64` products only.
- The products unzip to `inspecto-deploy\` (Basic, start with `bin\serve.bat`) and `inspecto-demo\` (Preview, start with
  `bin\serve-demo.bat`; Demo User sign-in with no password, loopback only, internal evaluation only).

Then pack every showcase from the same commit (the packer reads `HEAD`, so commit nothing in between) and write the
checksums:

```bash
for id in la-sample la-showcase la-mobile-money la-mfs la-telco-ra la-ownership la-card la-trade la-crypto \
          la-claims la-utility la-police la-procurement la-benefits la-health la-logistics la-audit la-insider; do
  node tools/package-la-showcase.mjs "$id" --out <out>/staging
done
```

Lay the output out as `product\`, `spaces\` (18 `inspecto-la-space-<id>-<version>.zip`) and `data\` (18
`inspecto-la-data-<id>-<version>.zip`), add `SHA256SUMS.txt` (one line per zip, paths relative to the release folder,
checked with `sha256sum -c`) and a `README.txt` with the install steps of each Space's install guide. Remove the lane
worktree and its private Maven repository when done.

## Boot-test

Prove every Space on both products before handing the release over:

1. Lay the downloads out exactly as the README says, in three sibling folders, with **every Space in one server** per
   product (this also proves the Demo User rosters coexist: a differing user id would refuse with `ROSTER_CONFLICT`).
2. Start each product from a `.cmd` file, not from Git Bash, which mangles `..\` in `SPACES_ROOT`:
   `set SPACES_ROOT=..\inspecto-la-spaces`, `set SPACES_DATA_ROOT=..\inspecto-la-data`, `set PORT=…`, then
   `bin\serve-demo.bat` (Preview) or `bin\serve.bat` (Basic).
3. Wait for every inbox file to land and for at least one `*/5` cron run after the last landing; trigger no Job by hand.
4. **Preview:** per Space, read `GET /api/v1/spaces/<id>/alerts` (one OPEN Alert at the value its install guide
   names), run `node tools/seed-la-<id>.mjs --base http://127.0.0.1:<PORT> --space <id> --apply` (every step created,
   none failed; a second run creates nothing), then probe the personas with Demo Auth tokens
   (`POST /api/v1/auth/exchange`): analyst, reviewer and manager read the Working Set and the outsider gets 404; the
   reviewer cannot add a step (403); the analyst has no approval inbox (403); analyst and reviewer cannot approve
   (403); the manager's inbox holds the held expand, approving it answers 200 and the expand runs with `decidedBy` =
   manager.
5. **Basic:** every Space lands with 0 rejected, logs "Not arming alert rule '<rule>'", its alerts Job logs "no rule
   breached", it has 0 Alerts, and `GET /settings/link-analysis` answers `maskingModeInForce: none`.
6. Count ERROR lines in both server logs (expected: 0).

Expected noise, not defects: each link Job's one "attempt 1 failed transiently (`__no_readable_files__`)" at the first
boot, and on Preview `DatasetMeasureProbe` "No files found" WARNs before the counts Datasets exist. A manager's approve
can answer 422 while a cron run is rebuilding the link Dataset; it succeeds when retried.

## Record: the 2026-11-09 release

Built locally from `1963c661b` (version `4.0.0-SNAPSHOT`) into `C:\sandbox\la-release-out\` (not in git): Basic with
the Link Analysis UI (246 MB) and Preview with Demo Auth (417 MB), `windows_amd64` only (no Linux GraalVM jmods cache
on the build box), 18 Space and 18 data zips, `SHA256SUMS.txt`, `README.txt`. A first build from `127bd077e` found
that an open scalar-Measure Alert kept the partial value it fired at (`la-crypto` opened at 1, the full count is 2);
fixed in the engine (`ALERT-VALUE-REFRESH-1`) and the whole release rebuilt.

Boot-test of all twelve 2026-11-09 Advanced tiers, all PASS; Preview (port 8180) had 0 ERROR lines and 0 rejected,
error rows or quarantined files in every Space:

| Space | Files landed | OPEN Alert | Seed created / failed | Persona checks |
|---|---|---|---|---|
| `la-utility` | 30 | 24.7 CRITICAL (fired at the first crossing of 15 %, refreshed to the current highest loss, TX-019 in 2026-09) | 21 / 0 | 18/18 |
| `la-card` | 17 | 60 WARNING | 19 / 0 | 18/18 |
| `la-ownership` | 18 | 1 CRITICAL | 17 / 0 | 18/18 |
| `la-trade` | 62 | 14 WARNING | 19 / 0 | 18/18 |
| `la-procurement` | 29 | 16 WARNING | 19 / 0 | 18/18, after one retry (the 422 above) |
| `la-benefits` | 40 | 9 WARNING | 20 / 0 | every gate held (the second approved expand admitted 0 new entities, because of an earlier threshold step) |
| `la-health` | 53 | 159 WARNING | 19 / 0 | 18/18 |
| `la-mfs` | 51 | 30 WARNING | 19 / 0 | 18/18 |
| `la-telco-ra` | 51 | 300 WARNING, plus 1 RESOLVED Alert at 4 from the first boot | 19 / 0 | 18/18 |
| `la-logistics` | 52 | 5 WARNING | 19 / 0 | 18/18 |
| `la-insider` | 39 | 8 WARNING | 19 / 0 | 18/18 |
| `la-crypto` | 28 | 2 CRITICAL | 19 / 0 | 18/18 |

Basic (port 8190): every Space as in step 5, 0 ERROR lines. Before this full boot-test, the first build booted
`la-card`, `la-police` and `la-telco-ra` on Basic and `la-crypto` on Preview, and the rebuild booted `la-card` on Basic
and `la-crypto` on Preview (one OPEN Alert at 2, refreshed in place from 1, no RESOLVED Alert).

Found by this boot-test and filed in `docs/BACKLOG.md`: `LA-TELCO-RA-FIRST-BOOT-RESOLVED-ALERT-1` (the stray RESOLVED
Alert above), `JOB-LOADER-SAVED-VIEW-SUFFIX-1` (`la-logistics`' saved view `driver_team_inside_job.toon` is read by the
Job loader, a WARN at every boot), `LA-OWNERSHIP-NO-CRON-FALLBACK-1` (`ownership_links` has no `*/5` cron fallback),
`ALERT-EVALUATE-NO-BREACH-WORDING-1`. Not covered by it: `LA-SHOWCASE-RELEASE-RESIDUALS-1` (the Linux product, the
Link Analysis UI on these tiers, an OIDC sign-in build, the Geo Views on Standard).
