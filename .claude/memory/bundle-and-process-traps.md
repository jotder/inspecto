---
name: bundle-and-process-traps
description: "Green tests do not mean the bundle works: stale :8080 dist/jar, jlinked runtime missing modules, npm ci strictness, cmd.exe block expansion in launchers, orphaned JVMs and stale-JVM waiters"
metadata:
  type: reference
---

- **:8080 serves `inspecto-ui/dist/gamma/browser`** (`tools/run-backend.ps1` `-Dui.dir`) — stale until
  `npm run build`; the :4204 dev server is a different surface. Say which port was verified. Likewise
  `mvn clean test` leaves `inspecto-deploy/inspecto.jar` untouched.
- **jlinked runtime:** the bundle JVM has only the modules `tools/jlink-modules.mjs` derives (jdeps over staged
  jars + `tools/jlink-runtime-extra.txt`, checked against `tools/jlink-modules.lock`, fail closed; corrected
  2026-10-19 — the old hand list in `package.ps1` was retired 2026-10-18). A new `com.sun.*`/`jdk.*` import
  reflected or not seen by jdeps can still throw `NoClassDefFoundError` (an Error, past every `catch (Exception)`)
  only in the bundle. Prove on `runtime/bin/java`; booting/seeding the real bundle is the only gate.
- **`npm ci` (package.ps1 step 1b) is stricter than a hand `npm install`**; `inspecto-ui/.npmrc` pins
  `legacy-peer-deps=true` (ngx-toastr peer lag) and `ignore-scripts=true`. After UI dependency changes run the
  packager, then re-run the gate (npm ci wipes node_modules).
- **cmd.exe expands `%VAR%` in a parenthesised block ONCE at parse time** — chained `set "X=%X% ..."` lines keep
  only the last (shipped once as auth-free bundles). One statement per line; never `EnableDelayedExpansion`
  (eats `!` in secrets). Verify a generated launcher by RUNNING it (old and new).
- **`Start-Process java` + `.Kill()` orphans the real JVM** (PATH `java` is a shim): find it by its
  `-Dcontrol.port=` in `Win32_Process.CommandLine` and Stop-Process, or launch the resolved `java.exe`.
  `package.ps1` boot smoke had the same leak.
- **Long-lived unrelated java processes exist**: never wait on "java count == 0"; filter by StartTime, or use the
  agent's completion notification and the log's mtime.

Related: [[preview-la-demo]], [[ui-test-traps]].
