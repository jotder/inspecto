---
name: operator-standing-preferences
description: "Breaking changes are free on every surface (no compat shims, no hedging); when the operator is away decide rationally and record the call; hold only hard-to-reverse/security/outward-facing/reversal decisions"
metadata:
  type: feedback
---

**Breaking changes are free** (operator 2026-09-20: "don't worry of breaking change on any surface"). Nothing after
3.x is in production; `@PublicApi` marks intent, not exposure.
- Do NOT add delegating constructors, deprecated aliases, dual-read config shims, or "accept old and new name".
  Change the thing and fix its call sites.
- Do NOT raise "breaking change" as a blocker, risk bullet or question option.
- DO document the change where a reader would be surprised (release notes, as-built note, OpenAPI description).

**When the operator is away** (2026-09-26: "hold the decision unless really important, keep logical and
rational"): pick the reasoned option, record it as a session decision (not "(operator)") in the doc/commit, and
list the calls in the report so they can be overturned. Hold only when hard to reverse, security-relevant,
outward-facing, or reversing a recorded operator decision.

Related: [[push-and-branch-policy]].
