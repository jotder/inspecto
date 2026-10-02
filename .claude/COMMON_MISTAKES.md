# Common Mistakes

**⚠️ CRITICAL - Read at session start**

Fuller treatment in [docs/troubleshooting.md](../docs/okf/backend/build-run/troubleshooting.md).

---

## Top Critical Mistakes

### 1. `#` comments in TOON / ConfigCodec files

**Symptom**: Config parse failure / unexpected values in any `*_pipeline.toon`,
`*_connection.toon`, `*_rca.toon`, etc.
**Check**: Any line containing `#` in a JToon/ConfigCodec file.
**Fix**: Remove it. **No `#` comments are allowed** in JToon/ConfigCodec files.

### 2. Output directories nested inside the poll directory

**Symptom**: Startup validator aborts: `directory X must not be inside poll directory`.
**Check**: `database`, `backup`, `temp`, `errors`, `quarantine`, `markers` in the pipeline config.
**Fix**: Make them **siblings** of the poll dir, not nested — otherwise the ETL recurses into its
own output/scratch/marker space.

### 3. Forgetting `--enable-native-access=ALL-UNNAMED`

**Symptom**: Native-access warnings/failure launching the JAR (DuckDB).
**Check**: The `java` command line.
**Fix**: Always launch with `java --enable-native-access=ALL-UNNAMED ...`. The bundled
run/serve/ura scripts already include it.

### 4. All output lands in `year=NULL/month=NULL/day=NULL`

**Symptom**: Partition keys are all NULL.
**Cause**: The partition-key column value matches no configured date format.
**Fix**: Add the matching format to `date_formats` / `timestamp_formats` in the pipeline config.

### 5. Stale `.processed` markers / temp dir filling up

**Symptom (markers)**: File won't reprocess — a `.processed` marker exists in `markers/`
(mirrors the inbox tree, *not* in `inbox/`). Delete the marker to force reprocessing (auto-pruned
per `processing.duplicate_check.retention_days`).
**Symptom (scratch)**: `No space left on device` / OOM on a large file. DuckDB spills to
`dirs.temp` — point it at a roomy disk, set `processing.duckdb.temp_directory`/`memory_limit`/
`max_temp_directory_size`, or enable `processing.chunking`.

---

## Other gotchas

- **pg_duckdb view "column X does not exist"** — `read_parquet()` columns aren't visible to the PG
  planner. Every column must be explicitly aliased (`r['col']::type AS "col"`); use
  `generate_warehouse_views.py` to emit the DDL.
- **ORA-28002 junk lines leaking into data** — `skip_junk_lines` cap too low (Oracle
  password-expiry preamble). Raise the cap or set `-1` for unlimited scan.
- **DuckDB 1.1.1 Windows AVX2 crash** (`EXCEPTION_ACCESS_VIOLATION` in VCRUNTIME140.dll) — worked
  around by materializing a `transformed` table before `COPY TO`. Keep the workaround after any
  DuckDB upgrade.

---

## Parallel lanes (worktree agents) and build proofs

Learned the hard way on 2026-10-01 (D-S4 + D-1: 6 lanes, 3 of them started from the wrong commit).

1. **A lane worktree is cut from the last PUSHED `origin/master` tip — not from your local `HEAD`.** Observed twice: lanes started
   at `98fce480f` (pushed) while local `HEAD` was already several commits ahead. Anything unpushed (a helper refactor, a signed
   design) is invisible to the lane. Either push first, or open the lane prompt with
   `git merge --ff-only <sha>` — the commit is reachable because a worktree shares the object store. The lane that forgets
   builds on a base where the code it must move is still `private` and reports a confusing compile error.
2. **Give every lane the same preamble** (paste it; do not paraphrase): *first `git merge --ff-only <sha>`; do not push; do not
   touch other lanes' files, `docs/INDEX.md` or `.claude/launch.json`; no `node_modules` junction/symlink (cleaning the worktree
   wiped the main checkout's once); use uniquely named scratch scripts — the session scratchpad is SHARED and a lane's `mut.sh`
   was overwritten mid-run; `git add` only the files you changed; run `-Pedition-enterprise -pl <module> -am -Dtest=A,B` (commas,
   never `+`; `-am` or a stale sibling jar is tested) and count the `Tests run:` lines; report SHA, files, counts, surprises.*
3. **Integrate in the main tree by `git cherry-pick`** (lanes touch new files, so conflicts are only the shared `pom.xml` /
   bundle lists), then run the **independent** verifier once on the combined result — a lane's own green is not the gate.
4. **A closure script is a PREDICTION; `node tools/compile-clean.mjs` is the PROOF.** `tools/java-closure.mjs` predicts what a cut
   drags along; only a clean compile of the whole reactor says the cut is real. See
   [build-test.md](../docs/okf/backend/build-run/build-test.md) "Proving a cut".
5. **An incremental `mvn compile` after a shared-interface change is a FALSE GREEN** (downstream modules not recompiled; 9 s, exit
   0). Always `clean`, and read the log, not the exit code.
6. **A peer's STAGED file blocks a cherry-pick and an ff-merge in the main tree** — integrate through a clean worktree
   (`git worktree add .claude/worktrees/integ-x -b integ-x HEAD`, cherry-pick or copy your files there, commit, then
   `git merge --ff-only integ-x` in the main tree). The ff also refuses if your own copies of the new files are sitting
   untracked or modified in the main tree: delete or `git checkout --` them first (they equal the commit).
7. **Never `git commit -o <path>` with a peer's rows in the index** — `-o` sweeps the whole index entry of a shared file
   (`docs/INDEX.md`, `docs/BACKLOG.md`), not your hunk. Stage shared files by hunk, or commit from the clean worktree.
8. **Run `npm run lint` on an integrated UI change before pushing** — a lane's own green does not include the merged tree
   (`no-explicit-any` went red on master once from an integration).
9. **`JAVA_HOME` may be 26 while PATH `java` is 27** — Maven then dies "release version 27 not supported". Export
   `JAVA_HOME` to the PATH JDK (`java -XshowSettings:properties -version` prints `java.home`) before any `mvn`. The extracted LA
   modules are only in the reactor with `-Pedition-enterprise`.
10. **Check GitHub Actions after a push, not just the local gate.** CI was red on every master commit from 2026-09-30 and nobody
    saw it: the Linux-only failure in `inspecto-config` halts the reactor, so 28 of 41 modules were SKIPPED. `gh run list --limit 3`
    after every push; "BUILD FAILURE" early in the reactor hides everything after it.
11. **Read `git cherry-pick`'s exit status before the fast-forward.** A conflict in the SECOND of two cherry-picked commits left the
    worktree mid-pick; `git merge --ff-only` of that branch still succeeded with only the first commit, and removing the worktree hid
    the conflict. Chain `cherry-pick ... && test -z "$(git status --short | grep -E '^(UU|AA)')"` and stop on failure.
12. **A BACKLOG row's `→ owning-doc` pointer must resolve UNDER `docs/` (no `docs/` prefix) AND the target doc must name the row id**
    — the pre-push `check-backlog-homes` guard refuses the push otherwise. Run the guard on a lane's BACKLOG edit before integrating.
13. **After a push read BOTH CI and UI** (`gh run list --workflow UI`): the UI workflow is path-filtered, so it may not run on a
    docs/Java-only commit and its last result can be an older commit's. A 5 s default test timeout also fails on the slower runner:
    give a spec that builds thousands of links an explicit `}, 30_000);`.
14. **An empty list inside `role=listbox` / `mat-menu` is an axe CRITICAL (`aria-required-children`)**: render the empty-state text
    OUTSIDE the role container (or drop the role while empty, and the `aria-label` with it — a name on a role-less div is its own violation).

---

## Environment & tooling (shared — so every profile on this sandbox has them)

> Consolidated here from per-profile session memory. Auto-memory lives under each Windows profile's
> `~/.claude/` and is **not** shared between teammates (e.g. `User` vs `jotder`); durable facts belong
> in these tracked sandbox docs/skills, not in memory.

- **`package.ps1` must run under pwsh 7, not Windows PowerShell 5.1** — the script is BOM-less UTF-8 and
  5.1 garbles it. The embedded JVM is a **jlinked, trimmed Windows runtime** with a curated module set;
  building "online" has gotchas — prefer offline. (See the `build-verify` skill.)
- **Theming plugin doesn't hot-reload** — editing the gamma Tailwind theming plugin
  (`@gamma/tailwind/plugins/theming.js`) or tokens requires a **dev-server restart**; verify via
  `getComputedStyle(body).getPropertyValue('--gamma-…')`. (See the `angular-ui` skill §5.)
- **Security/edition direction**: no Spring/Quarkus migration — harden in place as Personal/Standard
  editions with IAM-delegated (Keycloak/WSO2) OIDC resource-server auth. (See `docs/EDITIONS.md`.)

---

**Last Updated**: 2026-06-18
