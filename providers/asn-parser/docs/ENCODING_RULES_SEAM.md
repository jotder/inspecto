# The EncodingRules seam — design note per future ER

[REDESIGN.md](REDESIGN.md) goal 4: leave clean seams for PER / OER / XER / JER without
implementing them. This note states what the seam is and checks it against each future ER. None
of these are implemented; the point is to show the architecture does not have to change to add
them — and to be honest where it does.

## What the seam actually is

The stack is three layers with narrow interfaces:

```
ByteSource ──▶ [ BER codec ] ──▶ Tlv tree ──▶ [ SchemaBinder ] ──▶ NamedNode ──▶ [ transform ] ──▶ rows
                (asn-core)                      (asn-schema)                       (asn-transform)
```

The load-bearing fact: **`SchemaBinder` consumes a `Tlv` tree and a `CompiledSchema`, and knows
nothing about bytes.** Everything downstream of `NamedNode` (transform, plugins, sinks) is
encoding-agnostic already, and `CompiledSchema` is a pure X.680 model with no BER in it.

So adding an ER means providing a new producer of the *named tree*. There are two shapes of that,
and which one applies is the useful test:

- **Shape A — TLV-compatible.** The ER is tag/length/value framed; implement a new codec that
  emits `Tlv` and reuse `SchemaBinder` unchanged.
- **Shape B — schema-driven.** The ER's encoding cannot be parsed without the schema (no
  self-delimiting tags), so the codec and the binder collapse into one pass that walks
  `CompiledType` and the input together, emitting `NamedNode` directly.

`Strictness` is *not* the seam — it is a validation-mode knob inside the BER codec (BER/DER/CER
differ only in which encodings they accept, and all three produce identical trees). Do not try to
express PER as a `Strictness`.

## Per-ER assessment

**DER, CER — done.** Validation modes of the BER codec, as designed: `Strictness.DER` rejects
indefinite length and non-minimal lengths; `Strictness.CER` requires indefinite length on
constructed values and minimal lengths on primitives. CER's remaining canonical rule — SET
components sorted by tag — is deliberately *not* in the codec: it is a property of a component
sequence, checkable at the binder where component identity is known. Not implemented (no input
needs it).

**OER — Shape A/B hybrid, moderate.** Basic-OER is length-prefixed and largely schema-driven:
component presence comes from a preamble bitmap, not from tags. `Tlv` cannot represent it (there
are no tags to key on), so OER is Shape B — a `CompiledType`-walking reader emitting `NamedNode`.
`CompiledSchema` suffices as-is; nothing in asn-schema needs changing. Effort is in the new
reader, not the architecture.

**PER — Shape B, the hardest.** Aligned/unaligned PER is bit-oriented, fully schema-driven, and
needs information the current `CompiledType` **discards: constraints.** Size and value ranges
determine field widths in PER, and this subset parses constraints and throws them away
(see [X680_SUBSET.md](X680_SUBSET.md)) — deliberately, because vendor CDRs violate them.

**This is the one honest gap in the seam.** PER would require `CompiledType` to retain a
constraint model, plus a bit-level `BitSource` alongside `ByteSource`. Neither breaks the layering
— `SchemaBinder` and everything downstream stay untouched — but the claim "the seam suffices" is
only true for PER if you accept adding constraints to the compiled model. Recorded here so nobody
discovers it mid-implementation.

**XER, JER — Shape B, easy, and the seam is genuinely sufficient.** Both are textual and
self-describing (element/member names instead of tags). A reader walks `CompiledType` alongside a
parsed XML/JSON document and emits `NamedNode` — names match by identifier, which
`CompiledType.Component` already carries. JER can reuse `asn-transform`'s JSON parser. No changes
to `CompiledSchema`, no bit-level access, no constraint model. XER's variants (BASIC/CANONICAL/
EXTENDED) are validation modes, same relationship BER/DER/CER have.

## Verdict

The seam holds without modification for **DER, CER, XER, JER**, and for **OER** at the cost of a
new schema-driven reader. It holds for **PER only if `CompiledType` gains a constraint model** —
an additive change to asn-schema, not a restructuring, but a real prerequisite rather than a
detail. If PER is ever prioritised, retaining constraints at compile time (while continuing to
ignore them for BER decoding) is the first step and can be done independently.
