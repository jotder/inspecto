# Space promotion — a whole Space applied to an existing Space as a previewed change set (SE-6b)

**State:** built 2026-11-13 (SE-6b of the Space evolution plan, §4.6, under D-SE7 and its 2026-11-13 detail). Two
routes, no UI, no file format change. Kill switch `-Dspace.promotion=off`.

## 1. What it does

Promotion takes the configuration of one Space (say, UAT) and applies it to another Space that already exists
(say, production) — on the same server or another one. It first shows the **change set**: for every config file it
would touch, whether the file is added, changed, removed or the same. Only then is it applied, quoting that preview.
Before this, a whole-Space bundle could only seed a **new** Space (`POST /spaces/import`); `POST /import` refuses
it into an existing Space (`DataSourceRoutes.WHOLE_SPACE_IMPORT_REFUSED`), and still does.

Code: `SpacePromotion` (the computation, pure) and `SpacePromotionRoutes` (gates, hold, write), both in
`inspecto/src/main/java/com/gamma/control/`.

## 2. Decisions

| Question the plan left open | Decision | Why |
|---|---|---|
| What carries the source Space | The existing whole-Space export, `GET /spaces/{id}/export` (`BundleExporter.exportSpace`), unchanged. | It is already how a Space travels between servers. It carries every file under `config/` (less the signed operational stores) and `space.toon`; connections are exported with literal secrets omitted. |
| How the bundle reaches the route | Base64 inside a JSON body, not a raw zip. | An approval replays the recorded request body as text (`PendingChanges.hold` stores `request.body` as a UTF-8 string). Raw zip bytes would not survive that; base64 does. Body cap 64 MB (413 above). |
| Where the per-environment parameters live | In the request body, `parameters: {NAME: value}`. | No new file in any Space: nothing for `ReservedConfigPaths` to reserve, nothing for the anatomy guard (`tools/check-space-anatomy.mjs`) to list, nothing a loader could read. The Pending Change records the values with the request, so the approver sees exactly what will be bound. Keeping the values per environment is the caller's job (beside the bundle in their pipeline). |
| Placeholder syntax | `${NAME}`, `NAME` upper snake case (`[A-Z][A-Z0-9_]{0,63}`), substituted as text, like `${SPACE}` in `SpaceManager.createFromTemplate`. `${SPACE}` is always the target Space's id and cannot be set. | Consistent with the one placeholder that exists. Lower-case `${dir}` (Job template parameters) and scoped secret references (`${ENV:X}`) never match. |
| Secret references are not placeholders | In a connection file, a token that is the whole value of a secret field (`password`, a secret `options` key, tunnel or proxy password) is a run-time secret reference (`SecretResolver`) and is left alone. | A bare `${DB_PASS}` is a valid secret reference; treating it as a missing parameter would refuse every Space that uses one. |
| Parameter values | A string of at most 1024 characters with no quote, backslash or control character (else 400). A `.toon` file that parsed before binding and does not after (a value with a comma in an array row) refuses the change set: 422, `brokenByParameters`. | Values are substituted into TOON text; a newline or quote could inject a key, a comma could split an array row. A placeholder in key position lets its value name the key; the approver sees the values. |
| A placeholder with no value | 422, `missingParameters` lists every name, nothing written. Only promoted files count. | As the plan states. |
| What can be promoted | Exactly what an import may write: `ImportPaths.refusal` (segment rules, shape allowlist, `ReservedConfigPaths`, real path), less the kinds written only through their own route (`ImportCapabilityGuard.DEDICATED_ONLY`, `GOVERNANCE_ONLY`). Everything else the bundle carries is listed under `excluded` with its reason. | One judge for every door that writes raw config. So server-written state, identity and settings documents (`roles.toon`, `approval.toon`, `branding.toon`, …), `.history/`, `audit/`, `pending-changes/` and the suffix-scanned ops configs (`*_workflow.toon`, `*_caserule.toon`, `*_tagrule.toon`, `*_tag.toon`, `*_meta.toon`, `*_rca.toon`, `*_job_template.toon`) are never compared or written. `.inspecto/` and `config.secrets/` sit beside `config/` and are not in the bundle at all. |
| Equality | `.toon`: decoded content, less the stamps the server writes (`createdAt`, `updatedAt`, `lastResult`, `lastSimulation`; a Job's `createdBy` / `updatedBy` / `updatedByRoles`; a Decision Rule's `createdBy` / `updatedBy` / `makers` / `restoredMakers`). Anything else: bytes. | Formatting and server stamps are not a change. |
| Connections and secrets | A target connection is compared in its exported form (`BundleExporter.exportableBytes`), so a secret is never a difference. A target connection holding a **literal** secret is never changed or removed (excluded: "change it through /connections"). A bundle connection carrying a literal secret (a hand-made bundle; the export never does) is never written (excluded). | The exported source has no literal secret to write, so writing over such a connection would delete the secret; and a promotion never writes a secret, nor records one in a Pending Change. |
| Removals | Files the target has and the source lacks are listed as `remove`; apply removes them only with `removals: true`. Default: listed in `removalsSkipped`, kept. | A promotion should not delete by surprise; the preview shows what an opt-in would remove. |
| Tests that travel with a Space (`spaces/<id>/tests/`, SE-6a) | Not promoted. | The whole-Space export carries only `config/` and `space.toon`; carrying `tests/` would change the export format. Copy the folder alongside. |
| Approval (D-SE7) | With no approval policy, an administrator's apply writes at once. Under a policy that holds **any** kind, the whole set is **one** Pending Change (kind `space-promotion`, name = the Space id), applied when it is approved. | The operator's 2026-11-13 detail. |
| Who approves a held promotion | `ApprovalPolicy.forWholeSet()`: four eyes always; the approver capability the policy names when every required rule names the same one, else `canAdminister`. | A promotion spans kinds; it must never be approved by less than any kind it carries asks for. |
| The gate | Both routes `canAdminister`. | It rewrites a Space's configuration; the preview reads the uploaded bundle against every config of the Space. |
| How the write lands | Every file through the import journal (`ImportJournal`: atomic temp + move, prior bytes kept, content-refusal audit), removals last; any failure puts back the removed files and rolls the journal back. A written Pipeline gets its history snapshot (`PipelineHistory.record`), as every Pipeline save. Connections written are registered, removed Pipelines and connections unregistered, and the registered configs re-read (`refreshConfigs`). A removed Job stops at once, as after `DELETE /jobs/{name}`: dropped from the running JobService, its publication and attachment approvals forgotten. | The same writer every import uses. A new Pipeline file goes live as after `POST /config/write`; an added or changed Job is read at the next restart, as after `POST /import`. |
| Kill switch | `-Dspace.promotion=off` answers both routes 503. Default on. | Promotion is an explicit operator act with its own preview; nothing at boot or load reads it (`SpaceCorpusEquivalenceTest` registers the switch). |

## 3. Routes

Both are reached as `/spaces/{id}/promotion/…` (the Space seam binds the target) and also unprefixed for the
default Space.

### `POST /promotion/preview`

Body `{bundle, parameters?}`. Writes nothing. Answers 200:

| Field | Meaning |
|---|---|
| `space`, `sourceSpace` | the target, and the `source_space` the bundle's manifest names |
| `changeSetHash` | SHA-256 over every entry (path, action, both contents) — what apply must quote |
| `counts` | `{add, change, remove, same}` |
| `changes[]` | `{path, kind, action}` for every promotable file, sorted by path |
| `excluded[]` | `{path, reason}` for every bundle file (and every literal-secret connection) not promoted |
| `parametersUsed` | the parameter names the promoted files bound (`SPACE` included when used) |
| `approval` | `direct` (no policy) or `pending-change` (apply will be held) |

### `POST /promotion/apply`

Body `{bundle, parameters?, previewHash, removals?}`. Re-computes the change set from the bundle and the target as
they are now; any difference from the preview is 409. Answers 202 with the Pending Change under a policy, else 200
`{space, applied, changeSetHash, written, removed, removalsSkipped}`. A change set with nothing to add, change or
remove answers 200 with empty lists and holds nothing.

Approving the Pending Change (`POST /pending-changes/{pc}/approve`) replays this request as its author; the replay
re-checks the hash (409 if the target changed while it waited), and the hold lets it through only if the files it
writes are the ones the approver read (the Pending Change's `proposed` is each file's action and full text, its
`current` what those files held).

## 4. Gates

Both routes, in order: kill switch or no config root 503 · body 400 (413 over 64 MB) · not a whole-Space bundle
(`kind` other than `space`), a placeholder without a value, or a file the bound values make unreadable 422. Apply then: a promoted Pipeline whose paths escape
the allowed roots (`ConfigSafetyValidator`) 422 · a carried kind the caller may not write on its own route
(`ImportCapabilityGuard.checkFiles`, e.g. a connection needs `canOnboardConnections`; an attaching report Job is
403 and a `publish.postgres` Job 409, as for every import, because each needs its own four-eyes approval) · the
Decision Rule gate
(`DecisionRuleGuard.guardImport`) · Job author stamps (`JobAuthority.stampFiles`) · stale 409 · a promotion already
pending for the Space 409 · hold 202 · write 200. An unknown Space is 404 at the seam.

Real-HTTP proof: `ControlApiSpacePromotionTest` (armed authenticator: 401, 403, 400, 404, 409, 422, 503, 200, 202 and
the approval that lands it).

## 5. Deliberately not covered

- **No UI.**
- **A registry component's `.history/` copy** (MET-5) is not taken for a promoted component; the import journal
  writes it, as `POST /import` does. A Pipeline's history snapshot is taken.
- **Live registration of a new Pipeline or Job**: as after `/config/write` and `POST /import`, a newly added Pipeline
  file, and an added or changed Job, is live after the next restart (a changed Job runs its previous version until
  then); changed registered Pipelines are re-read at once, and a removed Job stops at once.
- **Referential integrity of the promoted set** (a schema reference that will not resolve): not checked beyond
  containment; the loader reports it as for any hand edit.
- **Lock enforcement** (SE-1b): the promotion's writes fall inside the request's write window, so the Space lock does
  not report them as outside edits; nothing else ties the two.
- **Parameter files kept by the server**: the values travel in each request.
- **Known gaps, open on the board as `SPACE-PROMOTION-PARAM-TYPES-1`**: a parameter value can change the type of the
  value it fills while the file still parses (`k: v` makes a list item an object, `[2]: a,b` makes text an array, an
  empty value makes `{}`); a held apply keeps the raw bundle in its Pending Change's recorded request body.

## References

- Plan: [Space evolution plan](plan-space-evolution.md) §2, §4.6, §6 D-SE7
- [Space tests (SE-6a)](spec-space-tests.md) · [Space lock (SE-1a)](spec-space-lock.md) ·
  [Space anatomy](architecture-space-anatomy.md)
- Code: `inspecto/src/main/java/com/gamma/control/SpacePromotion.java`,
  `inspecto/src/main/java/com/gamma/control/SpacePromotionRoutes.java`,
  `inspecto/src/main/java/com/gamma/control/PendingChanges.java` (`hold`, `REPLAYABLE`),
  `inspecto/src/main/java/com/gamma/control/ApprovalPolicy.java` (`forWholeSet`)
- Tests: `inspecto/src/test/java/com/gamma/control/ControlApiSpacePromotionTest.java` (routes, real HTTP),
  `inspecto/src/test/java/com/gamma/control/SpacePromotionTest.java` (connections and secrets, stamps, reserved files)
