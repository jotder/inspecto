---
name: guard-scope-is-a-silent-exemption
description: "Audit a guard's SCOPE (which files it ever opens) separately from its RULES — too narrow misses the target, too wide lets scratch files break it; and config written but never read is the same blind spot"
metadata:
  type: feedback
---

`tools/check-secrets.mjs` once had well-argued rules but an `EXTS` list without `.md`, so credentials pasted into
runbooks/plans were seen by nothing (now fixed; `.md` is in `EXTS`). Same day the opposite: doc guards walked the
filesystem and a peer's gitignored scratch file refused pushes.

**How to apply:**
- Ask two questions of every guard: what shapes it matches, and what files it ever opens (`EXTS`, `SKIP_DIRS`,
  globs, module list). An exemption expressed as a missing array entry is invisible.
- Measure noise before arguing it: copy the guard, widen it, run on the corpus.
- Falsify on the NEW surface (plant a fake secret in a `.md`), both directions — see [[falsify-negative-tests]].
- A test that carries its own copy of the list it checks cannot detect drift; a comment asking two lists to stay in
  sync is not a mechanism — write a two-direction parity test and grep for further copies.
- **Config written but never read:** before adding a config key or surface, find the READ path; a key can be
  authored, validated and saved yet read by nothing.
- A guard wired only into CI cannot PREVENT anything — it runs after the push.

Related: [[doc-guard-traps]], [[new-route-checklist]].
