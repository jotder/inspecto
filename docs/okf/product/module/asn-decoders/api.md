---
type: Reference
title: ASN.1 decoders — Java API
description: The asn-facade decoding API and the TransformFunctionProvider SPI, with registration and a minimal example.
resource: providers/asn-parser/asn-decoders/
tags: [module, asn-decoders, asn1, api, spi]
timestamp: 2026-10-19T00:00:00Z
---

# ASN.1 decoders — Java API

The library has no HTTP routes. Its contract is two Java surfaces: the **facade** callers decode with, and
one **ServiceLoader SPI** for vendor transform functions.

## 1. `com.gamma.asn.facade.Asn1Decoder` (artifact `com.gamma.asn:asn-facade`)

Defined in `providers/asn-parser/asn-decoders/asn-facade/src/main/java/com/gamma/asn/facade/Asn1Decoder.java`.
An instance is immutable and safe to share across files and threads.

| Method | Semantics |
|---|---|
| `static compile(String grammarText, String rootTypeName)` | Strict X.680 parse and compile against the default decoder registry (universal + telecom). Throws on a bad grammar. |
| `static compileLenient(grammarText, rootTypeName, List<String> warnings)` | Recovers per component/assignment; every skip is appended to `warnings`; unresolved type refs decay to OCTET STRING. |
| `static of(CompiledSchema, DecoderRegistry)` | Wrap a pre-compiled schema with a custom registry. |
| `decode(ByteSource, Framing, Strictness, RecoveryPolicy, ErrorListener[, int maxValueBytes])` | Lazy `Stream<NamedNode>`, one schema-bound tree per record. The caller owns and closes the `ByteSource`. Default value cap `BerReader.DEFAULT_MAX_VALUE_BYTES` (64 MiB); a larger declared value fails its record through the listener before allocation. |
| `decode(ByteSource)` | Bare back-to-back TLVs, strict BER, `STOP_FILE`. |
| `decodeToRows(..., String recordType, LegacyTransformEngine)` | Decode, map each record with `RecordMapper.toMap`, flatten through a legacy transform config; eager. |

`RecoveryPolicy`: `SKIP_RECORD` (report and continue) or `STOP_FILE`. `Strictness`: BER (permissive) / DER
(and CER as used by [telecom-asn1](../telecom-asn1/spec.md)).

Minimal use:

```java
Asn1Decoder dec = Asn1Decoder.compile(grammarText, "CallEventRecord");
List<ParseError> errors = new ArrayList<>();
try (ByteSource src = ByteSource.of(bytes)) {
    dec.decode(src, Framing.none(), Strictness.BER, RecoveryPolicy.SKIP_RECORD, errors::add)
       .forEach(rec -> System.out.println(RecordMapper.toMap(rec)));
}
```

## 2. SPI `com.gamma.asn.plugin.TransformFunctionProvider` (artifact `asn-plugin-api`)

Defined in `providers/asn-parser/asn-decoders/asn-plugin-api/src/main/java/com/gamma/asn/plugin/TransformFunctionProvider.java`.

```java
public interface TransformFunctionProvider {
    Map<String, TransformFunction> functions(PluginContext context);   // called once per pipeline
}
@FunctionalInterface interface TransformFunction { Object apply(List<Object> args) throws Exception; }
@FunctionalInterface interface PluginContext { Map<String, Object> lookups(); }  // the config's lookup tables
```

- A function is invoked from a transform config as `"@transform": "name(args)"`; a thrown exception means
  "no value" (a null field), as the legacy reflective dispatch did.
- Function names that collide across providers fail **loudly at load time** — no silent override.
- Per-pipeline state comes only through `PluginContext` (no static caches).

### Implementing one

1. Depend on `com.gamma.asn:asn-plugin-api`.
2. Implement `TransformFunctionProvider`, returning name to function.
3. List the class in `META-INF/services/com.gamma.asn.plugin.TransformFunctionProvider`.

The shipped example is `asn-plugin-vendors` (`LegacyVendorFunctions`, registered in
`providers/asn-parser/asn-decoders/asn-plugin-vendors/src/main/resources/META-INF/services/com.gamma.asn.plugin.TransformFunctionProvider`).
Full guide: `providers/asn-parser/docs/PLUGIN_GUIDE.md`.

## References

- [spec](spec.md) · [architecture](architecture.md)
- `providers/asn-parser/docs/X680_SUBSET.md` — the accepted grammar subset
- `providers/asn-parser/docs/CONFIG_REFERENCE.md` — transform config keys
