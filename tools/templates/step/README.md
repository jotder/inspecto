# {{name}} — a pack-hosted **Step**

Step kind `transform.{{typeSuffix}}`, scaffolded from `tools/scaffold.mjs` against
`{{engineGroupId}}:{{engineArtifactId}}:{{engineVersion}}`.

A Step pack is a Job Pack jar that carries a node type and the `StepExecutor` that runs it. Drop it in the
packs directory and the engine loads it in an isolated classloader. It runs inside a Pipeline, mid-walk,
under a watchdog. It never holds a database connection.

## Five commands

```bash
mvn -o test                                          # 1. run the tests (they run the Step the way the engine does)
mvn -o package                                       # 2. build target/{{artifactId}}-1.0.0.jar
cp target/{{artifactId}}-1.0.0.jar "$PACKS_DIR"      # 3. deploy (see below)
jarsigner -keystore my.jks target/{{artifactId}}-1.0.0.jar mykey   # 4. optional: sign it
curl -X POST localhost:8080/jobs/packs/rescan        # 5. load it
```

`$PACKS_DIR` is the engine's `-Djobs.packs.dir`. Before step 5, append
`sha256sum target/{{artifactId}}-1.0.0.jar` to the file named by `-Djobs.packs.allowlist`. A jar whose hash
is not listed is refused.

## Two registrations

| File | Half | What it decides |
|---|---|---|
| `{{className}}NodeType.java` | descriptor | that the Step exists: palette entry, `EXECUTED` mode, and the relations it `emits()` |
| `{{className}}Step.java` | execution | what the Step does with each row |
| `META-INF/services/com.gamma.pipeline.PipelineNodeType` | | registers the descriptor |
| `META-INF/services/com.gamma.pipeline.exec.StepExecutor` | | registers the Step |

## Rules the engine enforces

- **Declare every relation you emit** in `emits()`. `ctx.emit()` refuses anything else. A declared
  `data` or `reject:*` relation you never write is still produced, empty.
- **Reject rows, do not throw for them.** Emit a bad row to a declared `reject:<reason>` stream. A throw
  fails the whole batch, and the engine drops every table the Step created.
- **Declare the services you use** in `requires()`. An undeclared service is invisible. A mid-walk Step
  may never be granted `mail`, and an unknown id rejects the pack whole.
- **Honour the dry run.** `ctx.dryRun()` is true in every preview. Granted mutating services already
  record instead of act, and `ctx.signals()` only logs.
- **Honour interrupts.** The deadline is 5 min by default; `timeout_seconds` on the node overrides it, up
  to a 30 min system ceiling. At the deadline the engine interrupts the thread and fails the batch with
  `STEP_TIMEOUT`. A Step that ignores the interrupt is abandoned and its kind disabled until the pack is
  replaced.
- **Cost.** A row-at-a-time Step costs about 0.6–0.9 µs per cell, 30–40× a built-in SQL Step. If SQL can
  express it, use a built-in Step instead.
- ⛔ **No raw `PipelineNodeExecutor` in a pack.** A pack that carries one is rejected whole.
