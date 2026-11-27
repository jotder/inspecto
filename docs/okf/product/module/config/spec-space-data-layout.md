---
type: Spec
title: Space data layout — config in git, data outside it
description: How the repository keeps a Space's configuration under version control and its data out of it - the data root, the generated Template corpora, the guard and its capped exception, and the private Spaces (SPACES-CONSOLIDATION-1, as built).
item: module.config
audience: internal
status: current
verified: 2026-11-27 code-reviewed
code: [platform/inspecto-config]
---

# Space data layout — config in git, data outside it

As built by `SPACES-CONSOLIDATION-1` (2026-11-25, reviewed 2026-11-27). The rule: **a Space's configuration is
version-controlled, its data never is — data is supplied by the customer or generated on implementation.** No engine
change was made; the layout uses contracts the engine already had ([Space anatomy](architecture-space-anatomy.md) §2).

## 1. The layout

| What | Where | In git |
|---|---|---|
| Space config | `spaces/<id>/{space.toon, config/}` in the checkout | yes |
| Space state (`data/`, `audit/`, `duckdb/`) | the data root, `C:\sandbox\inspecto_spaces\<id>` on the shared sandbox | never |
| A Template's generated corpus | written by its generator when a Space is seeded or a bundle is packaged | never |
| Small samples and sink seeds | `spaces/_templates/<id>/data/…`, inside the caps of §4 | yes |

A Space names its data root with `data_root:` in `space.toon`, or the server takes `SPACES_DATA_ROOT` /
`-Dspaces.data.root=<mount>` and uses `<mount>/<id>`. The shipped `demo`, `default` and `ucc` Spaces keep their state in
the Space folder (D-SC15).

## 2. Engine contracts the layout relies on

- `-Dspaces.root` / `SPACES_ROOT` wins over the bundle's `spaces.ref`; `<id>.ref` attaches a Space kept elsewhere.
- A data root is stamped `.inspecto-space` with the absolute path of its owning Space folder at first boot, and a
  second owner is refused. Two checkouts cannot share one data root: that refusal is the guard working.
- A Space that names a data root and still holds files under its own `data/`, `audit/` or `duckdb/` is refused
  (`SpaceDataRoot.claim`). Move the state, then add `data_root:`.
- The data root must lie outside the spaces root and outside every Space folder.
- The Link Analysis index is written to `<Space config>/la-index`, inside the config folder. It is ignored in git
  (`/spaces/*/config/la-index/`) and left there (D-SC9; board row `LA-INDEX-UNDER-DATA-ROOT-1`).

## 3. Generated data

- **Generators.** `tools/gen-la-*-data.py` (18) and `tools/gen-order-to-cash-data.mjs` are deterministic and take
  `--out <dir>`; without it they write the Template's own `data/`. `tools/la_gen_io.py` `data_dir()` is the one place
  that reads the flag.
- **One entry.** `node tools/seed-space-data.mjs <template> --data <dir>` runs a Template's generators;
  `--list` prints the registry (18 Templates). A Template with no entry ships hand-written samples or none.
- **Scripts that lay down a Space** call `seedData` (`tools/space-data.mjs`): copy the Template's `data/` when it
  holds at least one file, else generate. An empty folder is not shipped data.
- **Packaging.** `inspecto/package.ps1` seeds every staged Template that has a generator, so a bundle's gallery
  carries its data; `tools/package-la-showcase.mjs` builds its data zip from the generators.
- **Tests generate, never read committed data.** The Java golden tests take their corpus from `LaCorpus`
  (`inspecto-etl` test tree; a copy in `inspecto-graph`) into `target/la-corpora/`, and from the fixed-seed corpus
  classes through `SampleCorpora`. A committed sample that still exists is pinned by a drift check
  (`-Dsamples.regenerate=true` rewrites it). Python is therefore needed on a developer machine and on CI.

## 4. The guard

`node tools/check-spaces-config-only.mjs` (CI `guards` job and pre-commit) refuses tracked data under `spaces/`.
Its baseline (`tools/spaces-config-only.baseline.json`) is empty, so any new data folder fails. Permitted, by name:

| Exception | Cap |
|---|---|
| `data/samples/**` | 1 MiB per Space or Template |
| sink seeds with an exact name (`seed.parquet`, `schema-seed.parquet`, `entries.parquet`, `.<name>-output`) | 16 KiB each, 256 KiB per owner; never under inbox, staged, samples, duckdb or audit |

`audit/` counts as state only at the Space root; `tests/` fixtures are allowed; `_industries` is not a Space.
`.gitignore` also covers `/spaces/*/config/la-index/`, a Template's `data/inbox/` and `data/staged/`, and
`/spaces/*.ref`.

## 5. Private Spaces

The real-data Spaces (`inspecto-mfs`, `mfs-demo`, `la-mobile-money`) are never committed. Their config lives in
`C:\sandbox\spaces\<id>` and their state in the data root. `tools/attach-private-spaces.ps1 -Action
Status|Attach|Detach [-Only <id>]` copies their `<id>.ref` files into the checkout's `spaces/`; they are detached by
default (D-SC14).

## 6. Decisions (operator, 2026-11-25)

| # | Decided |
|---|---|
| D-SC1 | split layout: config in git, state in the data root |
| D-SC2 | one canonical `spaces/`; a throwaway build uses its own tagged data root |
| D-SC3 | the Java-generated corpora are generated by the tests, not copied |
| D-SC4 | the Link Analysis corpora are generated; tests seed first |
| D-SC5 | track the config of three local Spaces; the two private ones stay local |
| D-SC6 | no Template contract change: a customer Template may still ship samples |
| D-SC7 | no history rewrite |
| D-SC8 | `C:\data` folded into `inspecto_spaces` |
| D-SC9 | `la-index` ignored in git and left in place |
| D-SC10 | the repo Spaces are live; the `C:\data` rehearsal was legacy |
| D-SC11 | the `inspecto-mfs` holding the 29 GB is canonical |
| D-SC12 | a named, capped exception for samples and sink seeds (§4) |
| D-SC13 | a drift check that pins a committed sample if present |
| D-SC14 | private Spaces attach on demand |
| D-SC15 | `demo`, `default` and `ucc` keep their state in the Space folder |

## 7. Gotchas

- A test that runs a Template's Pipeline in place leaves empty `status` folders in the checkout
  (`TEMPLATE-STRAY-STATUS-DIR-1`); `seedData` ignores them, the writing test is not yet found.
- A same-drive move of state keeps no second copy: back up first.
- The untracked corpora remain in git history (about 93 MB, D-SC7).

## References

- Plan and per-phase record (provenance only):
  [spaces-consolidation-plan](../../../../archived-documents/plans-archive/2026-11-25-spaces-consolidation-plan.md).
- Open items: [space-coherence-plan](../../../../superpower/2026-11-27-space-coherence-plan.md) §10, and the board rows
  `SPACES-CONSOLIDATION-1`, `SANDBOX-CLEANUP-1`, `LA-INDEX-UNDER-DATA-ROOT-1`.
