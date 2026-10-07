# Plugin author guide

Vendor and operator logic lives in plugin jars, never in core ([REDESIGN.md](REDESIGN.md) §4.5).
This is the contract for writing one.

## The SPI

`asn-plugin-api` is JDK-only and has no dependencies:

```java
public interface TransformFunctionProvider {
    Map<String, TransformFunction> functions(PluginContext context);
}

@FunctionalInterface
public interface TransformFunction {
    Object apply(List<Object> args) throws Exception;
}

@FunctionalInterface
public interface PluginContext {
    Map<String, Object> lookups();   // the config's @simpleLookup tables
}
```

`ValueDecoderProvider` and `FramingProvider` are named in the redesign but **deliberately not
defined yet** — no format needs a vendor-specific decoder or framing, and an interface with no
implementation is a liability. Add them when a real format requires one, not before.

## Writing a plugin

1. New Maven module depending only on `asn-plugin-api`.
2. Implement `TransformFunctionProvider`. `functions()` is called **once per pipeline**, so
   per-pipeline state (lookup tables, precomputed prefix maps) belongs in the returned closures —
   never in a `static` field. Static mutable state is what made the legacy `TransformUtils`
   impossible to run two configs against in one JVM.
3. Register it: `src/main/resources/META-INF/services/com.gamma.asn.plugin.TransformFunctionProvider`
   containing the implementation's fully-qualified name.
4. Put the jar on the pipeline's classpath (or plugin path). Discovery is plain `ServiceLoader`.

`asn-plugin-vendors` is the worked example: one provider, ~30 functions, verbatim from legacy.

## Argument and failure semantics

Arguments arrive **already evaluated** from the tx config: `"literal"` as a String, `$field` as
whatever the record held (String, `BigInteger`, `Boolean`, nested `Map`/`List`), `@self` as the
current node map.

- **Arity and types are not validated for you.** Cast and let it throw; a `ClassCastException`
  lands in the same place a legacy missing-overload did.
- **A thrown exception means "no value"** — `FunctionRegistry` catches it and the engine emits a
  null field. This is legacy-faithful and load-bearing for row parity. Do not "improve" a
  function by substituting a default where legacy produced null.
- **Returning a `Map`** spreads into multiple output fields (map keys become column names);
  returning a scalar sets the single field the config named.
- **An unregistered name resolves to null**, silently. That is why
  `getStartEndTime`/`convertedClientDate`/`interOperatorIdentifiers`/`subscriptionId`/`firstKey`
  are deliberately *absent* from `asn-plugin-vendors`: production configs call them, legacy never
  implemented them, and the corpus rows depend on them staying null. **Check the corpus before
  "fixing" a missing function.**

## Rules

- **Duplicate function names fail loudly at load time**, not at row time. Two plugins claiming
  one name is a deployment error.
- **Core owns generic functions** (arithmetic, string ops, date conversion honouring its
  arguments, lookups) in `asn-transform`'s `CoreFunctions`. Operator-specific thresholds, MSISDN
  prefixes, service-key bands, timezone offsets: plugin, always.
- **Porting from legacy? Port verbatim first, including the quirks**, then prove parity on the
  corpus, and only then consider fixing behaviour — as a documented deviation. Several legacy
  functions look buggy and are load-bearing (e.g. `normalization` tests the *original* number's
  length after already rewriting it, so a 10-digit `0`-prefixed number is only trimmed).
- One jar per operator once real per-operator deployments migrate; the single
  `asn-plugin-vendors` jar is a staging convenience, not the end state.

## Testing

Pin three things per plugin: that `ServiceLoader` actually finds the provider (a missing
`META-INF/services` entry fails silently, the worst possible failure), that the ghost names stay
unregistered, and the behaviour of each function including its quirks. See
`LegacyVendorFunctionsTest`.
