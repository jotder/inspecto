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
}
