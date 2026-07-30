package com.gamma.asn.core;

import java.util.Set;

/**
 * Declarative per-format record layout: file header, per-record length-prefix headers,
 * inter-record padding, trailer (REDESIGN.md §4.2). SPI seam for exotic vendor layouts.
 */
public interface Framing {

    String name();

    /** Bytes to skip at the start of the file. */
    long fileHeaderLength(ByteSource src);

    /** Bytes reserved at the end of the file. */
    long trailerLength(ByteSource src);

    /** True for filler bytes tolerated between records (e.g. 0xFF pads). */
    boolean isPadding(int unsignedByte);

    /** Header bytes preceding the TLV payload of the record at {@code recordStart}; 0 = none. */
    int recordHeaderLength(ByteSource src, long recordStart);

    /**
     * Total record length including its header, or -1 when the record has no length prefix
     * and is delimited by its own BER length.
     */
    long recordLength(ByteSource src, long recordStart);

    /** Bare back-to-back TLVs: no headers, no padding. */
    static Framing none() {
        return new Fixed(new FramingSpec(0, 0, Set.of(), null));
    }

    static Framing of(FramingSpec spec) {
        return new Fixed(spec);
    }

    /**
     * @param recordHeader null when records are bare TLVs
     */
    record FramingSpec(long fileHeaderLength,
                       long trailerLength,
                       Set<Integer> paddingBytes,
                       RecordHeaderSpec recordHeader) {
    }

    /**
     * A fixed-size record header carrying a big- or little-endian record length.
     * {@code lengthSize == 0} means the header carries no usable length: it is skipped
     * and the record stays delimited by its own BER length (the legacy
     * {@code RECORD_HEADER_LENGTH} behaviour).
     *
     * @param lengthIncludesHeader whether the encoded length counts the header bytes too
     */
    record RecordHeaderSpec(int headerLength,
                            int lengthOffset,
                            int lengthSize,
                            boolean bigEndian,
                            boolean lengthIncludesHeader) {

        /** Header bytes to skip before each record; no length field. */
        public static RecordHeaderSpec skipOnly(int headerLength) {
            return new RecordHeaderSpec(headerLength, 0, 0, true, false);
        }
    }

    final class Fixed implements Framing {

        private final FramingSpec spec;

        private Fixed(FramingSpec spec) {
            this.spec = spec;
        }

        @Override
        public String name() {
            return spec.recordHeader() == null ? "fixed" : "length-prefixed";
        }

        @Override
        public long fileHeaderLength(ByteSource src) {
            return spec.fileHeaderLength();
        }

        @Override
        public long trailerLength(ByteSource src) {
            return spec.trailerLength();
        }

        @Override
        public boolean isPadding(int unsignedByte) {
            return spec.paddingBytes().contains(unsignedByte);
        }

        @Override
        public int recordHeaderLength(ByteSource src, long recordStart) {
            RecordHeaderSpec h = spec.recordHeader();
            return h == null ? 0 : h.headerLength();
        }

        @Override
        public long recordLength(ByteSource src, long recordStart) {
            RecordHeaderSpec h = spec.recordHeader();
            if (h == null || h.lengthSize() == 0) {
                return -1;
            }
            long len = 0;
            for (int i = 0; i < h.lengthSize(); i++) {
                int idx = h.bigEndian() ? i : h.lengthSize() - 1 - i;
                len = (len << 8) | src.byteAt(recordStart + h.lengthOffset() + idx);
            }
            return h.lengthIncludesHeader() ? len : len + h.headerLength();
        }
    }
}
