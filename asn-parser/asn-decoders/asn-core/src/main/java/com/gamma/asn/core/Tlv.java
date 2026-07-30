package com.gamma.asn.core;

import java.util.List;

/**
 * One decoded TLV node. Carries absolute offsets into the {@link ByteSource} so any node
 * can be re-read, dumped, or reported in errors precisely; no value bytes are copied here.
 *
 * @param tagOffset   absolute offset of the first tag byte
 * @param valueOffset absolute offset of the first content byte
 * @param valueLength content length in bytes (for indefinite-length nodes: actual content
 *                    length, excluding the end-of-contents marker)
 * @param endOffset   absolute offset just past the whole encoding (incl. EOC when indefinite)
 * @param children    parsed children for constructed nodes, empty for primitives
 */
public record Tlv(
        TagClass tagClass,
        long tagNumber,
        boolean constructed,
        boolean indefinite,
        long tagOffset,
        long valueOffset,
        long valueLength,
        long endOffset,
        List<Tlv> children) {

    public Tlv {
        children = List.copyOf(children);
    }

    /** Content bytes of this node; use only on leaves or for bounded dumps. */
    public byte[] value(ByteSource src) {
        return src.bytes(valueOffset, Math.toIntExact(valueLength));
    }

    /** e.g. {@code [APPLICATION 3]}, {@code [2]} (context), {@code UNIVERSAL 16}. */
    public String tagString() {
        return switch (tagClass) {
            case UNIVERSAL -> "UNIVERSAL " + tagNumber;
            case APPLICATION -> "[APPLICATION " + tagNumber + "]";
            case CONTEXT -> "[" + tagNumber + "]";
            case PRIVATE -> "[PRIVATE " + tagNumber + "]";
        };
    }
}
