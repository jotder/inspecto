---
name: preview-la-demo
description: "The demo is ONLY the unzipped Preview la-app bundle at C:\sandbox\inspecto-preview\inspecto-la on :8080 (WSO2 OIDC); update in place, no zips/linux bundles unless asked, and keep the runtime cacerts on redeploy"
metadata:
  type: project
---

Preview `la-app` bundle at `C:\sandbox\inspecto-preview\inspecto-la` (Spaces la-showcase / la-showcase-off /
la-daily-feed), started with `serve-preview-la.bat`, WSO2 at https://localhost:9443 (app `inspecto_preview_la`,
setup `scripts/wso2-preview-la-setup.mjs` — git-excluded, local only). Operator 2026-10-16: deploy every fix there;
zip/linux builds are wasted time — only when asked.

- **UI-only change:** build `inspecto-ui/dist/la-app` and robocopy `/MIR` it into the bundle's `ui\`.
- **Full redeploy:** worktree at origin/master, `npm ci --offline`, `package.ps1 -Edition Preview -Ui la-app`
  (~15 min); stop :8080; back up to `inspecto-preview\backup\predeploy-<stamp>`; robocopy over with `/XD spaces`;
  refresh only `spaces\_templates`; keep `serve-preview-la.bat`, the WSO2 setup script, `runtime-natives`, all Spaces.
- **Restore `runtime\lib\security\cacerts`** from the backup (or exclude it): the fresh jlinked runtime lacks the
  imported WSO2 cert and boot dies with `SSLHandshakeException` on OIDC discovery.

Related: [[oidc-wso2-keycloak]], [[bundle-and-process-traps]].
