package com.gamma.asn.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The single tag/length codec plus recursive TLV parsing (REDESIGN.md §4.2).
 * Indefinite length is handled structurally — by descending into children until a true
 * end-of-contents marker — never by byte-scanning for {@code 00 00}.
 */
public final class BerReader {

    private BerReader() {
    }

    /**
     * Default cap on one primitive value's declared length: 64 MiB. A BER length can be perfectly
     * valid and still enormous, and {@link Tlv#value} copies a value into a {@code byte[]}, so without
     * a cap the allocation is sized by the input (BER-VALID-BUT-HUGE-ALLOCATION-1). Measured
     * 2026-09-24 over every shipped sample and the operator corpus (telecom CDRs from six vendors):
     * the largest primitive value is 128 bytes and the largest record ~9 KB, so 64 MiB refuses no real
     * file while bounding the worst case to an allocation any JVM that runs the engine can afford.
     */
    public static final int DEFAULT_MAX_VALUE_BYTES = 64 * 1024 * 1024;

    /** Parses one complete TLV under the {@link #DEFAULT_MAX_VALUE_BYTES default} single-value cap. */
    public static Tlv read(ByteSource src, long offset, long limit, Strictness strictness) {
        return read(src, offset, limit, strictness, DEFAULT_MAX_VALUE_BYTES);
    }

    /**
     * Parses one complete TLV starting at {@code offset}; nothing past {@code limit} is read. A
     * PRIMITIVE value declaring more than {@code maxValueBytes} is refused as a
     * {@link BerParseException} naming its tag, declared length and the cap — at parse time, so no
     * {@code byte[]} is ever sized by it. Constructed values are not capped: their length only bounds
     * where children are parsed, and no decoder copies a constructed value.
     */
    public static Tlv read(ByteSource src, long offset, long limit, Strictness strictness, int maxValueBytes) {
        if (maxValueBytes < 1) {
            throw new IllegalArgumentException("maxValueBytes must be at least 1, got " + maxValueBytes);
        }
        if (limit > src.size()) {
            limit = src.size();
        }
        return readNode(src, offset, limit, strictness, maxValueBytes, 0);
    }

    private static final int MAX_DEPTH = 200;

    private static Tlv readNode(ByteSource src, long offset, long limit, Strictness strictness,
                                int maxValueBytes, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BerParseException(offset, "nesting deeper than " + MAX_DEPTH + " levels");
        }
        long pos = offset;
        if (pos >= limit) {
            throw new BerParseException(pos, "truncated: expected a tag byte");
        }

        // --- tag ---
        int first = src.byteAt(pos++);
        TagClass tagClass = TagClass.fromBits(first >> 6);
        boolean constructed = (first & 0x20) != 0;
        long tagNumber = first & 0x1F;
        if (tagNumber == 0x1F) { // long-form tag: base-128, high bit = continuation
            tagNumber = 0;
            int b;
            do {
                if (pos >= limit) {
                    throw new BerParseException(pos, "truncated inside long-form tag");
                }
                b = src.byteAt(pos++);
                if (tagNumber > (Long.MAX_VALUE >> 7)) {
                    throw new BerParseException(pos - 1, "tag number overflows 63 bits");
                }
                tagNumber = (tagNumber << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
        }

        // --- length ---
        if (pos >= limit) {
            throw new BerParseException(pos, "truncated: expected a length byte");
        }
        int lenByte = src.byteAt(pos++);
        long valueLength;
        boolean indefinite = false;
        if (lenByte == 0x80) {
            if (!strictness.allowIndefinite()) {
                throw new BerParseException(pos - 1, "indefinite length not allowed in DER");
            }
            if (!constructed) {
                throw new BerParseException(pos - 1, "indefinite length on a primitive tag");
            }
            indefinite = true;
            valueLength = -1;
        } else if ((lenByte & 0x80) != 0) {
            int count = lenByte & 0x7F;
            if (count > 8) {
                throw new BerParseException(pos - 1, "length field of " + count + " bytes is unsupported");
            }
            if (pos + count > limit) {
                throw new BerParseException(pos, "truncated inside long-form length");
            }
            long len = 0;
            for (int i = 0; i < count; i++) {
                len = (len << 8) | src.byteAt(pos++);
            }
            if (len < 0) {
                throw new BerParseException(pos - count, "length overflows 63 bits");
            }
            if (strictness.requireMinimalLength()
                    && (src.byteAt(pos - count) == 0 || len < 0x80)) {
                throw new BerParseException(pos - count - 1, "non-minimal length encoding (DER)");
            }
            valueLength = len;
        } else {
            valueLength = lenByte;
        }

        long valueOffset = pos;

        if (strictness.requireIndefiniteConstructed() && constructed && !indefinite) {
            throw new BerParseException(offset, "definite length on a constructed value (CER)");
        }

        if (!constructed) {
            // compared as a remaining-bytes budget, never as valueOffset + valueLength: a declared
            // length near Long.MAX_VALUE makes that sum wrap negative and slip past the guard
            if (valueLength > limit - valueOffset) {
                throw new BerParseException(valueOffset,
                        "value of " + valueLength + " bytes runs past limit " + limit);
            }
            // a length can be in bounds and still enormous — refuse it here, before Tlv.value() sizes
            // an array by it (BER-VALID-BUT-HUGE-ALLOCATION-1)
            if (valueLength > maxValueBytes) {
                throw new BerParseException(valueOffset,
                        "value of " + Tlv.tagString(tagClass, tagNumber) + " declares " + valueLength
                                + " bytes, over the max_value_bytes cap of " + maxValueBytes);
            }
            long end = valueOffset + valueLength;
            return new Tlv(tagClass, tagNumber, false, false, offset, valueOffset, valueLength, end, List.of());
        }

        List<Tlv> children = new ArrayList<>();
        if (indefinite) {
            long cursor = valueOffset;
            while (true) {
                if (cursor + 2 > limit) {
                    throw new BerParseException(cursor, "truncated: no end-of-contents marker");
                }
                if (src.byteAt(cursor) == 0x00 && src.byteAt(cursor + 1) == 0x00) {
                    long contentEnd = cursor;
                    return new Tlv(tagClass, tagNumber, true, true, offset, valueOffset,
                            contentEnd - valueOffset, cursor + 2, children);
                }
                Tlv child = readNode(src, cursor, limit, strictness, maxValueBytes, depth + 1);
                children.add(child);
                cursor = child.endOffset();
            }
        }

        if (valueLength > limit - valueOffset) {
            throw new BerParseException(valueOffset,
                    "constructed value of " + valueLength + " bytes runs past limit " + limit);
        }
        long contentEnd = valueOffset + valueLength;
        long cursor = valueOffset;
        while (cursor < contentEnd) {
            if (src.byteAt(cursor) == 0x00) {
                throw new BerParseException(cursor,
                        "end-of-contents marker inside a definite-length constructed value");
            }
            Tlv child = readNode(src, cursor, contentEnd, strictness, maxValueBytes, depth + 1);
            children.add(child);
            cursor = child.endOffset();
        }
        return new Tlv(tagClass, tagNumber, true, false, offset, valueOffset, valueLength, contentEnd, children);
    }
}
