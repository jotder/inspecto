---
name: subagent-claims-are-hypotheses
description: "A subagent's MEASUREMENTS are evidence; its verdicts, attributions, negatives ('X does not exist') and inferred third-party defaults are hypotheses — check with one command before relaying"
metadata:
  type: feedback
---

Repeated, costly pattern: I relayed an agent's claim to the operator and it was wrong.
- Self-reported "Verdict: PASS" (wrong edition profile, wrong worktree, read off the log) — re-derive with
  `tools/check-reactor-verdict.mjs` on the tree you will commit.
- "Failed at the 4th assertion" — it was the 1st; reading the quoted `Test.java:<line>` settles it.
- "That file was already dirty before this session" — one `git diff` disproved it.
- "`PlatformBuilder` doesn't exist anywhere" — it did, in another package.
- "DuckDB defaults to UTC" (inferred, not run) — false here; the proposed fix would have introduced a skew.
- Subtracting two tools' totals compares two different populations.
- **A performance "finding" from reading code is a hypothesis until MEASURED (2026-10-25: 6 of 8 server claims were wrong
  or already done):** "no gzip anywhere" (it lived in `spi/inspecto-http-spi`, outside the searched modules), "the edge index
  is off by default" (on since 2026-10-10; a stale comment said otherwise), "a second COUNT scan runs" (only with a
  four-eyes threshold), "no top-N" (DuckDB already plans `TOP_N`), "the Grow CTE rescans the relation" (DuckDB materialises it:
  one read), "the log fold is O(ops) and slow" (13-35 ms at 1,000 ops). Gate every perf lane on a measured BEFORE table and a
  go/no-go rule written in the prompt; two of the lanes correctly stopped with no code.

**How to apply:** before repeating "it failed at X / was already dirty / does not exist / the gap is N", run
`sed -n '<line>p'`, `git diff <path>`, `git grep`, or a direct probe. A batch of zero hits is suspicion, not
findings: first make a probe KNOWN to exist return non-zero through the same code path. Ask verify agents for the
exit code, the log mtime, and any unhandled error quoted with its spec file.

Related: [[maven-verdict-traps]], [[claims-are-hypotheses]].
