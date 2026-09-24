package com.gamma.asn.core;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BerReaderTest {

    private static Tlv parse(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex.replace(" ", ""));
        return BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.BER);
    }

    @Test
    void primitiveShortForm() {
        Tlv t = parse("02 01 2A"); // INTEGER 42
        assertEquals(TagClass.UNIVERSAL, t.tagClass());
        assertEquals(2, t.tagNumber());
        assertFalse(t.constructed());
        assertEquals(2, t.valueOffset());
        assertEquals(1, t.valueLength());
        assertEquals(3, t.endOffset());
    }

    @Test
    void longFormTag() {
        // context constructed tag 417 = 0xBF 0x83 0x21, empty value
        Tlv t = parse("BF 83 21 00");
        assertEquals(TagClass.CONTEXT, t.tagClass());
        assertEquals(417, t.tagNumber());
        assertTrue(t.constructed());
        assertEquals(0, t.valueLength());
    }

    @Test
    void longFormLength() {
        StringBuilder sb = new StringBuilder("04 82 01 00"); // OCTET STRING, 256 bytes
        sb.append(" AB".repeat(256));
        Tlv t = parse(sb.toString());
        assertEquals(256, t.valueLength());
        assertEquals(4 + 256, t.endOffset());
    }

    @Test
    void nestedConstructed() {
        // SEQUENCE { INTEGER 1, SEQUENCE { OCTET STRING "hi" } }
        Tlv t = parse("30 09 02 01 01 30 04 04 02 68 69");
        assertEquals(2, t.children().size());
        Tlv inner = t.children().get(1);
        assertTrue(inner.constructed());
        assertEquals(1, inner.children().size());
        assertEquals(2, inner.children().getFirst().valueLength());
    }

    @Test
    void indefiniteLengthStructural() {
        // SEQUENCE (indefinite) { INTEGER 1, SEQUENCE (indefinite) { INTEGER 2 } }
        Tlv t = parse("30 80 02 01 01 30 80 02 01 02 00 00 00 00");
        assertTrue(t.indefinite());
        assertEquals(2, t.children().size());
        Tlv inner = t.children().get(1);
        assertTrue(inner.indefinite());
        assertEquals(1, inner.children().size());
        assertEquals(14, t.endOffset());
    }

    @Test
    void indefiniteNotFooledByZerosInsidePrimitive() {
        // the classic corruption case: a primitive value containing 00 00 inside an
        // indefinite-length parent must not be mistaken for end-of-contents
        Tlv t = parse("30 80 04 04 00 00 00 00 02 01 07 00 00");
        assertEquals(2, t.children().size());
        assertEquals(4, t.children().getFirst().valueLength());
        assertEquals(7, t.children().get(1).value(ByteSource.of(new byte[]{
                0x30, (byte) 0x80, 4, 4, 0, 0, 0, 0, 2, 1, 7, 0, 0}))[0]);
    }

    @Test
    void indefiniteOnPrimitiveRejected() {
        assertThrows(BerParseException.class, () -> parse("04 80 00 00"));
    }

    @Test
    void derRejectsIndefinite() {
        byte[] bytes = HexFormat.of().parseHex("308002010100".replace(" ", "") + "00");
        assertThrows(BerParseException.class,
                () -> BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.DER));
    }

    @Test
    void derRejectsNonMinimalLength() {
        byte[] bytes = HexFormat.of().parseHex("0281012A"); // length 42 in long form
        assertThrows(BerParseException.class,
                () -> BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.DER));
    }

    @Test
    void cerRejectsDefiniteConstructed() {
        byte[] bytes = HexFormat.of().parseHex("3003020101"); // SEQUENCE (definite) { INTEGER 1 }
        assertThrows(BerParseException.class,
                () -> BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.CER));
    }

    @Test
    void cerAcceptsIndefiniteConstructedRequiresMinimalPrimitiveLength() {
        byte[] ok = HexFormat.of().parseHex("30 80 02 01 01 00 00".replace(" ", ""));
        Tlv t = BerReader.read(ByteSource.of(ok), 0, ok.length, Strictness.CER);
        assertTrue(t.indefinite());
        assertEquals(1, t.children().size());
        // primitive length 0x81 0x01 is non-minimal — CER shares DER's minimal-length rule
        byte[] bad = HexFormat.of().parseHex("30 80 02 81 01 01 00 00".replace(" ", ""));
        assertThrows(BerParseException.class,
                () -> BerReader.read(ByteSource.of(bad), 0, bad.length, Strictness.CER));
    }

    @Test
    void truncationReportsOffset() {
        BerParseException e = assertThrows(BerParseException.class, () -> parse("30 05 02 01"));
        assertTrue(e.offset() >= 2, "offset was " + e.offset());
    }

    @Test
    void valueRunningPastLimitRejected() {
        assertThrows(BerParseException.class, () -> parse("04 05 01 02"));
    }

    @Test
    void eocInsideDefiniteConstructedRejected() {
        assertThrows(BerParseException.class, () -> parse("30 04 00 00 00 00"));
    }

    @Test
    void primitiveLengthNearLongMaxRejectedInsteadOfWrappingPastTheGuard() {
        // 0x88 = long-form length, 8 bytes; 7F FF.. = Long.MAX_VALUE. valueOffset + valueLength
        // wraps negative, so an `end > limit` guard would let this through with a negative endOffset.
        assertThrows(BerParseException.class, () -> parse("02 88 7F FF FF FF FF FF FF FF"));
    }

    @Test
    void constructedLengthNearLongMaxRejectedInsteadOfWrappingPastTheGuard() {
        assertThrows(BerParseException.class, () -> parse("22 88 7F FF FF FF FF FF FF FF"));
    }

    @Test
    void anAcceptedNodeNeverReportsOffsetsOutsideTheInput() {
        // the load-bearing shape assertion: whatever a length claims, a returned Tlv must describe a
        // slice that really lies inside the input. A wrapped end is negative and would fail here.
        byte[] bytes = HexFormat.of().parseHex("0288" + "7FFFFFFFFFFFFFFF");
        try {
            Tlv t = BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.BER);
            assertTrue(t.endOffset() >= t.valueOffset() && t.endOffset() <= bytes.length,
                    "endOffset " + t.endOffset() + " is outside the input");
            assertTrue(t.valueLength() >= 0 && t.valueLength() <= bytes.length,
                    "valueLength " + t.valueLength() + " is outside the input");
        } catch (BerParseException expected) {
            // rejecting it is the other allowed outcome
        }
    }

    // ---- BER-VALID-BUT-HUGE-ALLOCATION-1: a VALID length over the single-value cap ----------

    /**
     * A {@link ByteSource} reporting {@code size} bytes: {@code head} at offset 0, zeros after.
     * Any value copy fails the test — the whole point is that the cap refuses before one happens.
     */
    private static ByteSource huge(long size, String headHex) {
        byte[] head = HexFormat.of().parseHex(headHex.replace(" ", ""));
        return new ByteSource() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public int byteAt(long offset) {
                return offset < head.length ? head[(int) offset] & 0xFF : 0;
            }

            @Override
            public void copyTo(long offset, byte[] dst, int dstOffset, int length) {
                throw new AssertionError("value bytes were copied (" + length + " bytes)");
            }

            @Override
            public byte[] bytes(long offset, int length) {
                throw new AssertionError("a " + length + "-byte value array was allocated");
            }
        };
    }

    @Test
    void validLengthOverTheDefaultCapRefusedAtParseNotAtAllocation() {
        // the row's measured case: 04 84 95 02 F9 00 = OCTET STRING of 2,500,000,000 bytes, genuinely
        // inside a 3 GB source — every bounds check passes, so only a cap can say no
        ByteSource src = huge(3_000_000_000L, "04 84 95 02 F9 00");
        BerParseException e = assertThrows(BerParseException.class,
                () -> BerReader.read(src, 0, src.size(), Strictness.BER));
        assertTrue(e.getMessage().contains("UNIVERSAL 4"), e.getMessage());
        assertTrue(e.getMessage().contains("2500000000"), e.getMessage());
        assertTrue(e.getMessage().contains("67108864"), e.getMessage());
    }

    @Test
    void validLengthJustUnderTwoGibRefusedInsteadOfAllocated() {
        // 0x71000000 = 1,895,825,408 bytes: fits an int, so before the cap Tlv.value() would have
        // allocated a byte[] of that size from attacker-chosen input
        ByteSource src = huge(2_000_000_000L, "04 84 71 00 00 00");
        assertThrows(BerParseException.class, () -> BerReader.read(src, 0, src.size(), Strictness.BER));
    }

    private static Tlv parseCapped(String hex, int maxValueBytes) {
        byte[] bytes = HexFormat.of().parseHex(hex.replace(" ", ""));
        return BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.BER, maxValueBytes);
    }

    @Test
    void valueOneByteOverAConfiguredCapRefusedNamingTagLengthAndCap() {
        // [APPLICATION 3] primitive, 5 bytes, all present — valid, and one byte over a cap of 4
        BerParseException e = assertThrows(BerParseException.class,
                () -> parseCapped("43 05 01 02 03 04 05", 4));
        assertEquals("value of [APPLICATION 3] declares 5 bytes, over the max_value_bytes cap of 4 (at offset 2)",
                e.getMessage());
        assertEquals(2, e.offset());
    }

    @Test
    void valueExactlyAtTheCapDecodes() {
        byte[] bytes = HexFormat.of().parseHex("0404CAFEBABE");
        Tlv t = BerReader.read(ByteSource.of(bytes), 0, bytes.length, Strictness.BER, 4);
        assertEquals(4, t.valueLength());
        assertEquals(4, t.value(ByteSource.of(bytes)).length);
    }

    @Test
    void capIsPerPrimitiveValueNotPerConstructedRecord() {
        // SEQUENCE of 9 content bytes holding three 1-byte INTEGERs: the record is over a cap of 2,
        // each value is under it — nothing copies a constructed value, so only values are capped
        Tlv t = parseCapped("30 09 02 01 01 02 01 02 02 01 03", 2);
        assertEquals(3, t.children().size());
        assertThrows(BerParseException.class, () -> parseCapped("30 05 04 03 01 02 03", 2),
                "a nested primitive over the cap fails the whole TLV");
    }

    @Test
    void nonPositiveCapRejected() {
        assertThrows(IllegalArgumentException.class, () -> parseCapped("02 01 2A", 0));
    }
}
