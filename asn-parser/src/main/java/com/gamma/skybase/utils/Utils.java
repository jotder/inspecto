package com.gamma.skybase.utils;

import java.util.Map;

public class Utils {
    public static String toPrettyJson(Object obj) {
        return toPrettyJson(obj, 0);
    }

    private static String toPrettyJson(Object obj, int indent) {
        String pad = indent(indent);

        if (obj == null)
            return "null";

        // Primitive / String
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return "\"" + obj.toString() + "\"";
        }

        // Map
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");

            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(indent(indent + 1))
                        .append("\"")
                        .append(e.getKey().toString())
                        .append("\": ")
                        .append(toPrettyJson(e.getValue(), indent + 1));

                if (++i < map.size()) {
                    sb.append(",");
                }
                sb.append("\n");
            }

            sb.append(pad).append("}");
            return sb.toString();
        }

        // List or any Iterable
        if (obj instanceof Iterable) {
            Iterable<?> iterable = (Iterable<?>) obj;
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");

            int count = 0;
            int size = sizeOf(iterable);

            for (Object v : iterable) {
                sb.append(indent(indent + 1))
                        .append(toPrettyJson(v, indent + 1));

                if (++count < size) {
                    sb.append(",");
                }
                sb.append("\n");
            }

            sb.append(pad).append("]");
            return sb.toString();
        }

        // Fallback
        return "\"" + obj.toString() + "\"";
    }

    /** Java-8 replacement for " ".repeat(n) */
    private static String indent(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }

    /** Determine the size of an Iterable (Java 8 safe helper) */
    private static int sizeOf(Iterable<?> it) {
        int size = 0;
        for (Object ignored : it) size++;
        return size;
    }
}
