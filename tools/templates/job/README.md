# {{name}} — a {{engineArtifactId}} Job Pack

Job Type `{{id}}`, scaffolded from `tools/scaffold.mjs` against `{{engineGroupId}}:{{engineArtifactId}}:{{engineVersion}}`.

A pack is a plain jar. Drop it in the packs directory and the engine loads it in an isolated
classloader; delete it and the engine unloads it once its in-flight Runs finish. No engine rebuild,
no restart.

## Five commands

```bash
mvn -o test                                          # 1. run the harness tests
mvn -o package                                       # 2. build target/{{artifactId}}-1.0.0.jar
cp target/{{artifactId}}-1.0.0.jar "$PACKS_DIR"      # 3. deploy (see below)
jarsigner -keystore my.jks target/{{artifactId}}-1.0.0.jar mykey   # 4. optional: sign it
curl localhost:8080/jobs/types/{{id}}                # 5. confirm the engine registered it
```

`$PACKS_DIR` is whatever the engine was started with as `-Djobs.packs.dir`. With that flag absent
the pack mechanism is off entirely — nothing is scanned, by design.

Start the engine with `-Djobs.packs.requireSignature=true` and every class entry in the jar must
carry a valid signature or the pack is rejected whole. Signing (step 4) is what makes that pass;
grants tell an operator what a pack *can* reach, signatures tell them the jar is the one you built.

## What to edit

| File | What it decides |
|---|---|
| `{{className}}Provider.java` | the Job Type: its declared parameters, the Signals it emits, and its `requires:` grants |
| `{{className}}Job.java` | what the Job actually does, using only what `JobContext` hands it |
| `{{className}}Test.java` | the harness test — keep it green, it is your loadability proof |

## Rules that will bite you otherwise

- **Declare what you use.** A Platform Service missing from `requires:` is invisible at run time
  even though the engine has it. A service listed but never looked up is a grant an operator was
  shown for nothing — remove it.
- **A wrong `requires:` refuses the whole pack** at registration, naming the id and what is
  available. That is deliberate: never an empty lookup half-way through a Run.
- **Keep the engine dependency `provided`.** Bundling it puts a second copy of the SPI classes in
  your isolated classloader and every grant lookup misses.
- **Honour the dry run** by doing nothing yourself and letting the framework's stand-ins record the
  mutating calls — `dryRun()` on the Job is a promise to the operator who clicked Preview.
