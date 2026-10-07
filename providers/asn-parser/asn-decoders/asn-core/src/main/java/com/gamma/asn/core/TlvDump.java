package com.gamma.asn.core;

/** Human-readable TLV tree dump with offsets and a bounded hex/ASCII preview per leaf. */
public final class TlvDump {

    private static final int PREVIEW_BYTES = 32;

    private TlvDump() {
    }

    public static String dump(Tlv root, ByteSource src) {
        StringBuilder out = new StringBuilder();
        append(out, root, src, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Tlv node, ByteSource src, int depth) {
        out.append("  ".repeat(depth))
                .append('@').append(node.tagOffset())
                .append(' ').append(node.tagString())
                .append(node.constructed() ? " constructed" : " primitive")
                .append(" len=").append(node.valueLength());
        if (node.indefinite()) {
            out.append(" (indefinite)");
        }
        if (node.constructed()) {
            out.append('\n');
            for (Tlv child : node.children()) {
                append(out, child, src, depth + 1);
            }
        } else {
            int n = (int) Math.min(node.valueLength(), PREVIEW_BYTES);
            byte[] bytes = src.bytes(node.valueOffset(), n);
            out.append(" hex=");
            for (byte b : bytes) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            if (n < node.valueLength()) {
                out.append("…");
            }
            out.append('\n');
        }
    }
}
