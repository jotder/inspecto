---
name: preview-la-demo
description: "The demo is ONLY the unzipped Preview la-app bundle at C:\sandbox\inspecto-preview\inspecto-la on :8080 (WSO2 OIDC); update in place, no zips/linux bundles unless asked, and keep the runtime cacerts on redeploy"
metadata:
  type: project
---

Preview `la-app` bundle at `C:\sandbox\inspecto-preview\inspecto-la` (ONE spaces folder holding every LA demo: la-showcase,
la-showcase-off, la-claims, la-mobile-money, la-audit, la-daily-feed; non-LA Spaces such as `ims` are parked in
`inspecto-preview\parked`), started with `serve-preview-la.bat`, WSO2 at https://localhost:9443 (app
`inspecto_preview_la`; `scripts/wso2-preview-la-setup.mjs` creates its 19 users + role groups — a user's WSO2 groups ARE
the Space role names). Operator 2026-10-16: deploy every fix there; zip/linux builds are wasted time — only when asked.

- **Re-bake all Spaces (2026-10-24):** `scripts/preview-la/bake-all.ps1` — scaffolds the four seeders + la-daily-feed onto
  ONE demo-auth server (`package.ps1 -Edition Preview -DemoAuth -NoBakeDemo -NoUi`, run from a sibling worktree), triggers
  each Space's data-build jobs as its manager, seeds (idempotent, retried), stops gracefully, rebases the temp root to the
  relative `spaces`, parks the old Spaces under `parked\spaces-old-<stamp>` and copies the new ones in. Smoke:
  `scripts/preview-la/la-smoke.mjs` (real WSO2 sign-in of every user against every Space).
  **Traps:** (1) the graceful Ctrl+C stop needs a REAL console — from the agent's PowerShell/Bash tools it silently never
  arrives (a `ping` test process ignores it too); run the script in the Terminal panel (`run_in_terminal`). (2) The data
  Datasets come from `*/10` cron jobs, so a seed started right after boot gets `409 no data yet` — trigger the jobs first.
  (3) The rebase tool needs `jdk.compiler`: run it with the SYSTEM `java`, never the bundle's jlinked runtime.
  (4) `JAVA_HOME` is `C:\.jdks\jdk-27-win` (the old `Program Files\Java\latest` path is gone); the Maven repo is `C:\.m2`.

- **UI-only change:** build `inspecto-ui/dist/la-app` and robocopy `/MIR` it into the bundle's `ui\`.
- **Full redeploy:** worktree at origin/master, `npm ci --offline`, `package.ps1 -Edition Preview -Ui la-app`
  (~15 min); stop :8080; back up to `inspecto-preview\backup\predeploy-<stamp>`; robocopy over with `/XD spaces`;
  refresh only `spaces\_templates`; keep `serve-preview-la.bat`, the WSO2 setup script, `runtime-natives`, all Spaces.
- **Restore `runtime\lib\security\cacerts`** from the backup (or exclude it): the fresh jlinked runtime lacks the
  imported WSO2 cert and boot dies with `SSLHandshakeException` on OIDC discovery.
- **Redeploy as actually run 2026-10-25 (worked first time, ~15 min build + ~10 min swap):** (1) build with NO downtime in
  a sibling worktree: `pwsh -NoProfile -File inspecto\package.ps1 -Edition Preview -Ui la-app` (JAVA_HOME `C:\.jdks\jdk-27-win`);
  the staged bundle is `<worktree>\inspecto-deploy\` (ignore the zip); (2) diff `ls -A` of live vs new to find what `/MIR`
  would delete: expect to PROTECT `runtime-natives`, `serve-preview-la.bat`, `wso2-preview-la-setup.mjs`, `cacerts`, and
  expect a stale module jar to disappear (`inspecto-la-graph.jar`); (3) back up the static parts to
  `inspecto-preview\backup\predeploy-<stamp>` (exclude `spaces` + `runtime-natives`, ~550 MB) BEFORE the stop, and copy
  `spaces\` (~163 MB) AFTER the stop so it is consistent; (4) swap: `robocopy <new> <live> /MIR /XD <new>\spaces <live>\spaces
  <new>\runtime-natives <live>\runtime-natives /XF serve-preview-la.bat wso2-preview-la-setup.mjs cacerts` (full paths, both
  sides — a bare name would also skip same-named nested dirs; robocopy exit 0-7 is success), then `robocopy <new>\spaces\_templates
  <live>\spaces\_templates /MIR`; never copy the bundle's `onboarding-counters.tsv`; (5) START IT IN THE TERMINAL PANEL
  (`run_in_terminal` with `& C:\sandbox\inspecto-preview\inspecto-la\serve-preview-la.bat`; the tool refuses a cwd outside the
  session folders, the bat `cd`s itself) so the NEXT stop can be a real Ctrl+C; check `/health`, the served `main-*.js` against
  `ui\`, and grep the boot log for ERROR/Exception (the "navigation catalog is empty" WARN is expected).
- **Stopping a server that was started detached is ABRUPT.** A console Ctrl+C event (AttachConsole + GenerateConsoleCtrlEvent
  from a throwaway pwsh) was delivered (`True`) and the JVM ignored it for 60 s (started with Ctrl+C disabled); `taskkill /PID
  <parent cmd>` (no `/F`) then ended the JVM in under a second with NO shutdown lines in the log and nine DuckDB `.wal` files
  left (replayed on the next open). Back up `spaces\` after the stop, and confirm the Space data with a signed-in look.
- **The built-in browser pane cannot reach WSO2** (`https://localhost:9443` self-signed cert; a cert accepted in the user's own
  Chrome does not carry over) and the Claude-in-Chrome extension may not be connected, so sign-in checks are often unreachable:
  ask the operator to sign in themselves, and say plainly that a visual/live check was NOT done.
- **FAKE-AUTH ("demo sign-in") variant, the way to drive the preview in the built-in browser (2026-10-25):** build with
  `package.ps1 -Edition Preview -DemoAuth -Ui la-app -NoBakeDemo` (NoBake: otherwise it bakes its own la-showcase Space; the
  stage dir is `<worktree>\inspecto-demo\`, the launcher `serve-demo.bat` binds 127.0.0.1 with `-Dauth.mode=demo
  -Dobjects.backend=db -Devents.backend=parquet -Djobs.backend=duckdb`). The swap DELETES `inspecto-oidc.jar`,
  `inspecto-secrets.jar`, `inspecto-geo-country.jar`, `serve.bat/.sh`, the Dockerfile and service files and adds
  `inspecto-demo-auth.jar` + `serve-demo.*` — so `serve-preview-la.bat` (which calls `serve.bat`) stops working until an OIDC
  redeploy; back up first (the old folder is the OIDC restore). Sign-in is a Demo User picker (no password); users live in each
  Space's `config/demo-users.toon` (`admin` exists in all but `la-daily-feed`); the same demo token works on every Space via
  `/spaces/<id>/...`. Start it in the Terminal panel: `& C:\sandbox\inspecto-preview\inspecto-la\serve-demo.bat`.
- **Driving it from an agent:** the built-in pane can be tiny (it was 742x336): call `resize_window` preset `desktop` first. Measure
  with in-page `PerformanceObserver({type:'longtask'})`, `performance.getEntriesByType`, `performance.now()` and in-page `fetch`
  with the SPA's own headers; never emulate a viewport larger than the pane. Server-side: sample `Get-Process` CPU over 60 s,
  `jcmd <pid> Thread.print` works on the bundled runtime (74 Java threads against ~3,550 OS threads: the rest is native/DuckDB).
- **Measured demo-scale baseline (2026-10-25, `f6c168147`):** boot 3 min 35 s to `/health` after a fresh swap (185 s of it before
  the first application log line — unexplained), idle CPU 95% of a core for ~4 min after boot then 6.9%, 1.4 GB working set,
  collectors poll once a minute per source. Largest graph in the Spaces is 767 nodes / 4,000 rows. Details: `docs/superpower/la-performance-plan.md` §0b.

Related: [[oidc-wso2-keycloak]], [[bundle-and-process-traps]].
