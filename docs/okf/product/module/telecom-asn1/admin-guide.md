---
type: Guide
title: Telecom ASN.1 decoder — admin guide
description: Installing, confirming, securing and troubleshooting the optional inspecto-telecom-asn1 provider.
resource: providers/inspecto-telecom-asn1/
tags: [module, telecom-asn1, asn1, admin-guide, editions]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom ASN.1 decoder — admin guide

## Install

- **Edition.** Bundled from Standard up as `inspecto-telecom-asn1.jar` (`tools/bundle-modules.mjs`,
  `from: 'standard'`; add-on `telecomIndustryPack` in `offerings/standard.toon`). Basic does not carry it.
- **Build.** It is a default-reactor module (root `pom.xml`), so `mvn -o clean test` compiles and tests it in
  every build; edition profiles only decide whether `package.ps1` stages it. It depends on
  `com.gamma.asn:asn-facade`, built first from the aggregated `providers/asn-parser/asn-decoders` reactor.
- **Binding.** `bindingTime: boot`: the jar must be on the classpath at start; there is no hot install.

## Confirm it is loaded

- `GET /modules` lists the installed module manifests (served by `inspecto/src/main/java/com/gamma/control/ModulesRoutes.java`);
  look for id `telecom-asn1` providing `ParserPlugin` and step kind `parser.asn1.ber`.
- The parse drawer offers the parser **ASN.1 — BER/DER encoded records**.

## Configure

There are no `-D` flags or environment variables. All settings are per Pipeline (`asn1:` block or
`parsing.plugin.ingester_config`), listed in the [user-guide](user-guide.md) and [spec](spec.md). Grammar
files (`.asn` / `.asn1`) and Decode Profiles are stored under the Space's config, beside the Pipeline config.

## Secure

- A grammar file reference must end `.asn` / `.asn1` and is path-jailed to the allowed roots
  (`Asn1GrammarSource`); an escaping reference is refused outright. This matters because the preview route
  is compute-only and a compile error can echo file text.
- `max_value_bytes` (default 64 MiB) bounds memory per value against hostile input; real CDR values are
  under 1 KB, so lower it per Pipeline if needed.

## Troubleshoot

| Symptom | Cause / action |
|---|---|
| ASN.1 parser missing; Pipeline listed `hosted:false` with `missingModule: telecom-asn1` | Module not in this bundle (Basic) or jar not on the classpath. Install a Standard+ bundle. |
| Ingest fails naming the module (`PluginIngesters.notLoaded`) | Same: the ingester class cannot load. |
| Preview: "no ASN.1 records found … check the framing" | Set `file_header_length` / `record_header_length`. |
| "asn1.root_type is required when a grammar is supplied" / "root_type was set without a grammar" | Supply both, or neither for a structural dump. |
| "must name an ASN.1 module file (.asn or .asn1)" / "not readable" | Fix the file reference; keep it beside the Pipeline config. |
| Files land in `QUARANTINED_UNREADABLE` | A record failed to decode (whole file refused), or `derive` / `children` limits were exceeded. Preview the file to find the record. |
| Files land in `QUARANTINED_MISMATCH` | No record matched a declared segment name; check `segments` keys against the record type names. |
| Log line `derive_non_numeric=… derive_no_match=…` | Derived columns could not compute for some records; informational. |

## References

- [architecture](architecture.md) · [spec](spec.md)
- [asn-decoders](../asn-decoders/index.md) — the underlying library
