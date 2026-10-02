package com.gamma.la.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The edge mapping an index was built with: which relation columns are the edge source, destination and time, plus
 * optional weight and carried attribute columns. Two indexes with the same mapping hash are interchangeable; a changed
 * mapping is a new index directory (see {@link IndexStore}).
 *
 * @param weightColumn nullable
 */
public record IndexMapping(String srcColumn, String dstColumn, String timeColumn, String weightColumn,
                           List<String> attributeColumns) {

    public IndexMapping {
        Objects.requireNonNull(srcColumn, "srcColumn");
        Objects.requireNonNull(dstColumn, "dstColumn");
        Objects.requireNonNull(timeColumn, "timeColumn");
        attributeColumns = attributeColumns == null ? List.of() : List.copyOf(attributeColumns);
    }

    /** Deterministic: 16 hex chars of SHA-256 over length-prefixed fields (order of attribute columns is significant). */
    public String hash() {
        StringBuilder sb = new StringBuilder();
        field(sb, srcColumn);
        field(sb, dstColumn);
        field(sb, timeColumn);
        field(sb, weightColumn);
        sb.append(attributeColumns.size()).append('|');
        for (String a : attributeColumns) field(sb, a);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(StringBuilder sb, String v) {
        if (v == null) sb.append("-|");
        else sb.append(v.length()).append(':').append(v).append('|');
    }
}
