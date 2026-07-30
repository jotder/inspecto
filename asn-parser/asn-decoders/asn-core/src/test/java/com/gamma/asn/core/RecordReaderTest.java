package com.gamma.asn.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordReaderTest {

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }

    @Test
    void bareTlvRecordsBackToBack() {
        RecordReader r = new RecordReader(ByteSource.of(hex("02 01 01 02 01 02 02 01 03")), Framing.none());
        List<Tlv> records = new ArrayList<>();
        r.forEachRemaining(records::add);
        assertEquals(3, records.size());
        assertEquals(3, r.recordsOk());
        assertEquals(0, r.recordsFailed());
    }

    @Test
    void fileHeaderPaddingAndTrailer() {
        // 4-byte file header, 0xFF padding between records, 2-byte trailer
        byte[] data = hex("DE AD BE EF 02 01 01 FF FF 02 01 02 FF CA FE");
        Framing framing = Framing.of(new Framing.FramingSpec(4, 2, Set.of(0xFF), null));
        RecordReader r = new RecordReader(ByteSource.of(data), framing);
        List<Tlv> records = new ArrayList<>();
        r.forEachRemaining(records::add);
        assertEquals(2, records.size());
        assertEquals(3, r.bytesSkipped());
    }

    @Test
    void lengthPrefixedRecordHeader() {
        // header: 2-byte big-endian length (excluding header) + the TLV
        byte[] data = hex("00 03 02 01 05 00 03 02 01 06");
        Framing framing = Framing.of(new Framing.FramingSpec(0, 0, Set.of(),
                new Framing.RecordHeaderSpec(2, 0, 2, true, false)));
        RecordReader r = new RecordReader(ByteSource.of(data), framing);
        List<Tlv> records = new ArrayList<>();
        r.forEachRemaining(records::add);
        assertEquals(2, records.size());
        assertEquals(5, records.getFirst().value(ByteSource.of(data))[0]);
    }

    @Test
    void skipOnlyRecordHeader() {
        // 4 opaque header bytes before each record, records delimited by their own BER length
        byte[] data = hex("AA BB CC DD 02 01 05 AA BB CC DD 02 01 06");
        Framing framing = Framing.of(new Framing.FramingSpec(0, 0, Set.of(),
                Framing.RecordHeaderSpec.skipOnly(4)));
        RecordReader r = new RecordReader(ByteSource.of(data), framing);
        List<Tlv> records = new ArrayList<>();
        r.forEachRemaining(records::add);
        assertEquals(2, records.size());
        assertEquals(6, records.get(1).value(ByteSource.of(data))[0]);
    }

    @Test
    void corruptRecordSkippedWhenBoundaryKnown() {
        // second record's TLV is garbage but the header gives the boundary
        byte[] data = hex("00 03 02 01 05 00 03 C9 FF FF 00 03 02 01 06");
        Framing framing = Framing.of(new Framing.FramingSpec(0, 0, Set.of(),
                new Framing.RecordHeaderSpec(2, 0, 2, true, false)));
        List<ParseError> errors = new ArrayList<>();
        RecordReader r = new RecordReader(ByteSource.of(data), framing, Strictness.BER,
                RecoveryPolicy.SKIP_RECORD, errors::add);
        List<Tlv> records = new ArrayList<>();
        r.forEachRemaining(records::add);
        assertEquals(2, records.size());
        assertEquals(1, r.recordsFailed());
        assertEquals(1, errors.size());
        assertEquals(RecoveryPolicy.SKIP_RECORD, errors.getFirst().action());
        assertEquals(1, errors.getFirst().recordIndex());
    }

    @Test
    void corruptBareTlvStopsFileEvenWithSkipPolicy() {
        byte[] data = hex("02 01 05 C9 FF 02 01 06");
        List<ParseError> errors = new ArrayList<>();
        RecordReader r = new RecordReader(ByteSource.of(data), Framing.none(), Strictness.BER,
                RecoveryPolicy.SKIP_RECORD, errors::add);
        List<Tlv> records = new ArrayList<>();
        r.forEachRemaining(records::add);
        assertEquals(1, records.size());
        assertEquals(RecoveryPolicy.STOP_FILE, errors.getFirst().action());
        assertFalse(r.hasNext());
    }

    @Test
    void allPaddingFileYieldsNothing() {
        Framing framing = Framing.of(new Framing.FramingSpec(0, 0, Set.of(0xFF), null));
        RecordReader r = new RecordReader(ByteSource.of(hex("FF FF FF FF")), framing);
        assertFalse(r.hasNext());
        assertEquals(4, r.bytesSkipped());
    }

    @Test
    void headerDeclaringLengthPastEndFails() {
        byte[] data = hex("00 63 02 01 05");
        Framing framing = Framing.of(new Framing.FramingSpec(0, 0, Set.of(),
                new Framing.RecordHeaderSpec(2, 0, 2, true, false)));
        List<ParseError> errors = new ArrayList<>();
        RecordReader r = new RecordReader(ByteSource.of(data), framing, Strictness.BER,
                RecoveryPolicy.SKIP_RECORD, errors::add);
        assertFalse(r.hasNext());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().message().contains("past end"));
    }
}
