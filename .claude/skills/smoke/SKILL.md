---
name: smoke
description: >
  Live smoke test of the Inspecto control plane. Trigger on "SMOKE" or "smoke test", optionally
  with a focus area ("SMOKE spaces", "SMOKE flows"). Boots ControlApi over a sample config,
  probes /health plus the focus endpoints, optionally runs the e2e suite, ALWAYS stops the
  server, and reports concrete evidence (HTTP codes, key response bodies).
---

# /smoke — control-plane smoke test

Prove the built artifact actually serves, not just that tests pass.

## Steps

1. **Ensure the JAR exists** — `mvn -o clean package -q` if `inspecto/target/inspecto-processor-*.jar`
   is missing/stale (delegate to the `verify-runner` agent if a full build is needed).
   ⚠ The reactor jar is `inspecto-processor-*.jar`, **not** `file-processor-*.jar` (artifactIds renamed
   2026-08-10, `a1da65f5`); `inspecto.jar` exists only inside a `package.ps1` deployment bundle.
2. **Seed the spaces** — `node tools/seed-samples.mjs --all`. Idempotent, writes only under the
   gitignored `spaces/<space>/data/`, takes under a second. 🔴 **Do not skip it on a fresh clone:**
   `.gitignore` ships `data/samples/**` and nothing else, so every inbox and `data/ref/` is absent
   until this runs — the reference-join examples (`join_step`, `orders_enriched_rollup`) then fail
   **422** with a leaked DuckDB internal (*“No files found that match the pattern”*) on both the test
   run and the dry-run, which reads as an engine defect and is not one
   (`REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`). ⚠ It also creates the eight per-Pipeline working dirs;
   `PipelineConfig.prepare()` creates only the status dir. The per-space `seed-inbox.sh`/`.ps1`
   remain for hand use, but they carry a hand-written pipeline list — prefer this one.
3. **Launch** (auth-free core — no token):
   ```powershell
   java --enable-native-access=ALL-UNNAMED -Dspaces.root=spaces -cp inspecto\target\inspecto-processor-*.jar `
        com.gamma.control.ControlApi
   ```
   Run in the background; default port :8080.
4. **Probe** — `GET /health` must be 200 (infra probes stay unversioned), then the focus endpoints
   for this smoke under the `/api/v1` prefix — since API-5 that is the only API surface, and a bare
   business path answers 404, not the resource (e.g. `/api/v1/spaces`, `/api/v1/spaces/demo/jobs`,
   `/api/v1/spaces/demo/views`). Use `curl -s` and capture status + body. Success bodies are
   envelope-wrapped (`{data, metadata, links, …}`), so read the resource under `.data`.
5. **Optional e2e** — `E2E_BASE_URL=http://localhost:8080 npm run test:ci -- --include src/e2e/**`
   in `inspecto-ui/` when the focus is UI-visible.
6. **Always stop the server** — even on failure.
7. **Report evidence** — endpoint → status → one-line body summary. "Smoke passed" without the
   probe table doesn't count.
