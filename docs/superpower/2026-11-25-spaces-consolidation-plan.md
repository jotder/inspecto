# Spaces in source control, data outside it — one data root, one build target (`SPACES-CONSOLIDATION-1`)

Status: **APPROVED 2026-11-25 — D-SC1…D-SC9 all DECIDED (operator, 2026-11-25); no engine change; nothing built,
nothing moved. P0 started 2026-11-25 (§9); the config backups and both private-data copies are done and verified.** Opened 2026-11-25. Owner: this shift. Items: `module.config`, `layer/platform`. Extends `SPACE-DATA-ROOT-1` and
`SPACE-RELOCATABLE-ROOTS-1` (both shipped).

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

## 4. Decisions (open — operator signs each)

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

## 8. Board rows (not yet filed)

All decisions are signed, so file as `docs/BACKLOG.md` rows tagged `[items: module.config, layer.platform ·
kind: chore]`: `SPACES-CONSOLIDATION-1` (P0–P5, with the data-generation split as `SPACE-DATA-GENERATED-1`) and
`SANDBOX-CLEANUP-1` (P6), plus the follow-up `LA-INDEX-UNDER-DATA-ROOT-1` (P3, `kind: feature`, `hold: demand`;
the engine writes `<write root>/la-index` through `IndexRoutes.indexRoot`, D-SC9). Filing needs the census counts and
the ledger line (`tools/check-backlog-items.mjs`, `tools/roll-backlog-ledger.mjs`), so it is a separate step from this
plan's approval.

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
