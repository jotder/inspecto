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

Related: [[oidc-wso2-keycloak]], [[bundle-and-process-traps]].
