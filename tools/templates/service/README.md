# {{name}} - a pack-contributed **Platform Service**

Service id `{{id}}`, scaffolded from `tools/scaffold.mjs` against
`{{engineGroupId}}:{{engineArtifactId}}:{{engineVersion}}`.

A Service pack is a Job Pack jar that carries a `ServiceProvider`. Drop it in the packs directory and the
engine binds the service under the pack. Any Job or Step in any pack then reaches it by declaring
`requires: [{{id}}]`.

## Five commands

```bash
mvn -o test                                          # 1. run the tests
mvn -o package                                       # 2. build target/{{artifactId}}-1.0.0.jar
cp target/{{artifactId}}-1.0.0.jar "$PACKS_DIR"      # 3. deploy (see below)
jarsigner -keystore my.jks target/{{artifactId}}-1.0.0.jar mykey   # 4. optional: sign it
curl -X POST localhost:8080/jobs/packs/rescan        # 5. load it
```

`$PACKS_DIR` is the engine's `-Djobs.packs.dir`. Before step 5, append
`sha256sum target/{{artifactId}}-1.0.0.jar` to the file named by `-Djobs.packs.allowlist`.

## Rules the engine enforces

- **The interface is engine-published.** `type()` must be an interface the engine already exposes. A pack
  cannot define the interface: consumers in other packs match on `Class` identity, and two pack classloaders
  cannot share a type. You supply an implementation of an interface that exists but is not bound in this
  build. A pack-defined interface is a later design.
- **A colliding id or interface rejects the pack whole**, and nothing it contributed stays bound.
- **A mutating service must supply a dry-run stand-in** (`dryRun`) that logs the would-be effect and does
  nothing, or declare `readOnly()`. A pack without one is rejected.
- **No unload protection yet.** A Run already holding the service keeps its reference if the pack unloads.
