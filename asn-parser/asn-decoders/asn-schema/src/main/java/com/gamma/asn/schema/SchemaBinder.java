package com.gamma.asn.schema;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Tlv;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a TLV tree against a {@link CompiledSchema} and produces the named tree
 * (REDESIGN.md §4.3). CHOICE alternatives are selected by tag; IMPLICIT vs EXPLICIT comes
 * from the schema, never guessed from constructedness. Unmatched nodes keep their tag-path
 * names and hex values — nothing is ever dropped.
 */
public final class SchemaBinder {

    private final CompiledSchema schema;
    private final ByteSource src;
    private final DecoderRegistry registry;

    public SchemaBinder(CompiledSchema schema, ByteSource src, DecoderRegistry registry) {
        this.schema = schema;
        this.src = src;
        this.registry = registry;
    }

    public NamedNode bind(Tlv record) {
        String path = String.valueOf(record.tagNumber());
        CompiledType root = schema.root();
        TagKey key = TagKey.of(record);
        if (!root.matches(key)) {
            return bindUnionFallback(record, root, path);
        }
        return bind(record, root, root.typeName(), path);
    }

    /**
     * Vendor grammars declare a record union as a SET/SEQUENCE whose tagged components
     * are the record types (aftel {@code IMSRecord ::= SET}, huwMsc
     * {@code CallEventRecord ::= SEQUENCE --CHOICE}) while the data carries one tagged
     * alternative; the legacy decoder matched by tag, never by the union's own tag —
     * mirror that, else keep the tag-path/hex contract.
     */
    private NamedNode bindUnionFallback(Tlv tlv, CompiledType type, String path) {
        if (type != null && (type.kind() == CompiledType.Kind.SET
                || type.kind() == CompiledType.Kind.SEQUENCE)) {
            TagKey key = TagKey.of(tlv);
            int pos = type.componentIndex().firstAtOrAfter(key, 0);
            if (pos >= 0) {
                CompiledType.Component c = type.components().get(pos);
                return bind(tlv, c.type(), c.name(), path);
            }
            // vendors encode SEQUENCE with the SET tag and vice versa (huwIMS
            // SubscriptionID SEQUENCE arrives as UNIVERSAL 17) — the legacy decoder
            // never looked at the universal tag at all
            if (tlv.constructed() && key.tagClass() == com.gamma.asn.core.TagClass.UNIVERSAL
                    && (key.number() == 16 || key.number() == 17)) {
                return bind(tlv, type, type.typeName(), path);
            }
        }
        return unknown(tlv, path);
    }

    private NamedNode bind(Tlv tlv, CompiledType type, String name, String path) {
        if (type == null || type.kind() == null) {
            return unknown(tlv, path);
        }
        return switch (type.kind()) {
            case EXPLICIT_WRAPPER -> {
                // the outer tag was matched by the caller; the single child is the real type
                if (tlv.children().size() != 1) {
                    yield unknown(tlv, path);
                }
                Tlv child = tlv.children().getFirst();
                String childPath = path + "." + child.tagNumber();
                CompiledType inner = type.inner();
                if (inner == null || !inner.matches(TagKey.of(child))) {
                    yield new NamedNode(name, path, type.typeName(), null,
                            List.of(unknown(child, childPath)), type);
                }
                yield new NamedNode(name, path, type.typeName(), null,
                        List.of(bind(child, inner, name, childPath)), type);
            }
            case CHOICE -> {
                CompiledType.Component alt = selectAlternative(type, tlv);
                if (alt == null) {
                    yield unknown(tlv, path);
                }
                yield bind(tlv, alt.type(), alt.name(), path);
            }
            case SEQUENCE, SET -> new NamedNode(name, path, type.typeName(), null,
                    bindComponents(tlv, type, path), type);
            case SEQUENCE_OF, SET_OF -> {
                List<NamedNode> items = new ArrayList<>();
                CompiledType element = type.inner();
                for (Tlv child : tlv.children()) {
                    String childPath = path + "." + child.tagNumber();
                    if (element != null && element.matches(TagKey.of(child))) {
                        items.add(bind(child, element, element.typeName(), childPath));
                    } else {
                        items.add(bindUnionFallback(child, element, childPath));
                    }
                }
                yield new NamedNode(name, path, type.typeName(), null, items, type);
            }
            case ANY -> {
                NamedNode raw = unknown(tlv, path);
                yield new NamedNode(name, path, type.typeName(), raw.value(), raw.children(), type);
            }
            case PRIMITIVE -> {
                if (tlv.constructed()) {
                    // e.g. BER constructed string encoding — keep structure, don't guess
                    yield new NamedNode(name, path, type.typeName(), null,
                            unknownChildren(tlv, path), type);
                }
                yield new NamedNode(name, path, type.typeName(), decode(tlv, type), List.of(), type);
            }
        };
    }

    private List<NamedNode> bindComponents(Tlv tlv, CompiledType type, String path) {
        List<NamedNode> out = new ArrayList<>();
        List<CompiledType.Component> components = type.components();
        CompiledType.ComponentIndex index = type.componentIndex();
        boolean ordered = type.kind() == CompiledType.Kind.SEQUENCE;
        int nextComponent = 0;
        for (Tlv child : tlv.children()) {
            String childPath = path + "." + child.tagNumber();
            TagKey childTag = TagKey.of(child);
            int found;
            if (ordered) {
                // vendor CDRs omit components regardless of OPTIONAL (legacy matched purely
                // by tag), so skip past unmatched mandatory components instead of stopping
                found = index.firstAtOrAfter(childTag, nextComponent);
                if (found < 0) {
                    // repeated/out-of-order tags (e.g. an event stream declared as a plain
                    // SEQUENCE): fall back to the legacy tag-map behaviour without moving
                    // the ordered cursor
                    int earlier = index.firstBefore(childTag, nextComponent);
                    if (earlier >= 0) {
                        out.add(bind(child, components.get(earlier).type(),
                                components.get(earlier).name(), childPath));
                        continue;
                    }
                }
            } else {
                found = index.firstAtOrAfter(childTag, 0);
            }
            if (found < 0) {
                out.add(unknown(child, childPath));
                continue;
            }
            CompiledType.Component comp = components.get(found);
            out.add(bind(child, comp.type(), comp.name(), childPath));
            if (ordered) {
                nextComponent = found + 1;
            }
        }
        return out;
    }

    private CompiledType.Component selectAlternative(CompiledType choice, Tlv tlv) {
        int pos = choice.componentIndex().firstAtOrAfter(TagKey.of(tlv), 0);
        return pos < 0 ? null : choice.components().get(pos);
    }

    private String decode(Tlv tlv, CompiledType type) {
        // ENUMERATED/named INTEGER values decode as raw integers, like the legacy
        // decoder (downstream transform configs match on the numbers); the names stay
        // available on CompiledType.valueNames() for consumers that want them.
        // Name-chain resolution normalizes (uppercase + strip spaces/dashes) and so
        // allocates per name — cache it per type; this binder owns one registry, so the
        // resolution can never change under us. Identity keying: compiled types are
        // graph nodes, and equal-looking ones may carry different name chains.
        ValueDecoder decoder = decoderCache.get(type);
        if (decoder == null) {
            decoder = registry.resolve(type.nameChain(), type.primitive());
            decoderCache.put(type, decoder);
        }
        return decoder.decode(tlv.value(src));
    }

    private final java.util.Map<CompiledType, ValueDecoder> decoderCache = new java.util.IdentityHashMap<>();

    /** Hex-string fallback for unknown tags/types — the compatibility contract (§3). */
    private NamedNode unknown(Tlv tlv, String path) {
        if (tlv.constructed()) {
            return new NamedNode(path, path, tlv.tagString(), null, unknownChildren(tlv, path));
        }
        return new NamedNode(path, path, tlv.tagString(), Decoders.hex(tlv.value(src)), List.of());
    }

    private List<NamedNode> unknownChildren(Tlv tlv, String path) {
        List<NamedNode> out = new ArrayList<>();
        for (Tlv child : tlv.children()) {
            out.add(unknown(child, path + "." + child.tagNumber()));
        }
        return out;
    }
}
