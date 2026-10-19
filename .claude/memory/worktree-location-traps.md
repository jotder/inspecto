---
name: worktree-location-traps
description: "Put gate/lane worktrees in a SIBLING of the checkout (C:\sandbox\<name>): %TEMP% flips safety-root tests, .claude/worktrees made scanners read master, and node_modules junctions get the shared tree wiped"
metadata:
  type: feedback
---

- **Not under `%TEMP%`:** tests get `assist.safety.roots=${session.executionRootDirectory};${java.io.tmpdir}`
  (root pom), so a relative `../../etc/evil` from `%TEMP%\wt\...` lands INSIDE an allowed root — a false red that
  also skips later modules. Pin such properties with absolute paths outside every root.
- **Not under `.claude/worktrees/` for scanner tests:** `ReactorModules.root()` used to climb to the outer checkout,
  giving false red/green on lane code. Fixed (stops at the nearest `.git`, verified in
  `inspecto/src/test/java/com/gamma/control/ReactorModules.java`), but hand-rolled copies in other scanner tests
  may differ — a sibling worktree remains the safe default for gates.
- **Never junction `inspecto-ui/node_modules` into the main checkout:** worktree cleanup follows the junction and
  empties the shared one. Each worktree runs `npm ci --offline` itself. Before removing a worktree check links with
  `cmd /c dir /aL <wt>\inspecto-ui` (fsutil missed them); count main `node_modules` before/after and restore with
  `npm ci --offline`.
- Deny-list entries for build output (`inspecto-deploy` etc.) are load-bearing only on a BUILT checkout; a fresh
  worktree looks green either way.

Related: [[shared-tree-peer-work]], [[maven-verdict-traps]].
