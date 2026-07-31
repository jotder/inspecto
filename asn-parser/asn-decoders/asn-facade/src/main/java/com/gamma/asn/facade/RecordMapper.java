package com.gamma.asn.facade;

import com.gamma.asn.schema.CompiledType;
import com.gamma.asn.schema.NamedNode;
import com.gamma.asn.schema.ast.BuiltinKind;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NamedNode → the legacy decoder's record map shape, so tx configs written against the
 * legacy output drive the new stack unchanged:
 * - constructed node → LinkedHashMap of child name → converted child;
 * - SEQUENCE OF / SET OF → a map with ONE key, the element type name with its first
 *   letter decapitalised (legacy: {@code servedSubscriptionIDs: {subscriptionID: [...]}}),
 *   holding the element list;
 * - INTEGER/ENUMERATED leaves as BigInteger, BOOLEAN as Boolean, everything else String
 *   (matches the legacy value types that reach the transformer and its overloads).
 *
 * Public port of {@code com.gamma.asn.golden.RecordMapper} (package-private there, and
 * {@code asn-golden} also pulls in {@code legacy-code} — not something production code should
 * depend on). Kept byte-for-byte identical to the golden version so the parity harness and this
 * facade never drift.
 */
public final class RecordMapper {

    private RecordMapper() {
    }

    public static Map<String, Object> toMap(NamedNode record) {
        Object converted = convert(record);
        if (converted instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) m;
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(record.name(), converted);
        return out;
    }

    private static Object convert(NamedNode node) {
        CompiledType type = node.type();
        if (type != null && (type.kind() == CompiledType.Kind.SEQUENCE_OF
                || type.kind() == CompiledType.Kind.SET_OF)
                && homogeneous(node)) {
            // heterogeneous children mean union-fallback records inside a vendor
            // "SEQUENCE OF" file wrapper (huwMsc) — those convert like a struct below
            Map<String, List<Object>> byName = new LinkedHashMap<>();
            for (NamedNode child : node.children()) {
                byName.computeIfAbsent(decap(child.name()), k -> new ArrayList<>())
                        .add(convert(child));
            }
            return byName;
        }
        if (node.leaf()) {
            return typedValue(node);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (NamedNode child : node.children()) {
            Object value = convert(child);
            Object prev = out.get(child.name());
            if (prev == null) {
                out.put(child.name(), value);
            } else if (prev instanceof List<?>) { // repeated names group into lists
                @SuppressWarnings("unchecked")
                List<Object> l = (List<Object>) prev;
                l.add(value);
            } else {
                List<Object> l = new ArrayList<>();
                l.add(prev);
                l.add(value);
                out.put(child.name(), l);
            }
        }
        return out;
    }

    private static Object typedValue(NamedNode node) {
        CompiledType type = node.type();
        BuiltinKind kind = type == null ? null : type.primitive();
        String v = node.value();
        if (kind == BuiltinKind.INTEGER || kind == BuiltinKind.ENUMERATED) {
            try {
                return new BigInteger(v);
            } catch (NumberFormatException e) {
                return v;
            }
        }
        if (kind == BuiltinKind.BOOLEAN) {
            return Boolean.valueOf(v);
        }
        return v;
    }

    private static boolean homogeneous(NamedNode node) {
        String first = node.children().isEmpty() ? null : node.children().getFirst().name();
        return node.children().stream().allMatch(c -> c.name().equals(first));
    }

    private static String decap(String name) {
        if (name.isEmpty() || Character.isLowerCase(name.charAt(0))) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
