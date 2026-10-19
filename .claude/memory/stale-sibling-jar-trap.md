---
name: stale-sibling-jar-trap
description: "mvn -pl <mod> (no -am) or -rf resolves sibling modules from the STALE ~/.m2 jar — a false red or false green that looks like a code bug"
metadata:
  type: reference
---

`mvn -o -pl <module> test` resolves sibling modules from `~/.m2`, not your edited source; nothing in this repo is
routinely `mvn install`ed, so `~/.m2` is permanently stale. Loud form: missing symbols / wrong arity in test classes
you never touched (reads exactly like drift on master). Quiet form: the old jar compiles and the module tests the
sibling's PREVIOUS behaviour (a new 422 returned 200). `-rf :<module>` does the same (drops upstream modules).

**How to apply:**
- Before believing a cross-module failure, find the symbol's OWNING module (`git ls-files "*Foo.java"`). If it is not
  the `-pl` module, the classpath is stale, not the source.
- Use the tool-printed commands from `node tools/affected-modules.mjs` (CLAUDE.md). Escape hatch when `-Dtest=`
  forbids `-am`: `mvn -o install -DskipTests -pl <owning-module> -am`, then re-run unchanged.
- Proof: `unzip -p ~/.m2/repository/com/gamma/inspector/<artifact>/<ver>/<artifact>-<ver>.jar <Class>.class | grep -c <NEW_SYMBOL>`
  (groupId path is `com/gamma/inspector/`). 0 = the run proved nothing.
- Directory `inspecto/` is artifact `inspecto-processor` in reactor output — same module.
- A parallel lane's `mvn install` also overwrites the shared `~/.m2`.

Related: [[maven-verdict-traps]], [[editions-and-toolchain]].
