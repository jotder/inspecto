# {{name}} — a Consignment Processor

Processor `{{id}}`, scaffolded from `tools/scaffold.mjs` against
`{{engineGroupId}}:{{engineArtifactId}}:{{engineVersion}}`.

## Four commands

```bash
mvn -o test                                          # 1. run the tests
mvn -o package                                       # 2. build target/{{artifactId}}-1.0.0.jar
cp target/{{artifactId}}-1.0.0.jar "$ENGINE_LIB"     # 3. deploy onto the engine classpath
curl localhost:8080/health                           # 4. restart the engine, then confirm it is up
```

## Deployment differs from a Job Pack — read this

A Consignment Processor is found by a **plain `ServiceLoader` over the engine's own classpath**, not
by the Job Pack loader. Consequences, all of them deliberate and none of them optional:

- The jar goes **on the engine classpath**, not into `-Djobs.packs.dir`. Dropping it in the packs
  directory does nothing.
- There is **no hot deploy and no isolated classloader** — the engine picks it up at startup.
- There is therefore **no signature gate** (`-Djobs.packs.requireSignature` governs packs only) and
  no per-pack quiesce on unload.

If you need hot deployment or an operator-visible grant, you want a Job (`scaffold.mjs new job`)
rather than a processor.

## What to edit

| File | What it decides |
|---|---|
| `{{className}}Processor.java` | `id()` — how a Consignment run selects this processor — and the work itself |
| `{{className}}ProcessorTest.java` | the fake `ProcessorContext`; widen it as the processor grows |

## Rules that will bite you otherwise

- **Throw to fail.** `ProcessorResult` only separates "did the work" from "nothing to do"; a thrown
  exception is what the framework converts into a FAILED Run.
- **`outputs()` is empty when the outputs registry is default-off.** That is not proof the
  Consignment wrote nothing — the manifest is authoritative for a file's existence.
- **A dry run must mutate nothing** and must say so. Never fall through to the real action.
- **Every summary row carries the reserved `count` measure**; the emitter refuses a row that
  violates the guardrails, naming every violation at once.
