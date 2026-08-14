# Path containment: one primitive, then load-time enforcement

**Status:** IN FLIGHT (opened 2026-08-14). Supersedes the scoping in
[`../BACKLOG.md`](../BACKLOG.md) "Config-declared paths resolve unjailed against the server CWD",
whose inventory (2026-07-31) is **wrong in both directions** — see §1.
**Trigger:** operator — "take on the unjailed config paths", the last buildable BACKLOG §6 row.

**Operator decisions (2026-08-14), all three taken before any code was written:**

| # | Decision | Chosen |
|---|---|---|
| D1 | Load-time behaviour on an escaping ref | **Enforce — throw.** Not warn, not flag-gated. |
| D2 | Which root enforcement measures against | **The spaces/server root** (`-Dspaces.root`, default CWD) — *not* `configDir`. |
| D3 | Width of the pass | **Unify the primitives first, then apply** — the BACKLOG row's own prescription. |

⚠ **D2 exists because D1's first framing was wrong.** See §2 — it is the single most important fact
in this plan and the reason the obvious implementation is a product-breaking one.

---

## 1. What grounding changed

The BACKLOG row was inventoried 2026-07-31 and re-verified 2026-08-14. It is **smaller than recorded
in three places and bigger in two**. Do not plan against the row; plan against this section.

**Already done — not work:**
- **Group (i), the route/server layer, is fully jailed.** Every site the row names (`ConfigRoutes`,
  `RunRoutes`, `EnrichmentRoutes`) is wrapped in `WriteGates.jail(writeRoot, …)`
  (`ConfigRoutes.java:158-162,297-299,403-405,449-451,715-727`, `RunRoutes.java:196-201,245-247`,
  `EnrichmentRoutes.java:83-85`).
- **`processing.grammar` is already routed** through `resolveGrammarRef` → `resolveSchemaRef`
  (`PipelineConfigParser.java:298-303`). The row's `:857-861` citation now points at
  `mergeSiblingMapping`'s Javadoc — pure drift.
- **Group (ii) needs no pom change.** `inspecto-engine` already depends on `inspecto-config`, and
  `writeRoot`/`dataDir` are already locals at every unguarded site.

**Wrong as recorded:**
- ⛔ **"~80 call sites" is not a real number** and must not be planned against. The raw universe of
  `Path.of`/`Paths.get`/`new File` in main sources is **222**; isolating the config-derived subset needs
  per-line judgment. The row's own "one pass or not at all" rests on this figure. The *actual* unjailed
  surface is ~7 config fields across 4 job-task files plus 2 config-layer sites.
- ⛔ **"Routed through `resolveSchemaRef`" ≠ contained.** The row concludes the schema reads are safe
  and the work is therefore "smaller than recorded". Read the function
  (`PipelineConfigParser.java:812-826`): it *prefers* the contained form and **silently falls back to
  the unjailed as-authored path** when the candidate escapes or does not exist, and returns immediately
  for an absolute ref. It is a portability preference, not a boundary.

**Bigger than recorded — five containment implementations, not two, and they disagree:**

| # | Where | Enforcing? | Absolutises? | Symlink re-check? |
|---|---|---|---|---|
| 1 | `ConfigSafetyValidator.checkPathValue`/`underAnyRoot` (`inspecto-config`) | advisory `Finding`s | ✅ | ✅ `toRealPath()` |
| 2 | `LocalConnectionWorkbench.jail` (`inspecto-acquire:121`) | ✅ throws → **403** | via `resolve` | ❌ |
| 3 | `WriteGates.jail` (`inspecto/control:34`) | ✅ throws | ❌ | ❌ |
| 4 | `PipelineConfigParser.resolveSchemaRef` (`inspecto-etl:812`) | ❌ preference | ✅ | ❌ |
| 5 | `validateDirs` (`:889`) / `PipelineJobRunner.requireTopLevelSinks` (`:380`) | ✅ throws | inverse-sense | ❌ |

⚠ **The weakest one guards the HTTP write surface.** `WriteGates.jail` is `normalize()` +
`startsWith()` with no absolutisation and no symlink re-check — while `ConfigSafetyValidator`, which
only produces advisory findings, is the one that defends against symlink escape. Whether that is a
live hole or is covered in depth by the validator running on the same routes is **§4 slice 2's first
question — do not assert either way until it is probed.**

⚠ `PipelineJobRunner.java:377` carries the comment *"Job configs bypass `ConfigSafetyValidator`"* — the
bypass is deliberate and documented, so removing it is a behaviour change, not a bug fix.

---

## 2. ⚠ Why D2 exists: the obvious implementation breaks every space

**Every config shipped in this repo resolves its refs from the repo/server root, not from its own
directory.** This was verified, not assumed:

```
spaces/default/config/events/events_pipeline.toon
  schema_file: spaces/default/config/events/events_schema.toon      # ← relative to the SERVER ROOT
```

With `configDir = spaces/default/config/events`, `resolveSchemaRef`'s contained candidate is
`spaces/default/config/events/spaces/default/config/events/events_schema.toon`, which **does not
exist** — so every one of these takes the fallback branch and resolves from the CWD. Confirmed for all
seven tracked refs, including `spaces/_templates/orders-starter`, **the template shipped to new users**.

**Therefore jailing against `configDir` would stop every space from booting.** The "legacy
working-directory branch" the BACKLOG row describes as a legacy corner to be closed is in fact the
*only* form in use.

Jailing against the **spaces/server root** gets the same security outcome with zero breakage:

| Ref | vs `configDir` | vs spaces root |
|---|---|---|
| `spaces/default/config/events/events_schema.toon` (every shipped config) | ✗ breaks | ✅ passes |
| `../../../etc/passwd` | ✅ throws | ✅ throws |
| `/etc/passwd` (absolute) | ✅ throws | ✅ throws |

It is also the root the route layer already jails against, so the two surfaces agree — the same
"picker and runner must not disagree" argument that drove 5c's jail extraction.

---

## 3. Design

**One primitive, in `inspecto-config`.** New `com.gamma.config.safety.PathJail`, carrying the
**strongest** semantics of the five (absolutise → normalize → `startsWith` → `toRealPath()` symlink
re-check against the nearest existing ancestor):

```java
public final class PathJail {
    /** Enforcing: returns the contained absolute path, or throws. */
    public static Path require(Path root, String value, String field);
    /** Predicate, for advisory callers that must not throw. */
    public static boolean contains(Path root, Path candidate);
    public static final class Escape extends RuntimeException { … }
}
```

`require` is defined in terms of `contains`, so the advisory and enforcing surfaces cannot drift —
that shared-truth property is the whole point of the unification and must be pinned by a test.

**Placement is settled by the module graph, not preference.** `inspecto-config` depends only on
`inspecto-api` + jtoon + jackson. `inspecto-acquire`, `inspecto-engine` and `inspecto/` already depend
on it; **`inspecto-etl` is the only module missing the edge**, and adding it creates no cycle and pulls
in **no new third-party** (etl already has jtoon and jackson). The row's "either add the dependency
deliberately or move the primitive down" is a false dilemma — moving it down means pushing security
code into either the annotations leaf (`inspecto-api`) or the heavyweight `inspecto-util`.

**How the config layer learns the root.** ⛔ **This paragraph was wrong; S3 corrected it.** It read:
*"`-Dspaces.root`, defaulting to CWD — read once, the same seam `MaterializeTask` already uses for
`-Ddata.dir`."* Both halves are false, and neither survives grounding:

- `spaces.root` is read in **exactly one place** — `ControlApi.java:343`, for space *discovery*. That
  is in module `inspecto/`, which sits **above** `inspecto-engine`, so no job task can call it. It is
  also unset in single-tenant mode, in the job runner and in every test, and has no operator override.
- **`-Ddata.dir` is never read anywhere.** It appears only inside error-message strings
  (`MaterializeTask:59`, `ReconRunJob:67`). There was no seam to copy.

**The real seam already existed and is better:** `SafetyPolicy.defaultPolicy()` reads
**`-Dassist.safety.roots`** (a `;`-separated list), falling back to the working directory
(`SafetyPolicy.java:67-79`). It is plural, operator-overridable, and it is *the list
`ConfigSafetyValidator` already enforces at the 422 write gate* — so containment at load and refusal
at authoring measure against the same roots. `PathJail.allowedRoots()` returns it and
`PathJail.requireUnderAny` enforces against it, still delegating each root's verdict to `contains`.
⛔ Do not reintroduce a second root source: a jail whose root disagrees with the gate's is a jail with
a documented bypass.

---

## 4. Slices

- **S1 · the primitive. ✅ SHIPPED 2026-08-14** — see the as-built below.
- **S2 · migrate the five onto it.** ⚠ **They differ on TWO axes and only one may be unified.**
  *Resolution* is legitimately per-caller — `LocalConnectionWorkbench` resolves connector paths
  **against the root**, the validator resolves config values **against the CWD**, and collapsing those
  would break one of them. *Containment* is the part that must have one implementation. So each caller
  keeps its own resolve and delegates only the **verdict** to `PathJail.contains`. ⚠ Do **not** change `LocalConnectionWorkbench.PathEscape`'s type
  — it is what `ConnectionRoutes` maps to **403**, and 5c pinned that with a falsification-probed
  security test. Delegate to `PathJail` and translate the exception; keep the 403 contract and its test
  untouched. **Migrating `WriteGates.jail` STRENGTHENS it** (adds absolutisation + symlink re-check), so
  it can newly refuse writes that previously passed — measure that blast radius before landing, and
  treat "is the missing symlink check a live hole?" as a probe, not an assertion.
### S2 SHIPPED 2026-08-14 — three migrated, two deliberately NOT

Migrated onto `PathJail.contains` — each keeping its own **resolution** and its own exception type,
sharing only the **verdict**:

| Was | Now | What it gained |
|---|---|---|
| `ConfigSafetyValidator.underAnyRoot` | delegates | nothing (it was the reference); lost its open-coded symlink walk |
| `LocalConnectionWorkbench.jail` | delegates, still `PathEscape` → 403 | the symlink re-check it never had |
| `WriteGates.jail` | delegates, still `ApiException(403)` | absolutisation **and** the symlink re-check |

⚠ **`LocalConnectionWorkbench.PathEscape` was deliberately left as the thrown type** — `ConnectionRoutes`
maps it to 403 and 5c pinned that with a falsification-probed security test. Changing it to
`PathJail.Escape` would have been "tidier" and would have silently broken a security contract.

⚠ **The validator's two-stage message was rebuilt, not dropped.** Delegating collapsed "outside the
allowed roots" and "escapes via a symlink" into one finding; a path that *looks* contained but is not
has escaped through a link, and telling the operator it is "outside the roots" sends them hunting the
wrong thing. The distinction is now derived from a structural pre-check.

**Two were NOT migrated, on purpose:**
- `PipelineConfigParser.validateDirs` asserts a dir is **not** under the poll dir — an
  anti-containment *business* rule (don't write output into the inbox), not a boundary. It could reuse
  the predicate, but it sits in `inspecto-etl`, which still lacks the `inspecto-config` edge → **S4**.
- `PipelineJobRunner.requireTopLevelSinks` is a **depth** rule ("a direct child of the data root"),
  not containment. It exists so a nested store cannot double-count in recursive dataset reads — a
  question about *literal* nesting, so resolving real paths would change the answer for the wrong
  reason. ⛔ Do not "finish the job" by folding it in.

- **S3 · apply at group (ii), the job tasks. ✅ SHIPPED 2026-08-14** — see the as-built below.
  ⚠ Removing `PipelineJobRunner`'s documented validator bypass is a separate decision — do
  not fold it in silently. *(Still not folded in.)*

### S3 SHIPPED 2026-08-14 — and it was not "mechanical"

**The surface was 9 fields / 6 call sites / 3 files**, not "~7 across 4 files":

| Site | Fields |
|---|---|
| `BackupTask.backup` | `dir`, `backup_dir` |
| `BackupTask.verify` | `backup_dir`, `archive` |
| `BackupTask.restore` | `archive`, `target_dir` |
| `MaintenanceJob.storageReport` | `dir` |
| `MaintenanceJob.cleanup` | `dir`, `archive_dir` |
| `ReportJob.deliver` | `out_dir` |

⚠ **`MaterializeTask` is NOT in the list and needs no change.** Its `target` is already contained by a
one-segment `SAFE_TARGET` regex plus an explicit `..` check, resolved under `dataRoot`
(`MaterializeTask:64-77`) — stronger than a jail. The plan named it as one of the four files.

⚠ **"`writeRoot`/`dataDir` already in scope" was true at one site of six.** `verify`, `restore`,
`storageReport` and `cleanup` take no root parameter at all. That is *why* the roots come from
`PathJail.allowedRoots()` rather than a threaded argument — the alternative was changing six
signatures.

⚠ **`archive` on `backup_verify` is jailed against `backup_dir`, not against the allowed roots.** It
names a file *inside* the backup dir, so `archive: ../outside.zip` reads back out of the box while
`backup_dir` itself stays perfectly legal. Two different roots for two different fields in the same
method — pinned by `verifyRefusesAnArchiveThatTraversesOutOfTheBackupDir`.

⚠ **`storage_report` is read-only and is jailed anyway.** The walk logs the largest files by full
path, so an unjailed `dir` is directory enumeration of anything the server can read.

**The blast radius the plan did not price (operator decision, 2026-08-14: enforce).** Job path fields
are operator-supplied *output destinations*, unlike the in-repo config refs D2 was decided for. With
the roots defaulting to the CWD, an absolute destination outside the server root now **fails the
job** — a normal deployment wanting `backup_dir: /mnt/backups` must declare it via
`-Dassist.safety.roots`. The operator chose enforcement with declared roots over a
context-dependent allow-list. Documented in `docs/ops/backup-restore-runbook.md`.

⚠ **~40 existing test call sites pass absolute `@TempDir` paths, which are outside the CWD.** Rather
than edit all of them, surefire declares the execution root and the temp dir as allowed roots in the
parent `pom.xml`. ⛔ That is a test sandbox, **not** a relaxation — `JobPathContainmentTest` narrows
the roots to a single temp dir before every assertion, because under the permissive sandbox an
"escape" would be contained by the temp root and **every test would pass while testing nothing**.
For the same reason its escape fixtures are real sibling directories reached via `..`, never a literal
like `/etc/passwd`: on Windows that normalises onto the current drive and can land *inside* the root.
`escapesTo` asserts the fixture really is an escape before the test relies on it.

**The falsification probe took three attempts, and the first two were wrong in ways worth keeping.**
Final result: with the shared `contains` short-circuited, **6 of 7 job tests, 5 of 14 `PathJailTest`
and 6 of 23 `ConfigSafetyValidatorTest` went red** — one chokepoint, both surfaces, falsified rather
than asserted. Getting there:

1. ⛔ **A `-DargLine` kill switch is not a probe in this repo.** Probe #1 disabled containment behind
   `-Dpathjail.probe.disable` passed via `-DargLine`; the property never reached the forked test JVM
   and **all 7 tests passed with containment "off"**. That looks exactly like a suite of vacuous
   tests and would have been read as one. **Probe by editing the source** (`if (true) return …`), where
   there is no plumbing to be wrong about.
2. ⛔ **A probe scoped to one method can miss a caller that uses a different one.** Probe #2 disabled
   only `requireUnderAny`, and `verifyRefusesAnArchiveThatTraversesOutOfTheBackupDir` stayed green —
   not because it was vacuous but because `verify`'s `archive` field jails through **`require`**
   against `backup_dir`. Probing the shared `contains` is what covered both.
3. ⚠ **The reactor stops at the first failing module, so a probe spanning modules needs
   `--fail-at-end`.** Without it `inspecto-config` failed, `inspecto-engine` was SKIPPED, and the run
   reported the engine tests as neither passed nor failed — an easy result to misread as a pass.

⚠ **The `escapesTo` guard earned its place:** 5 of the 6 failures landed on *it* ("fixture is not an
escape"), not on the `assertThrows`. The guard detects a vacuous fixture before the assertion can
pass for the wrong reason — ⛔ do not "simplify" it into a plain string literal.
- **S4 · apply at group (iii), the config layer.** `resolveSchemaRef` enforces against the spaces root
  per D1/D2; `Asn1RecordIngester:96` (`ingester_config.grammar`) routed through it; and the genuinely
  rootless `PipelineConfig.fromMap` in-memory draft takes a root **supplied by the caller** (the route
  layer, where a space root is already in scope).
- **S5 · close the 422 gate's blind spot.** `ConfigSafetyValidator`'s field list omits `schema_file`
  and `grammar` (verified: zero matches in the file). Add them so the write gate refuses at authoring
  what S4 now refuses at load.

**Sizing: multi-shift.** S1+S2 is a plausible shift on its own.

### S1 SHIPPED 2026-08-14 — as-built, and the test that was never running

`com.gamma.config.safety.PathJail` (`inspecto-config`): `require(root, value, field)` enforcing,
`contains(root, candidate)` predicate, `PathJail.Escape` unchecked. 13 adversarial tests, **0 skipped**.

**`require` delegates its verdict to `contains`** and re-derives only enough to word the failure. The
first draft had them as parallel implementations — which would have made this class a *sixth* spelling
of the boundary while claiming to be the one. `requireAndContainsAgree` pins the shared truth.

⚠ **Relative values resolve against the CWD, not against the root.** Resolving against the root would
double the prefix on every shipped config (§2) — the single most important behavioural detail here.

⚠ **The falsification probe passed correctly:** with the containment and symlink checks removed,
**6 of 13 tests went red**, including the symlink case. The UNC and blank cases stayed green — correct,
they are guarded before containment is consulted and are separate defences.

⚠ **S1 shipped with a false-negative that S2 found: a root that is ITSELF a link.** `contains` compared
the candidate's *real* path against a *non-real* base, so when the root is a symlink (`/tmp` →
`/private/tmp`, or any linked deploy dir) every legitimate path under it fails the check. Harmless while
nothing called it; it would have surfaced as unexplained 403s the moment S2 wired three enforcing
callers up. Fixed by comparing **real-to-real**, pinned by a linked-root test.

⚠ **The find that matters most is in the test suite, not the source.**
`ConfigSafetyValidatorTest.symlinkEscapeIsRejected` — the *only* test covering the real-path re-check
on the strongest of the five implementations — called `assumeTrue(false)` whenever the OS refused a
symlink. Windows refuses by default, so **it had been silently skipping in every recorded green
baseline**, and the module reported "1 skipped" where a reader would assume a deliberate `@Disabled`.
A **directory junction** needs no elevation and `toRealPath()` resolves it identically, so
`TestLinks.linkDirectory` falls back to `mklink /J`. The module now runs **90 tests, 0 skipped**, and
that pre-existing symlink defence is verified for the first time. ⛔ Do not "simplify"
`TestLinks` back to a plain `createSymbolicLink` — it will silently stop testing anything.

## 5. Verification

- Reactor: `mvn -o clean test -Pedition-enterprise` — 25/25 modules. ⚠ Adding a pom edge to
  `inspecto-etl` touches the dependency graph, so the enterprise profile is mandatory, not optional.
- **The breakage probe that matters:** boot every tracked space and confirm each still loads. A green
  unit suite does **not** cover §2 — the shipped configs are data, not tests.
- UI unaffected (no surface change) unless S5 changes a 422 body.

## Links

- Superseded scoping: [`../BACKLOG.md`](../BACKLOG.md) §6 "Config-declared paths resolve unjailed"
- Validator as-built: [`../okf/backend/config/config-safety.md`](../okf/backend/config/config-safety.md)
- The 403 jail contract this must not disturb: [`../okf/backend/engine/pipeline-test-run.md`](../okf/backend/engine/pipeline-test-run.md)
