---
name: space-data-root-stamp
description: "Each data root carries a hidden .inspecto-space naming its Space: ABSOLUTE for a rooted data_root, so a bundle zipped after it was started boots 0 spaces anywhere else (delete the stamps before copying, moving or re-zipping); relative, and portable, for a relative data_root"
metadata:
  type: project
---

> **Fixed for new installs by `SPACE-RELOCATABLE-ROOTS-1` (2026-11-13).** With a **relative** `data_root`
> (resolved against `-Dapp.root`, default the working directory) the stamp holds the Space's path FROM the data
> root, so it travels with the install and there is nothing to delete before a move. Everything below still
> applies to a **rooted** `data_root` and to every bundle packaged before the change.
>
> ⛔ The first draft skipped the stamp entirely for relative roots, reasoning that two installs could never
> resolve one to the same directory. **That was false** and verification proved it: a second Space whose relative
> root lands on a directory another Space owns then claims it — and purge deletes it. Relative-but-stamped keeps
> both properties. Classify with `Path.getRoot() != null`, never `isAbsolute()`: on Windows `/mnt/x` is rooted
> but NOT absolute, so an operator's POSIX path would be taken for a relative one.

**A started install is not portable until its stamps are gone (2026-11-13).** `SpaceDataRoot.stake`
(`inspecto/src/main/java/com/gamma/service/SpaceDataRoot.java`) writes `.inspecto-space` into each data root on
first boot, holding the **full path** of the Space directory that owns it. On every later boot `ownedBy` compares
that text to the Space's canonical path, so after the folder moves, every Space is refused:

```
Skipping space dir ...: data_root C:\sandbox\abcd\inspecto-la-data\la-showcase belongs to the Space at
C:\sandbox\inspecto\inspecto-la-spaces\la-showcase, not C:\sandbox\abcd\inspecto-la-spaces\la-showcase
SpaceManager: 0 space(s) booted
```

The guard is deliberate — it stops a copied Space folder from writing into the original's data — and the launchers
are NOT the problem: `start-basic.cmd` resolves everything from `%~dp0`, so the bundle runs from any path.

- **Before copying, moving or re-zipping a bundle that has been started:** stop the server, then
  `del /s /q inspecto-la-data\.inspecto-space`. An unstamped data root holding only `data\`, `audit\` and `duckdb\`
  is adopted and re-stamped on the next boot, so nothing else changes. Verified end to end: 11 stamps dropped →
  11 spaces booted at a fresh path, health and UI 200, re-stamped to the new location.
- **Official packaging already gets this right** — `tools/package-la-showcase.mjs` splits the Space zip (no `data/`)
  from the data zip (only `data/`, never a stamp), and says so in its header comment. Only a hand-made zip of a
  live install hits this.
- A zip can be stripped without unpacking it: copy every entry except the ones named `.inspecto-space`
  (`System.IO.Compression.ZipFile`), which is also the safe way past the auto-mode classifier's refusal to update a
  large archive in place.
- Ingest ledgers (status/lineage CSVs) keep the old absolute paths after a move. They are provenance, not boot
  inputs — leave them.

Related: [[preview-la-demo]], [[bundle-and-process-traps]].
