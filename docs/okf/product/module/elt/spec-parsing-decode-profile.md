---
type: Concept
title: Decode Profile — specification
description: A Decode Profile keeps one vendor's decode settings once — the operator decisions (D5, D6, D9, D10), how a Pipeline's own keys win and segments are replaced whole, bundle export, the effective-value read-back and its tests. Section P.12 of the parsing spec, kept on its own page.
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/DecodeProfile.java
tags: [parsing, decode-profile, asn1, grammar, plugin, segments, bundle]
timestamp: 2026-11-19T00:00:00Z
---

# P.12 Decode Profile — one vendor's decode settings, kept once (2026-09-25, slices C1–C4)

Section P.12 of the [parsing specification](spec-parsing.md) (parser plugins
[§P.9](spec-parsing-profiles-plugins.md#p9-parser-plugins--the-self-describing-parser-framework), drop-in parser jars
[§P.11](spec-parsing-hierarchical-decode.md#p11-drop-in-parser-jars--trust-gate-staging-dir-pack-parsers-ingest-and-preview-gate-shipped-2026-09-25),
deferred items [§P.13](spec-parsing-hierarchical-decode.md#p13-deferred-by-decision-the-trust-designs-open-items)), on its own page so each
stays readable whole.

Operator decisions 2026-09-25: **D5** a key the Pipeline sets wins, and `segments` is **replaced whole**,
never merged. **D6** a bundle carries the profile as its own satellite and rewrites the refs inside it,
refusing basename collisions, and never inlines it. **D9** no explode-style option for repeated children
until prevalence is measured (since measured and built — [spec-parsing.md §P.13](spec-parsing-hierarchical-decode.md#p13-deferred-by-decision-the-trust-designs-open-items)). **D10** the name is **Decode Profile** (GLOSSARY §5).

**Shape.** A satellite `.toon` whose one top-level block is `asn1:`, holding the same keys a Pipeline's
`parsing.asn1` takes. It is not a registry kind and has no routes of its own. It is written, read and
jailed like the grammar file.

```
config/vendors/ericsson_msc/ericsson_msc.decode.toon      asn1:
                                                            grammar_file: ericsson_msc.asn
                                                            root_type: CallEventRecord
                                                            strictness: BER
                                                            segments:
                                                              moCallRecord: mo_call_schema.toon
config/msc/msc_switch1_pipeline.toon                      parsing:
                                                            frontend: asn1
                                                            asn1:
                                                              profile_file: ../vendors/ericsson_msc/ericsson_msc.decode.toon
                                                              max_value_bytes: 4194304
```

**One overlay, `com.gamma.etl.DecodeProfile.overlay`.** The load (`PipelineConfigParser.asn1PluginBlock`, before
its required-key checks, so a profile-backed Pipeline hits the same refusals) and the preview
(`Asn1ParserPlugin.preview`) both call it, so a profile that previews is the profile that ingests:
- `profile_file` must end `.toon`. That is checked before anything touches the disk. The ref resolves
  beside the referring config (`PathJail.resolveConfigRef`) and is jailed (`requireUnderAny(allowedRoots())`)
  **before** the readability probe, so an escaping ref is an escape (403 on the preview), never "not
  readable".
- Every relative ref **inside** the profile (`grammar_file`, the `segments` values) resolves **beside the
  profile** and comes back absolute. The downstream resolvers still jail it: `Asn1GrammarSource` for the
  grammar, `resolveSchemaRef` for a segment. A `schema/<id>` registry ref is left as an id.
- Keys the Pipeline sets win, key by key. A **null or blank value is unset**, not an override, so an empty
  `grammar_file` cannot clear the profile's.
- Refused at load: a profile that names another `profile_file` (no nesting), a file with no `asn1:` block,
  and a file holding anything besides `asn1:`.
- ⚠ **Nothing of a non-profile file is echoed.** The ASN.1 preview is open to any authenticated user and
  any `.toon` under the roots can be named, so a TOON parse failure does not quote the codec's message and a
  file without `asn1:` does not list its keys. Only a real profile's own extra keys are named.
- The profile, its grammar and its segment schemas are in `referencedFiles()` (reload trigger + bundle
  closure). A segment the Pipeline replaced is not.

**Save gate.** `ConfigSafetyValidator` (pipeline) refuses a `parsing.asn1.profile_file` that is not `.toon`,
or that escapes, with an ERROR finding, so `/config/write`, `/config/patch` and bundle import answer 422
before the load would refuse it. Not a `ConfigSpecs` / accepted-key census entry: the census stops at the
top-level `parsing` block, and the served `grammarSchema` (which now lists `asn1.profile_file`) is the
declaration for `asn1.*`.

**The Parse drawer (UI).** The served form holds every `asn1.*` field at its default. On the server every
key the Pipeline sets wins over the profile, so persisting the whole form would silently fork the Pipeline
off its profile (`record_header_length: 0` over the profile's `4`). `decodeProfileOverrides`
(`pipeline-parse-definition.component.ts`) keeps only `profile_file`, `segments`, keys the saved block
already set non-blank, and keys the user changed (`GrammarEditorComponent.dirtyKeys`, FLAT control keys —
`asn1__strictness`; the function matched a dotted spelling until 2026-09-25, which silently dropped every
such edit on Save). Blank values are always dropped. The drawer's preview sends the same subset with its
`subdir`. A block without a profile is untouched.

**The drawer reads the profile back (2026-09-25)** through `GET /parsers/asn1/profile?profile_file=<ref>&subdir=<pipeline dir>`
(`ParserRoutes.decodeProfile`), which returns `{asn1: <the profile's own block, AS AUTHORED>}` — refs stay
relative to the profile, never the resolved absolute paths. It reads through `DecodeProfile.read`, the half of
`overlay` that resolves, jails and validates, so the read and the overlay cannot disagree on what a profile
is: `.toon` checked first, jail before the readability probe (escape **403**), and the same never-echo error
text (**422**: a `.toon` that is not a profile, or not valid TOON, is refused without quoting it). Missing
`profile_file` or an absolute `subdir` is **400**. `subdir` means what it means on the preview. Read-only, so
ungated (a GET needs no manifest entry) — open to the same users as a built-in's preview. The drawer seeds its
form with the profile's values under the Pipeline's and lists each key's provenance (*from profile* / *set
here* / *default*) — see [grammar-config.md](../config/user-guide-grammar-config.md). Save is unchanged.
**Profile segments in the drawer (2026-10-06).** The same section lists the profile's own `segments`
(key → schema ref, as authored, `profileSegmentsOf`), marked *replaced by this Pipeline's own* when the
Pipeline carries `segments` (D5: replaced whole). A profile-backed Pipeline with **no** `segments` of its own
and an empty segments editor now Applies without a `segments` key and writes no segment schema — the
inherited ones are the profile's. This is checked only once the profile has been read and names segments;
an unread, unreadable or segment-less profile still meets *Add at least one segment* (the load refuses a
plugin ingester with no segments anyway). Proof: the *Decode Profile read-back* block of
`pipeline-parse-definition.component.spec.ts` (list + a11y, Apply without segments, the negative refusal).

**Bundles (D6).** Export ships the profile byte-verbatim as its own satellite beside its grammar and schemas,
and the Pipeline keeps only `profile_file` (never inlined, which would import as N un-shared copies). Two
different files under one basename are the existing **409**. Import rewrites `parsing.asn1.profile_file`
(`rewriteSatelliteRefs`) and, inside the landed profile, `asn1.grammar_file` and the `segments` values
(`PipelineBundleRoutes.rewriteProfile`) to the bundled basenames, before the satellites land and the save
gate runs. The manifest sha256 is checked against the bytes as shipped.

**Scope.** ASN.1 only. XML's `ingester_config` has the same per-vendor shape but no customer asking. The
legacy tree operations on repeated children (`flattenList` / `reduce` / `cartesianJoin`) have no home on
purpose (D9; what was built instead is in [spec-parsing.md §P.13](spec-parsing-hierarchical-decode.md#p13-deferred-by-decision-the-trust-designs-open-items)).

Proof: `UnifiedParsingBlockTest` (profile-only load with refs beside the profile; Pipeline scalar wins;
blank is unset; `segments` replaced whole; escape; `.txt` refused; nesting and extra blocks refused),
`ConfigSafetyValidatorTest.aDecodeProfileRefIsJailedAndMustNameAToonFile`,
`ControlApiParsersTest.asn1PreviewHonoursADecodeProfileBesideThePipelineSubdir` (profile preview equals
inline, the Pipeline's `root_type` wins, escape 403),
`ControlApiPipelineBundleTest.aDecodeProfileTravelsAsItsOwnSatelliteAndItsRefsAreRewritten` +
`twoProfileSatellitesSharingABasenameAreRefused`,
`DemoCorpusIngestTest.mscCdrSplitIntoADecodeProfilePreviewsAndIngestsIdenticallyToInline` (the msc_cdr demo
split into profile + Pipeline: same preview tree, the same rows in every segment),
`ControlApiDecodeProfileReadTest` (real HTTP with a viewer Subject: as-authored values beside the subdir,
escape 403, `.asn` 422, two non-profile `.toon` files refused without echoing a marker, 400s), and the drawer
specs `decodeProfileOverrides` + *Decode Profile read-back* (seeded effective values, provenance, an edited
profile key becomes *set here* and Save writes only the overrides, late catalog + late profile, read failure;
mutation-checked: without the seed or with the dotted dirty key, four go red). **Mutation-checked**: merging `segments`, probing readability before the jail,
dropping the preview overlay, and dropping the in-profile import rewrite each go red.
