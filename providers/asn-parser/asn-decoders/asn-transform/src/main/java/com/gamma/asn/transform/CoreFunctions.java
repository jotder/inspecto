package com.gamma.asn.transform;

import java.util.Map;

/**
 * Generic functions core ships itself (REDESIGN.md §4.5: "Core ships only generic
 * functions — string ops, arithmetic, lookups"). Only the ones the corpus configs
 * actually invoke are ported so far; semantics are the verbatim legacy TransformUtils
 * ones, quirks included (e.g. a null operand of {@code div} becomes 1, and a first
 * operand of an unhandled type is returned unchanged).
 */
final class CoreFunctions {

    private CoreFunctions() {
    }

    static Map<String, FunctionRegistry.TxFunction> functions() {
        return Map.of(
                "add", args -> add(args.get(0), args.get(1)),
                "div", args -> div(args.get(0), args.get(1)));
    }

    private static Object add(Object d1, Object d2) {
        if (d1 == null) d1 = 0;
        if (d2 == null) d2 = 0;
        try {
            if (d1 instanceof Double) return ((Number) d1).doubleValue() + ((Number) d2).doubleValue();
            else if (d1 instanceof Float) return ((Number) d1).floatValue() + ((Number) d2).floatValue();
            else if (d1 instanceof Long) return (double) ((Number) d1).longValue() + ((Number) d2).longValue();
            else if (d1 instanceof Integer) return ((Number) d1).intValue() + ((Number) d2).intValue();
        } catch (Exception ignore) {
        }
        return d1;
    }

    private static Object div(Object d1, Object d2) {
        if (d1 == null) d1 = 1;
        if (d2 == null) d2 = 1;
        if (((Number) d2).intValue() != 0) {
            try {
                if (d1 instanceof Double) return ((Number) d1).doubleValue() / ((Number) d2).doubleValue();
                else if (d1 instanceof Float) return ((Number) d1).floatValue() / ((Number) d2).floatValue();
                else if (d1 instanceof Long) return (double) ((Number) d1).longValue() / ((Number) d2).longValue();
                else if (d1 instanceof Integer) return ((Number) d1).intValue() / ((Number) d2).intValue();
            } catch (Exception ignore) {
            }
        }
        return d1;
    }
}
