# Spaces in source control, data outside it — one data root, one build target (`SPACES-CONSOLIDATION-1`)

Status: **ARCHIVED 2026-11-27 — P0–P5 shipped; the open items moved to `docs/superpower/2026-11-27-space-coherence-plan.md` §10 and the board rows `SPACES-CONSOLIDATION-1`, `SANDBOX-CLEANUP-1`, `LA-INDEX-UNDER-DATA-ROOT-1`.** As built: `docs/okf/product/module/config/spec-space-data-layout.md`. Kept for provenance, not maintained. Opened 2026-11-25.

**Revision note.** The first draft kept the live Spaces whole under `C:\sandbox\inspecto_spaces\<space>\` and
recommended the native layout. The operator then said the rule is: **a Space's configuration is source-controlled,
its data never is — data is supplied by the customer or generated on implementation.** That changes the shape:
config stays in the repo, only the data root moves out, and the committed sample data has to go (§1.2).

## 1. Why, and what is true today

Live Spaces sit in about ten roots on one machine and every launcher hard-codes its own. The operator wants Space
configuration versioned with the code, all data under `C:\sandbox\inspecto_spaces\<space>\`, every new build tested
against that data, and the unneeded sibling folders removed.

### 1.1 What is on disk (measured 2026-11-25, read-only)

| Root | Size | Holds |
|---|---|---|
| `C:\sandbox\inspecto-clean\spaces` | ~0.5 GB | tracked: `_templates` (30 Templates), `_industries`, `default`, `demo`, `ucc`; untracked: `telco-assurance`, `cricket-analytics`, `inspecto-mfs` (a junction to `C:\sandbox\inspecto-mfs-root\inspecto-mfs`), `uat` (generated, ignored) |
| `C:\sandbox\spaces` | 15.6 GB | `mfs-demo` 15.5 GB (13.7 GB `data`, **1.7 GB `config`**), `la-mobile-money` 68 MB, near-empty copies of the repo Spaces |
| `spaces-preview`, `spaces-rfp`, `ims-spaces`, `inspecto\inspecto-la-spaces`, `inspecto-preview` (10 GB), `la-*` bundles (~1 GB each) | | preview installs and bundles, some with their own `spaces\` (contents not yet inspected) |

One drive (C:, 155 GB free of ~476 GB). `git worktree list` shows one worktree beside this checkout,
`C:\sandbox\bi-p1-projection`, owned by the Windows account `User`; git run as the shift account refuses it
("dubious ownership"), so its unpushed commits are **not yet checked**. The other ~70 folders under `C:\sandbox`
are not worktrees (bundles, previews, scratch, about ten `.m2-*` private Maven repositories). No server JVM was
running.

### 1.2 What the repo tracks under `spaces/` (`git ls-tree`, HEAD)

93.7 MB in 2 219 files, of which **92.1 MB is `.csv`** and config is about 1.3 MB of `.toon`. `.gitignore` already
ignores `spaces/*/data/**`, `audit/`, `duckdb/`, `state/` and `config.secrets/`; the one exception is
`data/samples/` ("pristine sample inputs"), and the LA Templates commit their data under `data/inbox` and
`data/staged`. By origin:

| Class | Where | Size | How it is produced today |
|---|---|---|---|
| **Generated corpora** | `_templates/la-*/data` (18 Templates) | ~90 MB | deterministic generators `tools/gen-la-*-data.py` (seeded, ground truth in `tools/gen-la-*.ground-truth.json`, already outside the Space); `tools/gen-la-*.test.mjs` assert "generator twice = the committed bytes" |
| **Hand-authored golden samples** | `demo/data`, `_templates/{telco-fraud,aml,mobile-money,payment-fraud,telco-ra}/data` | ~1.5 MB | written by hand; read as **test inputs** by Java golden tests (`AmlCorpus`, `PaymentFraudCorpus`, `MobileMoneyCorpus`, `TelcoRaCorpus`, `TelcoComposedSpaceGoldenTest`, …) |
| **Config** | `config/`, `space.toon`, `template.toon` | ~1.3 MB | authored |

Untracking does not shrink history: the ~93 MB stays in every clone. Rewriting shared `master` is out of scope (D-SC7).

### 1.3 Contracts that read `data/samples` or committed data (must change with it)

- **Product:** `SpaceRoutes` `loadSamples` (`SpaceSamples.load`: a new Space from a Template lands the Template's
  samples and runs each Pipeline once); `InstalledApps` labels `data/samples/**` "Sample data"; `tools/inspecto-app.mjs`
  (the partner kit's `templates/<id>/data/samples/`), `tools/customer*.mjs` (strip the golden corpus).
- **Tools:** `tools/seed-samples.mjs` (derives each Pipeline's inbox from its config and copies
  `data/samples/<pipeline>/` into it), `package-la-showcase.mjs`, `tools/seed-la-*.mjs`.
- **Tests:** the Java golden tests above, 11 files under `inspecto/src`, 7 in `features/inspecto-scoring`, 3 in
  `inspecto-reconciliation`, 2 in `platform/inspecto-engine`, plus `gen-la-*.test.mjs` and the LA golden tests that
  read the link Dataset the generated data produces.

## 2. What the engine already provides (no engine change)

- `-Dspaces.root` / `SPACES_ROOT` names the spaces root; else `<bundle root>/spaces.ref`; else `<bundle root>/spaces`.
  The flag wins over the pointer. `tools/check-launchers.mjs` asserts every launcher leaves the flag off when a
  pointer exists.
- A Space's state — `data/`, `audit/`, `duckdb/` — lives under its **data root**: `data_root:` in `space.toon`, else
  `-Dspaces.data.root/<id>` (`SPACES_DATA_ROOT`), else the Space folder. A data root off the Space folder is checked
  and stamped `.inspecto-space`; it must lie **outside the spaces root and every Space folder**.
- Hence the split this plan needs is one launcher setting: `SPACES_ROOT=<checkout>\spaces` (config, in git) plus
  `SPACES_DATA_ROOT=C:\sandbox\inspecto_spaces` (data at `inspecto_spaces\<id>\{data,audit,duckdb}`).

⚠ **The stamp binds a data root to one Space folder.** `.inspecto-space` names the Space directory that owns the
data root (`SpaceDataRoot.ownedBy` compares canonical paths) and a data root claimed by another Space is refused
(`SPACE-LOCATIONS-1` reports it as 409). So two checkouts, each with its own `spaces/demo`, cannot share
`inspecto_spaces\demo`. Confirmed in `SpaceDataRoot.stake` ("belongs to the Space at …, not …"); a real boot of a
second checkout is still P0.4.

## 3. Target

```
<checkout>\spaces\<space>\          IN GIT:  space.toon  config\        (and _templates\, _industries\)
C:\sandbox\inspecto_spaces\<space>\ NOT IN GIT:  data\  audit\  duckdb\  (data root; .inspecto-space stamp)
```

Data comes from exactly three places, none of them the repo:

1. **Supplied** — the customer's files, dropped into the Pipeline inboxes.
2. **Generated** — `tools/gen-*` writes a Template's synthetic corpus into the data root on implementation
   (an `--out <data root>` argument; today they write into `spaces/_templates/<t>/data`).
3. **Fixtures** — the hand-authored golden inputs that tests need live with the tests, not in a Space.

A guard (`tools/check-spaces-config-only.mjs`, wired into `ci.yml` and so into the pre-commit run) keeps it that
way: every tracked file under `spaces/**` must be `space.toon`, `template.toon`, `config/**` or documentation and
under a size cap; any `data/`, `*.csv`, `*.parquet`, `*.db` fails. Its test must show the guard fails on a planted
CSV.

## 4. Decisions (all signed; D-SC10…D-SC15 are in §11, §17 and §19)

| # | Decision | Recommendation |
|---|---|---|
| **D-SC1** ✅ **DECIDED 2026-11-25: split** | Layout | **Split:** config in git, data root at `inspecto_spaces\<id>`. (This reverses the first draft's "native layout", which kept data inside the Space folder.) |
| **D-SC2** ✅ **DECIDED 2026-11-25: one canonical `spaces/`** | Which `spaces/` do builds use? The stamp (§2) allows one owner per data root | Every build passes `-Dspaces.root=C:\sandbox\inspecto-clean\spaces` (the one canonical config), so all share `inspecto_spaces`. A throwaway worktree or bundle test uses its own `SPACES_DATA_ROOT=…\inspecto_spaces\_<tag>` and is generated fresh, then deleted |
| **D-SC3** ✅ **DECIDED 2026-11-25: move to test resources** | The ~1.5 MB hand-authored golden samples | Keep tracked, but **move out of `spaces/`** into the owning module's `src/test/resources/corpora/<template>/`; the Template's `data/samples` is produced by copying them on install. They are test expectations, not Space data. Alternative: generate them too (more work, loses reviewable fixtures) |
| **D-SC4** ✅ **DECIDED 2026-11-25: generate; tests seed first** | The ~90 MB generated LA corpora | Untrack; generate into the data root on seed. `gen-la-*.test.mjs` change from "equals the committed bytes" to "twice = identical, plus the ground-truth checks". The LA golden Java tests then need a seed step first — see §6 |
| **D-SC5** ✅ **DECIDED 2026-11-25: track 3, keep the 2 private local** (revised after P0, §9) | The untracked Spaces | `telco-assurance` (config 0.1 MB), `cricket-analytics` and `la-mobile-money`: track their **config**. **`mfs-demo` and `inspecto-mfs` stay untracked and local**: their own `space.toon` says "real data … PRIVATE … never committed". Their 1.7 GB `config/la-index` is runtime data (D-SC9) |
| **D-SC6** ✅ **DECIDED 2026-11-25: no contract change; tools generate** | Product contract: a Template may ship `data/samples/` | The contract stays (a customer Template may ship its own samples); the repo's own Templates ship none. Data is generated by `tools/seed-space-data.mjs` and by packaging, so bundles and data downloads still carry it. In-product new-from-Template gives config only and says so (`SpaceRoutes` already answers "ships no sample data"). No engine change; the server never needs Python. **This plan therefore has no engine change except, possibly, D-SC9** |
| **D-SC7** ✅ **DECIDED 2026-11-25: no rewrite** | History | Do not rewrite shared `master`; the ~93 MB stays in history |
| **D-SC9** ✅ **DECIDED 2026-11-25: ignore in git, leave in place; moving it is a follow-up row** (new, from P0) | `config/la-index` (the Link Analysis index, parquet; 1.7 GB in `mfs-demo`) is written **inside the Space's config folder** (`<write root>/la-index`) and nothing ignores it | Add `/spaces/*/config/la-index/` to `.gitignore` and the guard now. Whether the engine should write it under the data root instead is a code change to `la/inspecto-la-storage` (`IndexBuildService`); decide before P1, since otherwise 1.7 GB of data stays outside `inspecto_spaces` |
| **D-SC8** ✅ **DECIDED 2026-11-25: fold `C:\data` in; keep the name** | Folder name, `C:\data`, and sandbox cleanup | The folder stays `inspecto_spaces`. Each `C:\data\<id>` is renamed to `inspecto_spaces\<id>` (same drive); the stamps and `data_root:` are rewritten (the `inspecto-mfs` stamp is an absolute path, P1); cleanup is classified before any delete (P6) |

## 5. Phases

Each phase ends with its verify step and starts only when the one before is green. Servers are stopped.

**P0 — Inventory, backup, and two checks (read-only plus copies).**
1. Classify every tracked file under `spaces/` as config / generated / golden sample / other (§1.2 is the draft).
2. Inspect `mfs-demo\config` (1.7 GB), `telco-assurance` (154 MB), and the `la-*`, `spaces-preview`, `spaces-rfp`,
   `ims-spaces`, `inspecto-preview` folders for what they hold and any own `spaces\`.
3. List every `space.toon` / `<id>.ref` with an absolute `data_root:` or an old path.
4. Boot two checkouts against one data root and record the stamp refusal (§2).
5. Back up every untracked Space (they have no history), verified by file count and bytes.
- **Verify:** inventory complete, backups match, `git status` unchanged, the stamp behaviour recorded.

**P1 — Move runtime data.** Same volume, so a rename. For each Space, `data\`, `audit\`, `duckdb\` move to
`inspecto_spaces\<id>\`; set `SPACES_DATA_ROOT`; the stamp is written at first boot. Config stays where it is.
`C:\data\<id>` (D-SC8) is renamed to `inspecto_spaces\<id>`; for each, rewrite the absolute `.inspecto-space` stamp
and any `data_root: "C:/data/<id>"` in `space.toon` (`inspecto-mfs` has both), or the boot refuses it. A same-drive
rename keeps no second copy: the backups in `C:\sandbox\backup\spaces-p0-20261125` are the fallback.
- **Verify:** each Space boots alone and serves its Datasets; file counts and bytes equal the manifest taken before.

**P2 — Generation and fixtures.**
1. Give each `tools/gen-*` an `--out <data root>` argument and a shared seed entry `node tools/seed-space-data.mjs
   <space|--all>` that generates (or copies fixtures) then lands inboxes — extending `seed-samples.mjs`, which
   already derives inboxes from Pipeline configs.
2. D-SC6 is decided: no Template field; document in the Template spec that the repo's Templates ship no data and
   how `seed-space-data` produces it.
3. Move the golden samples (D-SC3) to module test resources and repoint the corpus classes.
4. **LA golden tests (D-SC4):** the 22 Java classes that read `_templates/la-*` data (17 in
   `platform/inspecto-engine`, plus `inspecto`, `la/inspecto-geo-link`, `platform/inspecto-graph`) generate their
   corpus into `target/` (disposable build output, never `spaces/`) before running, through one shared test helper
   that runs the generator and fails with a named reason when Python is absent — never a silent skip.
5. **Python on CI is unverified:** no workflow I searched names Python, and `gen-la-*.test.mjs` skip without it, so
   they may already be skipping on CI. Establish that first; if absent, add the setup step in the same change as
   the untracking, or no LA golden test is covered.
- **Verify:** a clean data root plus one seed command reproduces the corpus byte-for-byte (generators run twice,
  hashes equal); the golden tests pass on it.

**P3 — Untrack and guard.** `git rm --cached` the data; drop the `data/samples` ignore exception; add
`/spaces/*/config/la-index/` to `.gitignore` (D-SC9); add the guard and
its test (§3); update `gen-la-*.test.mjs` (D-SC4); update the Space Template spec, `customer-build`, `inspecto-app`.
- **Verify:** the guard passes on HEAD and fails on a planted CSV; the affected-module test plan
  (`node tools/affected-modules.mjs`) green; `check-vocabulary`, `check-doc-links` green.

**P4 — Point launchers and tools.** `.claude/launch.json` (the `..\spaces`, `spaces-la-showcase`, `spaces-blank`,
`la-basic\…` entries, and the zain demo's `-Dassist.safety.roots`), `tools/run-mfs.ps1`, `tools/run-backend.ps1`,
`tools/seed-*`, `inspecto/package-spaces.ps1`, `inspecto/package-demo-bake.ps1`. The safety and jail roots must
include the data root. `spaces.ref` in each kept bundle (the config path) and `SPACES_DATA_ROOT` beside it.
- **Verify:** `node tools/check-launchers.mjs` and `node tools/check-safety-roots-declared.mjs` pass.

**P5 — Boot and smoke.** One current build, then a second checkout on its own `_tag` data root: the **`smoke`**
skill (`/health`, Space list, one Pipeline run), stopped afterwards.

**P6 — Clean up.** Classify each sibling folder keep / archive / delete with a reason; delete unambiguous scratch
first (`.m2-*`, `*-scratch`, `verify-*`, `ui-bundle-verify-*`). `bi-p1-projection`: the operator grants the shift
account access (not done here), then `git cherry origin/master HEAD`; push anything unpushed, then
`git worktree remove`. Never delete unpushed commits blindly.
- **Verify:** `git worktree list` shows only the checkout; nothing under `inspecto_spaces` or a backup touched.

## 6. Risks and trade-offs

- **Tests now need Python.** The 22 LA golden classes generate their corpus first (P2.4), so a developer machine
  and CI both need Python; `tools/affected-modules.mjs` must treat a change to a `tools/gen-la-*` file as reaching
  those classes. If that proves too heavy, the rejected fallback (D-SC4 option 2) is a small pinned fixture per
  LA Template — reopen D-SC4 rather than weakening a test.
- **Generators must be deterministic across machines** — they are tested only on this one's CI.
- **One data root, one config location** (§2): a second checkout silently sharing `inspecto_spaces` is refused at
  boot; that is the guard working, not a bug.
- **A migrating build** can leave a Space an older build cannot read — test other versions on a fresh `_tag` root.
- **Working-tree changes under `spaces/`** (`default/config/registry/datasets/`, `onboarding-counters.tsv` were
  untracked on 2026-11-25) belong to other work and are not touched.
- **The `User` account** owns some sibling folders; deleting them may need that account.

## 7. Out of scope

`C:\sandbox\backup`; history rewrite (D-SC7); a data root on another volume; engine root resolution.

## 8. Board rows (filed 2026-11-27)

`SPACES-CONSOLIDATION-1`, `SANDBOX-CLEANUP-1` and `LA-INDEX-UNDER-DATA-ROOT-1` (`hold: demand`; the engine writes
`<write root>/la-index` through `IndexRoutes.indexRoot`, D-SC9) are on `docs/BACKLOG.md`. The data-generation split planned as
`SPACE-DATA-GENERATED-1` shipped inside P2–P3 and needs no row.

## 9. P0 findings (2026-11-25, read-only plus a config-only backup)

**Where Spaces are.** 227 folders holding a `space.toon` sit under `C:\sandbox`; about 200 are extracted bundle
copies (the `la-*` bundles each carry the 11 industry Spaces; `antigravity`, `bi-p1-showme`, `bypass-b2`, `ps-s4`,
`verify-fc-canvas` each carry `default`, `demo`, `ucc`). The live roots are the repo `spaces/`, `C:\sandbox\spaces`,
`inspecto-mfs-root`, `spaces-preview`, `spaces-rfp`, `ims-spaces` and `inspecto-preview\inspecto-la` (whose `*.ref`
files point at `C:\sandbox\spaces\{inspecto-mfs,mfs-demo,la-mobile-money}`).

**The split layout already exists.** The `la-*` bundles use `spaces.ref → ../inspecto-la-spaces` and
`data_root: ../inspecto-la-data/<id>` (a data root holds `data`, `audit`, `duckdb`), so D-SC1 matches current bundle
practice. A second data root already exists at `C:\data\<id>` (`cricket-analytics` 9 MB, `default` 91 MB, `demo`
103 MB, `telco-assurance` 91 MB, `ucc` 77 MB and **`inspecto-mfs` 29 GB** of real data); `C:\sandbox\spaces\inspecto-mfs`
names it with an absolute `data_root: "C:/data/inspecto-mfs"`, and the data root's `.inspecto-space` stamp is the
**absolute** path `C:\sandbox\spaces\inspecto-mfs` — so moving that Space folder, or the data root, without
rewriting the stamp makes the boot refuse it (P2). No `tools/run-*.ps1` sets `SPACES_DATA_ROOT` or names
`C:/data`, so what launches the others is not yet known. D-SC8 must say whether `C:\data` folds into
`inspecto_spaces`.

**Private Spaces.** `mfs-demo` and `inspecto-mfs` hold real operator extracts (their `space.toon` descriptions:
"git-excluded: real data", "PRIVATE … never committed"). Their config must stay out of git — D-SC5 revised.

**`config/la-index`.** 1.74 GB of the 1.7 GB "config" of `mfs-demo` is `config/la-index/**/*.parquet`, the Link
Analysis index. The LA bundles keep it in config too, not in the data root. No `.gitignore` rule covers it — D-SC9.
`telco-assurance` is small: config 0.1 MB, `data` 8.9 MB, `duckdb` 144 MB.

**Tracked data by generator.** 17 of the 19 LA Templates that carry data have a generator (`gen-la-*-data.py`,
including `gen-la-mm-data.py` for `la-mobile-money`); `la-daily-feed` and `la-sample` (a few KB) do not.
`order-to-cash` has `gen-order-to-cash-data.mjs`. `demo`, `default`, `ucc` and the non-LA Templates (`aml`,
`mobile-money`, `payment-fraud`, `telco-fraud`, `telco-ra`, …) are hand-written (D-SC3).

**Backup.** Config-only copies of `telco-assurance`, `cricket-analytics`, `inspecto-mfs` (both copies), `mfs-demo` and
`la-mobile-money` (excluding `data`, `duckdb`, `audit`, `la-index`) are at `C:\sandbox\backup\spaces-p0-20261125`
(2.1 MB). Four match the source exactly; two differ by **five files in `config.secrets` this account cannot read**
(access denied; ACLs not changed): `mfs-demo` `.exploration-alias-book` (264 KB) and `inspecto-mfs`
`.pending-changes.key`, `.risk-score-mask.key`, `audit-anchoring.json`, `audit-anchors.jsonl`. No data backup exists:
the `roster-20261118` backups are config-only (0 MB), and `mfs-demo` data was last written 2026-11-20. Real private
data totals about 43 GB (`mfs-demo` 13.7 GB, `C:\data\inspecto-mfs` 29 GB) against 155 GB free, so a same-disk copy
fits but protects only against a mistake in the move, not against disk loss. **`mfs-demo` was copied in full on
2026-11-25** to `C:\sandbox\backup\spaces-p0-20261125\mfs-demo-full` (15.1 GB, no server running): 5 126 of 5 127
files, 16 256 724 519 B of 16 256 988 705 B — the only file missing is the unreadable `.exploration-alias-book`
(264 186 B, the exact difference). `C:\data\inspecto-mfs` was copied the same way to `…\data-inspecto-mfs-full` (28.5 GB, no server running): 61 225
of 61 225 files, 30 603 577 987 B of 30 603 577 987 B, no difference. Both full copies sit on the same drive as the
originals; neither protects against disk loss.

**Not done.** P0.4 (a second checkout against one data root) needs a built bundle. `bi-p1-projection`'s unpushed
commits are unchecked (ownership).

## 10. P0 closed and P1 executed in part (2026-11-25)

**P0 closed.** The stamp refusal (§2) is covered by the code (`SpaceDataRoot.stake`) and by an existing test
(`SpaceDataRootTest.aRelativeDataRootOnAnotherSpacesDataIsRefused`); it was **not** boot-tested, because no built
bundle was to hand. Still open and not doable from this account: the five unreadable `config.secrets` files, and
`bi-p1-projection`'s unpushed commits (a per-command `safe.directory` override would bypass the ownership check, so
it was not used).

**What P1 found before moving anything** — three facts that change the order:

1. **A mount cannot be switched on while a tracked Space holds files in its own `data/`.** `SpaceDataRoot.claim`
   refuses a Space whose folder still holds files under `data/`, `audit/` or `duckdb/`. `default`, `demo` and `ucc`
   still track `data/samples/**`, so they cannot move to a data root until P3 untracks it.
2. **Ids collide.** The repo `spaces/{default,demo,ucc,telco-assurance,cricket-analytics}` keep their data inside
   the Space folder (17 486, 18 835, 16 297, 2 400 and 1 859 files). `C:\data\<same id>` roots are stamped for
   *different* Space folders, `C:\sandbox\spaces\<id>` (near-empty stubs), so folding them into `inspecto_spaces\<id>`
   would put a root stamped for another Space where the repo Space must claim its own. And `inspecto-mfs` exists
   twice: `C:\sandbox\spaces\inspecto-mfs` (29 GB at `C:\data`) and `C:\sandbox\inspecto-mfs-root\inspecto-mfs`
   (2 308 data files in-folder, run by `tools/run-mfs.ps1`, junctioned into the repo `spaces/`).
3. **Private Spaces need no stamp rewrite if their config does not move.** `<spaces root>/<id>.ref` attaches a Space
   that lives elsewhere (the preview bundles already do this for `C:\sandbox\spaces\{inspecto-mfs,mfs-demo,
   la-mobile-money}`), so under D-SC2 the three private Spaces stay at `C:\sandbox\spaces\<id>` and are attached to
   the canonical root by a git-ignored `.ref`; their stamps keep naming the same folder.

**Moved (same drive, renames; counts and bytes equal before and after; backups in
`C:\sandbox\backup\spaces-p0-20261125` untouched):**

| Space | Moved | To | Files | Bytes |
|---|---|---|---|---|
| `inspecto-mfs` | `C:\data\inspecto-mfs` | `C:\sandbox\inspecto_spaces\inspecto-mfs` | 61 225 | 30 603 577 987 |
| `mfs-demo` | its `data\`, `audit\`, `duckdb\` | `…\inspecto_spaces\mfs-demo\` | 1 569 + 3 267 + 12 | 14 415 751 089 + 3 544 574 + 8 678 386 |
| `la-mobile-money` | its `data\`, `audit\`, `duckdb\` | `…\inspecto_spaces\la-mobile-money\` | 6 554 + 6 707 + 9 | 19 539 253 + 18 814 322 + 21 606 400 |

Each Space's own `space.toon` (local, untracked) now carries `data_root: "C:/sandbox/inspecto_spaces/<id>"`. Checked
statically against the boot rules: nothing left under the old `data/audit/duckdb`; each data root holds only those
three folders (`inspecto-mfs` also its stamp, still naming `C:\sandbox\spaces\inspecto-mfs`, valid). **Not booted**:
a boot of a real-data Space starts its Collectors and Pipelines, which would read and change the private extracts, so
the boot check waits for P5 with a stated plan for that.

**Held back, with reasons:** `default`, `demo`, `ucc` (tracked samples → after P3); `telco-assurance`,
`cricket-analytics` (id collision with the `C:\data` copies — which is live must be settled first); the
`inspecto-mfs-root` instance (second `inspecto-mfs`); the five remaining `C:\data` roots (stamped for the
`C:\sandbox\spaces` stubs). D-SC8's "fold `C:\data` in" therefore applies to `inspecto-mfs` only for now.

## 11. The collisions settled (operator, 2026-11-25)

Facts behind the choice (measured): for `default`, `demo`, `ucc`, `telco-assurance` and `cricket-analytics` the repo
`spaces/<id>` keep their data in the Space folder (newest write 2026-11-24); the `C:\data\<id>` roots (about 370 MB
in all, newest 2026-11-01) are stamped for the config stubs under `C:\sandbox\spaces\<id>` (older config copies,
2026-09 to 2026-10). `inspecto-mfs` exists twice: **A** `C:\sandbox\spaces\inspecto-mfs` (359 config files; the 29 GB
of real data, now at `inspecto_spaces\inspecto-mfs`) and **B** `C:\sandbox\inspecto-mfs-root\inspecto-mfs` (828
config files, 25 MB in all, newest 2026-11-24, run by `tools/run-mfs.ps1`, junctioned into the repo `spaces/`); their
configs differ (482 files only in B, 13 only in A).

- **D-SC10 ✅ — the repo Spaces are live; the `C:\data` rehearsal stays where it is.** The five `C:\data\<id>` roots
  and their five `C:\sandbox\spaces` stubs are neither folded nor touched. They are listed as legacy for P6, where
  deleting them is decided. D-SC8's "fold `C:\data` in" is therefore done for `inspecto-mfs` only. `default`, `demo`
  and `ucc` move to `inspecto_spaces\<id>` after P3 (tracked samples); `telco-assurance` and `cricket-analytics` (no
  tracked data) may move in P1 as their repo data is live — their in-folder `data/`, `audit/`, `duckdb/` become
  `inspecto_spaces\<id>`, with a `data_root:` line in their local `space.toon`.
- **D-SC11 ✅ — A is the canonical `inspecto-mfs`; B stays outside.** In P4 a git-ignored `spaces/inspecto-mfs.ref`
  naming A replaces the junction to B (removing a junction deletes the link, not B). B keeps running from
  `C:\sandbox\inspecto-mfs-root` through `tools/run-mfs.ps1` until it is retired in P6; no folder with data is
  renamed. B's 482 extra config files are not merged — whether A needs them is a question for whoever owns the MFS
  Space.

## 12. P1 continued: `telco-assurance` and `cricket-analytics` (2026-11-25)

No tracked file exists under either Space (`git ls-files` = 0) and no server was running. Each state folder was copied
to `C:\sandbox\backup\spaces-p0-20261125\<id>-state\` and counted equal, then renamed into
`C:\sandbox\inspecto_spaces\<id>\`; counts and bytes equal before and after.

| Space | Folder | Files | Bytes |
|---|---|---|---|
| `telco-assurance` | `data` / `audit` / `duckdb` | 2 400 / 23 / 17 | 9 311 466 / 18 723 / 151 328 504 |
| `cricket-analytics` | `data` / `audit` / `duckdb` | 1 859 / 0 / 20 | 3 817 625 / 0 / 4 571 813 |

Each local `space.toon` now carries `data_root: "C:/sandbox/inspecto_spaces/<id>"`. ⚠ That is an **interim,
machine-specific line**: when these Spaces' config is tracked (D-SC5, P3), drop it and let `SPACES_DATA_ROOT` supply
the root (a committed absolute path would break every other checkout). ⚠ `telco-assurance`'s own description says
"Local mock, not committed" (the fact base is synthetic); confirm that it may be tracked before P3 does it. Not booted.

## 13. P2 step 1 built, uncommitted (2026-11-25)

**Built (tools only, nothing under `spaces/` touched):**
- `tools/la_gen_io.py` gains `data_dir(default)`: the folder a generator writes into is `--out <dir>` / `--out=<dir>`
  (a Space's `data` folder), else the Template's own `data/` as before. All 18 `tools/gen-la-*-data.py` route their one
  output constant through it; `tools/gen-order-to-cash-data.mjs` takes `--out` too and, with it, deletes nothing.
- `tools/seed-space-data.mjs` — `--list`, or `<template> --data <data folder>` — registers 18 Templates (17 `la-*` and
  `order-to-cash`) against their generators and runs them with `--out`. A missing Python is a named failure.
- `tools/seed-space-data.test.mjs` (4 tests, 71 s): the registry names every `tools/gen-*-data.*` once and each writes
  the Template it is registered for; an unregistered Template fails by name; **every generator run with `--out`
  reproduces the Template's committed files byte for byte** (so untracking loses nothing); `--out` leaves the Template
  folder untouched. The parity test was falsified: changing one generator's seed makes it fail, naming the Template.
- The existing `gen-la-{card,claims,showcase}`/`gen-order-to-cash` tests still pass (default output unchanged).

**Findings:** CI is `ubuntu-latest` (plus one `windows-latest` job) and names no Python; the runner images ship it, but
nothing declares it, and **no workflow runs the `gen-*` tests at all** (the unwired-tools-tests baseline,
`TOOL-TESTS-UNWIRED-BASELINE-1`). The pre-commit wiring guard refuses an unwired tool test, so
`seed-space-data.test.mjs` is wired **now** into the `slow-fixtures` job of `ci.yml` (not `guards:`: ~70 s, and every
`guards:` step is paid on every commit) with an explicit `actions/setup-python` step. `CardLinksJobGoldenTest` (and its siblings) copy the whole Template
folder, `data/` included, into a temp Space (`Files.walk(TEMPLATE)`), so the Java conversion is: copy config, then run
`seed-space-data` into the temp Space's data folder.

**Not yet done in P2:** (a) the Java helper and the 22 LA golden classes (four modules: `inspecto`,
`platform/inspecto-engine`, `platform/inspecto-graph`, `la/inspecto-geo-link`); (b) moving the hand-written golden
samples into module test resources and repointing the corpus classes (`AmlCorpus`, `PaymentFraudCorpus`,
`MobileMoneyCorpus`, `TelcoRaCorpus`, …); (c) the Template spec text — written in P3, when it becomes true.

## 14. P2.4 built; P2.3 premise corrected (2026-11-25)

**P2.4 — the LA golden tests generate their corpus (built, verified, committed in its own lane).**
- One helper, `com.gamma.etl.LaCorpus` in the `inspecto-etl` test tree (every consumer module already depends on that
  test-jar): `data(template)` runs `node tools/seed-space-data.mjs` once per JVM into `<module>/target/la-corpora/<t>/data`;
  `install(template, space)` copies the Template without its `data/` and adds the generated corpus. A missing Node or
  Python is a named `IllegalStateException`, never a skip. `inspecto-graph` keeps its own 40-line copy of `data(...)`
  (`com.gamma.graph.LaCorpus`): the module is dependency-free (`check-module-deps.mjs`) and the etl module reaches it
  through `inspecto-analytics`, so it cannot be its test dependency.
- Converted: 13 `*LinksJobGoldenTest` in `platform/inspecto-engine` plus `MmLinksJobGoldenTest`,
  `LaClaimsLinksJobTest`, `TelecomLinksGoldenTest`; the four `La*GoldenTest` and `ClaimsCorpus` in `inspecto-graph`;
  `LaShowcaseRiskScoreGoldenTest` (scoring); `LaClaimsTemplateTest` (`inspecto`); and in `inspecto-geo-link`
  `ControlApiLaShowcaseFiguresTest`, `ControlApiTelecomPatternPacksTest`, `ControlApiLaShowcaseGeoSceneTest`,
  `ControlApiLaClaimsGeoSceneTest`, `ControlApiFullCycleIndexStatusTest`. **The first survey (22 classes) missed every
  class that spells the path as separate `Path.of("..", "_templates", "la-x")` arguments**; the second found the rest.
  `La*TemplateTest` classes read only `config/` and are untouched.
- Proof: 247 targeted tests green (graph 33, engine 165, `inspecto` 35, geo-link 13, scoring 1) **with all 17 Templates'
  `data/` folders deleted and every `target/la-corpora` cleared**; the unconverted `LaAuditGoldenTest` fails 9 of 9 on
  `NoSuchFileException …/la-audit/data/inbox/ap_invoice` in the same state. Full reactor gate NOT run.
- Committed Template data the generators do **not** produce (found while checking): `la-mobile-money/data/staged/README.txt`,
  `la-showcase/data/staged/README.txt`, `order-to-cash/data/o2c_open_items/`. P3 must move the READMEs (into the
  generator or the Template config) or drop them on purpose.

**P2.3 — the premise of D-SC3 was wrong for five of the six Templates.** `AmlCorpus`, `PaymentFraudCorpus`,
`MobileMoneyCorpus`, `TelcoRaCorpus` and `TelcoFraudCorpus` are **Java generators** with a fixed seed (each has a
`main` or `writeTo` and an `-D…regenerate` switch), and their golden tests already assert "the committed samples are
exactly the generator's output" — so `aml`, `mobile-money`, `payment-fraud`, `telco-ra` and `telco-fraud` are not
hand-written fixtures but generated corpora like the LA ones. Copying them into test resources would duplicate
generated data; the right shape is the P2.4 one (the tests build the corpus from the generator into the temp Space and
the pin tests shrink to a determinism check), which is a different change from the one D-SC3 signed. **Not built;
awaiting a decision.** The `demo`, `default` and `ucc` Spaces' `data/samples` are a different case again: they are
product demo data consumed by `tools/seed-samples.mjs --all` (the `launch.json` entries and the CI seed-path guard),
`tools/check-split-identity-fixture.mjs` (a `guards:` step reading `spaces/demo/data/samples/roaming_tap`), the
customer and App tools, `SpaceSamples.load` and about thirteen Java tests (`DemoCorpusIngestTest`,
`ControlApiConnectionsTest`, …). `demo` also carries its own Python generator inside `data/samples`.

## 15. P2.3 built: the five Java-generated corpora (2026-11-25, D-SC3 amended by the operator: "generate, not copy")

`aml`, `payment-fraud`, `mobile-money`, `telco-ra` and `telco-fraud` are generated by fixed-seed Java classes, so their
golden tests now **build the samples from those classes** instead of reading `data/samples` from the Template.
- `com.gamma.etl.SampleCorpora` (the `inspecto-etl` test-jar): `copyTemplate(template, to, samples)` copies a Template
  without its `data/samples` and writes the generated files in their place.
- `MobileMoneyCorpus`, `TelcoRaCorpus` (from `inspecto-reconciliation`) and `TelcoFraudCorpus` (from `inspecto`) moved
  into that test-jar, unchanged except `public` and a static `samples()`, because two scoring tests
  (`MobileMoneyRiskScoreGoldenTest`, `TelcoComposedBypassTest`) and `TelcoComposedSpaceGoldenTest` need more than one of
  them across modules. Their own packages are kept, so the members the old tests use stay package-private. `AmlCorpus`
  and `PaymentFraudCorpus` stay in `inspecto-scoring` (only that module uses them).
- The "committed samples equal the generator's output" pin tests became determinism tests; `TelcoRaGoldenTest`'s
  regenerate-on-ask hook (it wrote into the Template) is gone. The `-D…regenerate` switches that refresh the zero-row
  sink seeds `data/<sink>/seed.parquet` remain.
- Proof: 69 targeted tests green with `data/samples` of all five Templates deleted (scoring 22, reconciliation 12,
  `inspecto` 35). The same deletion made `TelcoRaGoldenTest.theTemplateIngestsEvaluatesAndAlertsEndToEnd` fail on
  `NoSuchFileException …/telco-ra/data/samples/switch_xdr/SWITCH_XDR_20260703.csv` — a read the first pass had missed,
  now fixed. Full reactor gate NOT run.

**Found for P3 (not built here):**
1. **Template sink seeds are `data/` files too.** Each of these Templates commits zero-row `data/<sink>/seed.parquet`
   (e.g. `aml/data/aml_threshold/seed.parquet`) that the seed gate and the golden tests need; they are config-like, a few KB,
   written by a `-D…regenerate` switch through DuckDB. The P3 guard must either allow `spaces/_templates/*/data/*/seed.parquet`
   or the seeds move under `config/`.
2. **Two seed tools read these samples:** `tools/seed-screening-demo.mjs` (`aml`) and `tools/seed-telco-fraud-demo.mjs`
   with `tools/seed-telco.test.mjs` (`telco-fraud`). With the samples untracked they have no source; the corpora are Java,
   not Node, so P3 either keeps these five `data/samples` tracked (about 0.7 MB) or gives the tools a generated source.
3. The `demo`, `default` and `ucc` Spaces' samples are a separate question (§14).

## 16. P3a built: the spaces-config-only guard, as a ratchet (2026-11-25)

Nothing is untracked yet; this change stops data entering `spaces/` and fixes today's debt so it can only go down.
- `tools/check-spaces-config-only.mjs` (+ `.test.mjs`, 8 cases, each defect planted and its finding required) in the
  `guards:` job, so the pre-commit hook runs it (it reads `git ls-files` and sizes, ~0.1 s). It fails on **NEW** (tracked
  data outside the baseline, or a data-extension file outside `config/` and `tests/`), **GROWN**, **SHRUNK** (lower the
  baseline in the same change), **STALE** (delete the entry), **BIG** (a tracked file over 256 KB) and **IGNORE**.
- `tools/spaces-config-only.baseline.json`: 33 `data/` folders, 811 files, 92.4 MB, each with its reason — 17 generated
  `la-*` corpora (P3b), 5 Templates whose samples a Java test generator builds (aml, payment-fraud, mobile-money, telco-ra,
  telco-fraud) and 8 hand-written ones, and the `demo`/`default`/`ucc` Spaces' samples (P3c).
- Scope rules: `audit/` is state only at the Space root (la-audit authors a `config/audit/` of its own); a Space's `tests/`
  folder holds authored Pipeline test fixtures; `config/la-index/` is data, and `.gitignore` now ignores it (D-SC9).

**P3b — untrack the 17 generated `la-*` corpora (about 90 MB): not started.** The tests no longer need them (P2.4); the
consumers outside tests do: the 18 `tools/seed-la-*.mjs --scaffold` copies (each hand-copies `<template>/data` into the new
Space), `tools/package-la-showcase.mjs` (stages the Template's data for the data zip), the `package.ps1` Template gallery
(bundles `spaces/_templates/<t>` with its data), and the two staged `README.txt` files (la-mobile-money, la-showcase) no
generator writes. Each must call `tools/seed-space-data.mjs`, which needs Python on the packaging machine.
**P3c — the rest (about 2 MB): decisions open.** The five Java-generated Templates' samples (read by
`tools/seed-screening-demo.mjs`, `tools/seed-telco-fraud-demo.mjs`), the hand-written Templates' samples and sink seeds, and
the `demo`/`default`/`ucc` samples.

## 17. P3c settled (operator, 2026-11-25)

Outside the 17 LA corpora the repository tracks about 1.6 MB under `spaces/`: samples (demo 759 KB, telco-fraud 325,
payment-fraud 119, telco-ra 103, aml 79, mobile-money 79, la-sample 33, ucc 29, default 14, order-to-cash 11, starter 1)
and 79 zero-row sink seeds (26 KB). Seed tools, the product's first-run samples, a CI guard and about 13 Java tests read
them.
- **D-SC12 ✅ — one kind of Space data is permitted: a named, capped exception.** Pristine samples under `data/samples/`
  (up to 1 MiB per Space or Template) and sink seeds the seed gate needs (`seed.parquet`, `schema-seed.parquet`,
  `entries.parquet` or a dot-marker under `data/<sink>/`, up to 16 KiB each). This is the design `.gitignore` always had
  ("data/samples is the one committed exception"), now enforced by `tools/check-spaces-config-only.mjs` (new finding
  `CAP`; 12 test cases). The independent review found the first seed rule too loose (any dot-file, any depth, no total);
  it is now exact names (`seed.parquet`, `schema-seed.parquet`, `entries.parquet`, `.<name>-output` markers), never in
  inbox, staged, samples, duckdb or audit, 16 KiB each and 256 KiB per Space or Template, and `_industries` is not a Space. Only the generated LA corpora are debt.
- **D-SC13 ✅ — a drift check, "pin if present".** `SampleCorpora.assertCommittedMatches` (etl test-jar) compares the
  samples a Template still commits with its generator's output and is **skipped when the folder is absent**, so the golden
  tests stay independent of the committed copy while a stale copy still fails; `-Dsamples.regenerate=true` rewrites the
  folder from the generator. One test in each of `AmlPackGoldenTest`, `PaymentFraudTemplateGoldenTest`,
  `MobileMoneyPackGoldenTest`, `TelcoRaGoldenTest`, `TelcoFraudTemplateGoldenTest`.
- Proof: with `aml` and `telco-ra` samples tampered by one byte, both checks failed naming the file; with `payment-fraud`'s
  samples deleted its check was skipped; `mobile-money` and `telco-fraud` passed; `-Dsamples.regenerate=true` restored the
  tampered `aml` sample byte for byte (`git status` clean). Nothing under `spaces/` changed.

**Effect on P3b:** the baseline is now the 17 generated `la-*` corpora only (589 files, 90.9 MB). Everything else tracked
under `spaces/` is either configuration or inside the permitted exception, so the guard no longer carries any other debt,
and the open items of §14–§16 shrink to the P3b consumers (the 18 `seed-la-*` scaffolds, `package-la-showcase.mjs`, the
`package.ps1` Template gallery) and the two `staged/README.txt` files no generator writes.

## 18. P3b built: the 17 generated LA corpora are untracked (2026-11-25)

589 files and 90.9 MB leave the repository (`spaces/_templates/la-*/data/inbox` and `staged`); the ratchet baseline is empty.
Found by deleting the corpora in a lane and running every guard and the LA node tests (41 failures, then 0):
- **Scaffolds:** the 17 `tools/seed-la-*.mjs --scaffold` copies took the Template's `data/` with a copy; they now call
  `tools/space-data.mjs` `seedData(template, dataDir, templatesDir)`: copy the Template's `data/` if it still ships one,
  else run its generator (`tools/seed-space-data.mjs`). The Template gallery copy a scaffold makes carries no corpus.
- **Packaging:** `tools/package-la-showcase.mjs` read `git show HEAD:` for every file, so the data zip would have been
  silently empty (its own guard refuses a Template with no data); it now lists and reads each registered Template's
  generated `data/` as well, single and composed (industry) Spaces alike. `inspecto/package.ps1` stages the gallery from
  `git ls-tree HEAD`; after `Copy-SpaceTemplates` it runs the generator for every staged Template that has one, before the
  Space is composed and before the demo bake.
- **Tests:** the 17 `tools/gen-la-*-data.test.mjs` pinned "generator twice = the committed bytes" and read the corpus in
  the Template; each now generates into a scratch folder with `--out` (the same assertions, "= the same bytes"), and
  `seed-space-data.test.mjs` no longer compares with the repository. A missing Python is a named failure.
- **The two `staged/README.txt` files** no generator wrote; `gen-la-mm-data.py` and `gen-la-showcase-data.py` now do, byte for
  byte as committed.
- **`.gitignore`:** `/spaces/_templates/*/data/inbox/` and `/staged/`, so a generator run without `--out` cannot be committed;
  `data/samples` and sink seeds stay tracked (D-SC12).
- **Not run end to end here:** `inspecto/package.ps1` (a full bundle build; its new step is parse-checked and its inputs are
  the tool the tests exercise), the demo bake, and `tools/seed-uat.ps1`.

## 19. P4: launchers and the private Spaces (2026-11-25)

**What the survey found.** Almost nothing tracked still points at the old roots: `tools/run-backend.ps1` already defaults
`-SpacesRoot` to the repository's `spaces/` (the canonical root, D-SC2); `inspecto-demo-spaces` and `inspecto-geolink` in
`.claude/launch.json` use `..\spaces` from their bundle folder; `inspecto-la-app` scaffolds its own throwaway root;
`zain-dial-digit-demo` runs from its own folder; `inspecto-mfs` runs `tools/run-mfs.ps1` on its own root
(`C:\sandbox\inspecto-mfs-root`, the second instance, which stays outside by D-SC11). The two `inspecto-basic-*` entries name
`C:\sandbox\la-basic\…`, which no longer exists (P6). `.claude/launch.json` is another lane's uncommitted file and was not
edited. `SPACES_DATA_ROOT` is not set by any tracked launcher.

- **D-SC14 ✅ — the private Spaces attach on demand, detached by default.** A dev backend boots every Space under `spaces/`, and
  attaching `inspecto-mfs` (29 GB), `mfs-demo` (14 GB) and `la-mobile-money` would open ~43 GB of real data and start their
  Collectors and Pipelines on every boot. Their `<id>.ref` pointers live in `C:\sandbox\inspecto_spaces\_refs`;
  `tools/attach-private-spaces.ps1 -Action Attach|Detach|Status` (`-Only <id>`) copies them into `spaces/` and removes them again,
  touches only `<id>.ref` files, and refuses a real Space folder of the same id or a ref that names another folder. `/spaces/*.ref`
  is git-ignored (a ref names a folder on this machine). The junction `spaces/inspecto-mfs` to the small second instance was removed
  (the link only; its 5 916 files are untouched), so a plain dev boot no longer sees any `inspecto-mfs`.
- **D-SC15 ✅ — `demo`, `default` and `ucc` keep their runtime state in the Space folder.** A Space with a data root is refused
  while files remain under its own `data/` (`SpaceDataRoot.claim`), and these three keep tracked `data/samples` there (D-SC12), so
  they cannot split, and **`SPACES_DATA_ROOT` must stay unset while they sit in the spaces root**. Only Spaces without tracked
  samples get a data root in `inspecto_spaces`: the private three (a local `data_root:` in each `space.toon`), `telco-assurance`
  and `cricket-analytics` (P1), and any Space created from a Template. This is a documented exception to D-SC1; their ~100 MB of
  state is git-ignored.

Verified: the script on a temporary spaces root (status, attach, an id clash, a foreign ref, a missing target, detach: only the
matching ref is removed; exit code 1 on any problem). Not verified: a server boot (P5), and whether the file routes' safety roots
cover a data root outside the spaces root.

## 20. P5: boot and smoke (2026-11-25)

**The safe boot set.** A throwaway spaces root (`C:\sandbox\p5-root`) held only `<id>.ref` pointers to the three synthetic Spaces that
moved to `inspecto_spaces`: `telco-assurance`, `cricket-analytics` and `la-mobile-money`. The two real-data Spaces (`inspecto-mfs`,
`mfs-demo`) were **not attached**, so nothing started a Collector on a private extract; their file counts and bytes were identical
before and after (4 848 files 14 427 974 049 B; 61 225 files 30 603 577 987 B). `la-mobile-money` had no state backup, so its 13 270 files
(59 959 975 B) were copied and counted equal first (`C:\sandbox\backup\spaces-p0-20261125\la-mobile-money-state`).
**The build:** the existing deploy bundle's `lib\*.jar` (built 2026-11-24 12:17; this checkout has no `runtime\`) on the system JDK 27,
without `inspecto-oidc.jar` and `inspecto-policy.jar`; with them the server exits at start (`OidcAuthenticator` needs its
configuration), because the bundle's edition above Basic authenticates.

| Probe | Result |
|---|---|
| `GET /health` | 200 `{"status":"UP"}` after 14 s; `SpaceManager: 3 space(s) booted` |
| `GET /api/v1/spaces` | 200; all three `integrity: ready` (`la-mobile-money` first `computing`), each `location.dataFolder` in `inspecto_spaces\<id>`, `referenced: true`, `holdsData: true` |
| `GET …/datasets/<name>/rows?limit=2` | 200, 2 rows each: `telco-assurance/assurance_benefit`, `la-mobile-money/agent_master_dataset`, `cricket-analytics/matches` (reads of a data root outside the spaces root) |
| `POST …/cricket-analytics/runs/matches/trigger` | 202; the run wrote its status file under `inspecto_spaces\cricket-analytics\data\matches\status` (a write there); no safety-root refusal in the log |
| first boot stamps | `.inspecto-space` written for all three, each naming its Space folder |
| **P0.4, the second checkout** | a second copy of `cricket-analytics` (config only, same `data_root:`) on another spaces root was **refused**: "data_root … belongs to the Space at …\spaces\cricket-analytics, not …\p5b\spaces\cricket-analytics"; 0 Spaces booted; the original's stamp and data untouched |

**Seen at boot:** `la-mobile-money` lost 16 `.processed` markers under `data/<feed>/markers/` and gained DuckDB files
(`inspecto-acquisition.db` and three WALs); its inbox is empty, so nothing is re-ingested. Housekeeping, not a defect found; noted
because the state moved with the Space. Both servers were stopped and their ports closed; the scratch roots were deleted.

**Not verified:** a boot of the private Spaces (deliberately; it needs a copy of their data, not the original), the assistant's
`assist.safety.roots` file routes against a data root outside the spaces root, a boot of a **fresh** build of master (the bundle is a
day old), the `SPACES_DATA_ROOT` mount form, and `demo`, `default` and `ucc` in the canonical root with their tracked samples.

## 21. P6 inventory (read-only, 2026-11-25) — nothing deleted

`C:\sandbox` holds 73 folders (156.4 GB) and 290 loose files (296 MB). Sizes are from a read-only scan (`robocopy /L`); a folder is
"referenced" when a tracked file names it (`git grep` on `origin/master`). **Nothing has been moved or deleted; every class below needs
your yes first.** Folders and files owned by the second Windows account `User` (all `la-*` bundles, `la-share`, `ims-mfs-demo`,
`inspecto-mfs-root`, `bi-p2-explain` and 22 loose files) may refuse a delete from the shift account; that is untested.

| Class | Size | What it is | Proposal |
|---|---|---|---|
| **A** regenerable bundles and installs | 11.6 GB, 12 | `la-cv-pilot`, `la-cv-L1`, `-L2`, `-L2b`, `-L3` (extracted standard bundles carrying the 11 industry Spaces, 2026-11-20..22, owner `User`); `la-preview-demo-20261116` and `la-basic-20261116` (dated installs; docs and `tools/la-demo-cases.mjs` name the first, `.claude/launch.json` names `la-basic` without the date, so that entry is stale); `la-video-basic`, `-shots`, `-zero` (video-production leftovers); `rfpdemo-run`, `preview-la-run` (October) | quarantine, then delete once you confirm nothing runs from them |
| **B** snapshots and scratch | 0.7 GB, 24 | `bypass-b2`, `ps-s4`, `verify-fc-canvas` (source-tree copies; `ps-s4` is only a temp-dir name in a test); `o6-verify-m2`, `duckdb201-verify-scratch`, `gate-persist-affected`, `lane-*`, `ui-bundle-verify-*`, `vrs-scratch`, `verif-scratch`, `b3p2-probe`, `spike-inputs`, `la-perf-runtime-evidence` (JFR evidence), …; plus the loose gate and lane logs (2026-10-31..11-24, the largest 92.7 MB) | delete after a glance; keep `la-perf-runtime-evidence` if the perf plan cites it |
| **C** older backups of live installs | 0.8 GB, 2 | `ims-mfs-demo-backup`, `inspecto-mfs-backups\before-map-move` | delete once you accept the P0 backups as the fallback |
| **D** delivered or built artifacts | 7.3 GB, 6 | `la-share` (its `TEAM-NOTE.txt`: three zips and the case videos sent to the team), `builds` (the `inspecto-basic-la-app` zip and sha), `la-deploy-pkgs`, `inspecto` (4.2 GB: `inspecto-la`, `-la-data`, `-la-spaces`, `inspecto-relocatable` from the relocatable-roots verification), `inspecto_training` (video), `la-video-out` (an mp4) | **ask the owner**; archive off the box rather than delete |
| **E** live environments | 67 GB, 15 | `inspecto-clean` (this checkout), `inspecto_spaces`, `spaces` (config of the private Spaces **and** five legacy stubs), `inspecto-preview` (the Preview install on :8080 per `.claude/memory/preview-la-demo.md`, 10.5 GB, whose `backup\` and `parked\` are redeploy debris), `spaces-preview`, `ims-mfs-demo` (the Basic install on :8098), `inspecto-mfs-root` and `inspecto-mfs-bundle` (`tools/run-mfs.ps1`), `archive` (named by three skills), `la-voice` (`tools/la-video-voice.mjs`), `zain-dial-digit-demo` (a launch entry), `drill-ha` (named by the HA drill guide), `ims-spaces`, `data`, `spaces-rfp` (a client working set: back up, never `git clean -x`) | keep; prune inside `inspecto-preview\backup` and `parked` later |
| **F** other lanes | 1.3 GB, 3 | `bi-p2-explain`, `ak2-decisions` (registered worktrees) and `.m2-bi-p2-explain` | not mine: leave until their lanes push |
| **G** other projects, tooling | 1.2 GB, 10 | `asn-decoders` (its own git clone), `cgi_decoder`, `vuln-db`, `zain-sudan`, `HUR`, `incubetor`, `.idea`, `.vscode`, `.graalvm-cache`, and `antigravity` (0.8 GB, written today, three Spaces, owner unknown) | out of scope; ask about `antigravity` |
| **H** backups of this effort | 66 GB, 1 | `backup\spaces-p0-20261125` (about 48 GB: both full copies of the real data, and the config and state copies) beside older ones: `ucc-file-processor` 10 GB (2026-07-30), `duckdb_cli` 2.9 GB, `fuse-demo-v17.2.0` 1.4 GB, `downloads` 1.4 GB, four `ims-mfs-demo-predeploy-*` of 0.39 GB, … | keep the P0 set until the private Spaces have booted from `inspecto_spaces` (they have not); ask about the older ones |

**Legacy rehearsal (D-SC10):** `C:\data\{default,demo,ucc,telco-assurance,cricket-analytics}` (0.37 GB) and the five stubs
`C:\sandbox\spaces\{default,demo,ucc,telco-assurance,cricket-analytics}` (config copies from 2026-09/10): quarantine together.

**One thing to verify before the live installs next start:** the Preview and Basic demo installs boot `inspecto-mfs`, `mfs-demo` and
`la-mobile-money` through `spaces\<id>.ref` to `C:\sandbox\spaces\<id>`. Their state now lives under `inspecto_spaces`, named by each
`space.toon`'s `data_root:`, so they should find it, but neither install has been started since the move. The team memory note that
described the old `C:\data` path is corrected in this change.

**How a delete would go:** move each approved class into `C:\sandbox\_pending-delete-<date>\` first (a same-drive rename, instant and
reversible), run the live installs and a boot once, then delete the quarantine folder. Nothing in class E, F, G or H moves without a
separate yes.

## 22. P6: classes A and B quarantined (operator yes, 2026-11-25)

Moved, not deleted, into `C:\sandbox\_pending-delete-20261125\` (a same-drive rename, instant and reversible; `MANIFEST.txt` there lists
every name, size and original path; move a folder back to `C:\sandbox\<name>` to restore it). **35 folders, 11.76 GB, and 156 loose
`*.log` files, 279.8 MB**; 0 failures (the renames of the `User`-owned folders succeeded too).
- **A (12 folders):** `la-cv-pilot`, `la-cv-L1`, `-L2`, `-L2b`, `-L3`, `la-preview-demo-20261116`, `la-basic-20261116`, `la-video-basic`, `-shots`,
  `-zero`, `rfpdemo-run`, `preview-la-run`.
- **B (23 folders):** the source-tree copies, verification scratch and lane folders listed in section 21, and `la-perf-runtime-evidence`
  (no tracked file names it). **`spike-inputs` stayed:** `docs/superpower/2026-11-20-agent-kit-ak2-plan.md` cites it as the spike's full
  record, and that plan is active.
- **The loose logs:** every top-level `*.log` older than six hours (gate, lane, serve and verify logs, 2026-10-31 to 11-24).

Checked first: no process had a command line naming any of these folders; none was a registered worktree; the only listener on `:8090` is an
unrelated desktop app. Afterwards: the originals are gone, the live and kept folders (`inspecto-clean`, `inspecto_spaces`, `spaces`,
`inspecto-preview`, `spaces-preview`, `ims-mfs-demo`, `inspecto-mfs-root`, `inspecto-mfs-bundle`, `archive`, `la-voice`,
`zain-dial-digit-demo`, `drill-ha`, `spike-inputs`, `la-share`, `builds`, `backup`, `bi-p2-explain`) are all in place, and
`C:\sandbox` is down to 42 folders and 135 loose files. (`ak2-decisions` is gone too, removed by its own lane, not by this move.)

**To delete for good:** start the Preview and Basic installs once (they were not touched, but have not run since the move), then remove
the quarantine folder. **Two tracked paths now point at nothing:** `tools/la-demo-cases.mjs` and a brochure script name
`la-preview-demo-20261116`; and the two `inspecto-basic-*` entries in `.claude/launch.json` (another lane's uncommitted file) name
`C:\sandbox\la-basic\…`, stale since before this move. Not moved: classes C and D, the legacy rehearsal, E, F, G, H.

## 23. P6: the legacy rehearsal and class C quarantined (operator yes, 2026-11-25)

Second batch into `C:\sandbox\_pending-delete-20261125\` (same-drive renames; its `MANIFEST.txt` has a new section): **12 folders, 0.98 GB, 0
failures**, which brings the quarantine to 12.74 GB. Nothing deleted.
- **`legacy-c-data\`** (0.26 GB): `C:\data\{default, demo, ucc, telco-assurance, cricket-analytics}`, the data roots of the 2026-10/11
  rehearsal, stamped for the `C:\sandbox\spaces` stubs, newer data living in the repo Spaces (D-SC10). `C:\data` is left as an empty folder.
- **`legacy-stubs\`** (under 1 MB): the five matching config stubs from `C:\sandbox\spaces`, which now holds only the three private Spaces
  (`inspecto-mfs`, `mfs-demo`, `la-mobile-money`), `_templates` and `onboarding-counters.tsv`.
- **Class C** (0.72 GB): `ims-mfs-demo-backup` and `inspecto-mfs-backups\before-map-move`, the older backups of the live installs; the P0
  backups, whose counts and bytes were verified, are the fallback.

Checked first: no process names any of them; no `.ref` or `space.toon` under the Preview, Basic, `spaces-preview`, canonical or private roots
names the stubs or `C:\data`; the `_refs` pointers name only the three private Spaces. Still not moved: D, E, F, G, H. The quarantine can be
deleted once the Preview and Basic installs have started once.

## 24. Review (2026-11-27): the source moved on, and what improves

- **`origin/master` is 120 commits past the last Spaces push** (`8a221b3ad` to `137c7cdce`); none changes the layout. Re-checked on that
  head: the guard passes (1,632 tracked files under `spaces/`, no data folder, empty baseline), `package.ps1` still runs the seeding step and
  parses clean, and the generator registry still names 18 Templates. The Business Insights work added Insight config and a 2 KB seed to
  `business-assurance`, inside the cap. Twelve Templates still ship committed data, all small samples or sink seeds (largest `telco-fraud`,
  227 KB), all held by the §17 cap.
- **Fixed, with a test.** `seedData` (`tools/space-data.mjs`) took any `data/` folder as shipped data, even an empty one. A Maven test that runs
  a Template's Pipeline in place leaves empty `status` folders (`TEMPLATE-STRAY-STATUS-DIR-1`), after which a `seed-la-*` scaffold copied
  nothing and never ran the generator. It now copies only a folder that holds a file (`holdsFiles`); the new case in
  `tools/seed-space-data.test.mjs` fails without the change (`copied`, not `generated`).
- **Fixed, text only.** The three board rows of §8 are filed. `admin-guide-configuration-reference-spaces.md` gave `C:\data` as its example
  data root; it now shows the real layout. Two backspace bytes in this plan, where `\b` of a Windows path had been typed, are back to
  `\backup` and `\before-map-move`.
- **Found, left alone.** (1) Some test still writes into `spaces/_templates`; §14 and §18 moved the corpora to `target/` but did not search for
  other writers. A check that `git status --porcelain spaces/` is empty after an affected-module run would catch any writer; it needs the
  writer found first (`TEMPLATE-STRAY-STATUS-DIR-1`). (2) The comments in `tools/la-demo-cases.mjs` and `tools/record-la-live.mjs`, and a
  brochure script, still name the quarantined installs; they say where a recording was made, so they stay. The stale `inspecto-basic-*` entries
  in `.claude/launch.json` belong to another lane. (3) `package.ps1` seeding has only been parse-checked: the first bundle build is its real
  test, and no one has run one since P3b.
- **Next, in order.** (a) Boot the three private Spaces with `tools/attach-private-spaces.ps1`, then the Preview and Basic installs once, one at a
  time; (b) delete the quarantine; (c) the owner questions (class D, `antigravity`, older backups); (d) build current `master` once
  against `inspecto_spaces`, which also runs the `package.ps1` seeding. Item (a) is the gate for (b) and (d).
- **Closing this plan.** It is 59 KB against the 60 KB docs-health cap (a plan over it is refused), so add no more sections. When (a)–(d) are done, distill the as-built
  facts (the split layout, the generator contract, the guard, the private-Space attach, D-SC1…D-SC15) into
  `okf/product/module/config/plan-spaces-templates.md`, move anything still open to the board, then `git mv` this plan to
  `archived-documents/plans-archive/` with its row in `archived-documents/README.md` (the `handoff` skill checks this).
