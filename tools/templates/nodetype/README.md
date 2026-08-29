# {{name}} — a plugin **node type** (Step)

Node type `transform.{{typeSuffix}}`, built against
`com.gamma.inspector:inspecto-engine:4.0.0-SNAPSHOT`. It keeps the first N characters of each listed
column and masks the rest, so a subscriber identifier stays joinable on its prefix without carrying the
full value downstream.

This is the worked example for the **node-type plugin seam** — see
[`docs/okf/backend/engine/node-types.md`](../../docs/okf/backend/engine/node-types.md).

## Four commands

```bash
mvn -o test                                       # 1. run the tests (they run the Step the way the engine does)
mvn -o package                                    # 2. build target/{{artifactId}}-1.0.0.jar
cp target/{{artifactId}}-1.0.0.jar "$ENGINE_LIB"     # 3. deploy onto the engine classpath
curl localhost:8080/health                        # 4. restart the engine, then confirm it is up
```

⚠ **Step 1 needs the SPI in your local repository.** Building outside the reactor resolves
`inspecto-engine` from `~/.m2`, so an engine jar installed before the `PipelineNodeExecutor` seam landed
fails with *"cannot find symbol: class PipelineNodeExecutor"*. Install a current one first:

```bash
mvn -o install -DskipTests -pl inspecto-engine -am
```

## A node type is TWO registrations

| File | Half | What it decides |
|---|---|---|
| `{{className}}NodeType.java` | descriptor | that the Step **exists** — palette entry, label, category, and the `accepts`/`emits` relationships `PipelineValidator` enforces |
| `{{className}}Executor.java` | executor | that the Step **runs** — the SQL, and the tables it produces |
| `META-INF/services/com.gamma.pipeline.PipelineNodeType` | | registers the descriptor |
| `META-INF/services/com.gamma.pipeline.exec.PipelineNodeExecutor` | | registers the executor |

🔴 **You need both, and they must agree.** A descriptor alone renders in the palette, validates and
lifts — and then throws at run time (that was the descriptor-only gap this seam closed). An executor
alone shapes relations the validator will not let anyone wire, because an outbound edge whose
relationship the type does not `emits()` is rejected. `{{className}}Test.bothHalvesAreDiscovered` fails
before any SQL runs if either service file is wrong.

## Deployment — classpath, not the packs directory

Node types are found by a **plain `ServiceLoader` over the engine's own classpath**, exactly like a
Consignment Processor and unlike a Job Pack:

- The jar goes **on the engine classpath**, not into `-Djobs.packs.dir`. Dropping it in the packs
  directory does nothing.
- 🔴 **The registry is `static final`, built once at class load.** There is no hot deploy, no isolated
  classloader, and no unload — the engine picks the jar up at startup and never re-scans.
- There is therefore no signature gate (`-Djobs.packs.requireSignature` governs packs only).

If you need hot deployment or an operator-visible grant, you want a Job (`scaffold.mjs new job`), not a
node type.

## Authoring it in a pipeline

```toon
steps[1]{id,type,columns,keep}:
  redact,transform.{{typeSuffix}},"msisdn|imsi",5
```

The `transform.` prefix is not decoration: the inline component-preview route refuses a config whose
`type` is not `transform.*`, so a differently-named type could never be exercised from the editor's
**Test this Step**.

## Rules that will bite you otherwise

- 🔴 **Quote every identifier.** Column names arrive from an operator's config and reach SQL directly.
  A name like `order` is a reserved word and one containing a quote is an injection —
  `aReservedWordColumnNameIsQuoted` is the test that pins it.
- 🔴 **Create your own output tables**, named `outPrefix + "__" + relationship`, and return one
  `RowShaper.Relation` per table. Nothing renames them; the caller reads exactly the names you return.
- ⚠ **Fail by throwing.** A bad config must raise rather than produce a silently wrong relation — the
  batch fails with your message, which is what an operator can act on.
- ⚠ **The preview seals its connection** (`enable_external_access=false`), so an executor that reads a
  file works in production and fails in **Test this Step**. Read nothing outside `input`.
- ⚠ **Single input only.** This seam covers `RowShaper.shape`. Fan-in (`transform.merge`) goes through
  a different signature and is not part of the contract.
- ⚠ **Preserve NULL.** `NULL` masked to a string of `*` is a value where there was none — the test
  asserts a NULL stays NULL.

## What to edit

| File | What it decides |
|---|---|
| `{{className}}NodeType.java` | `TYPE` — how a config selects this Step — plus the label and the relationships it may emit |
| `{{className}}Executor.java` | the config keys read (`columns`, `keep`, `mask`) and the SQL |
| `{{className}}Test.java` | runs the Step through `RowShaper.shape`, i.e. the way the engine will — widen it as the Step grows |
