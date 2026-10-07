package com.gamma.asn.schema;

import com.gamma.asn.core.BerReader;
import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.core.Tlv;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SchemaBinderTest {

    private static final String GRAMMAR = """
            TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN
            Record ::= [APPLICATION 1] SEQUENCE
            {
              id [0] INTEGER,
              name [1] IA5String OPTIONAL,
              kind [2] Kind,
              msisdn [3] TBCDString OPTIONAL,
              events [4] SEQUENCE OF Event,
              extra [5] EXPLICIT INTEGER OPTIONAL
            }
            Kind ::= ENUMERATED { voice(0), data(1) }
            Event ::= CHOICE { start [6] INTEGER, stop [7] INTEGER }
            TBCDString ::= OCTET STRING
            END
            """;

    private static NamedNode bind(String hex) {
        CompiledSchema schema = SchemaCompiler.compile(Asn1Parser.parse(GRAMMAR), "Record");
        byte[] bytes = HexFormat.of().parseHex(hex.replace(" ", ""));
        ByteSource src = ByteSource.of(bytes);
        Tlv tlv = BerReader.read(src, 0, bytes.length, Strictness.BER);
        return new SchemaBinder(schema, src, DecoderRegistry.withDefaults()).bind(tlv);
    }

    @Test
    void fullRecord() {
        NamedNode rec = bind("61 17 80 01 2A 82 01 01 83 02 21 43"
                + " A4 06 86 01 05 87 01 09 A5 03 02 01 07");
        assertEquals("Record", rec.name());
        assertEquals("1", rec.path());
        assertNull(rec.value());

        assertEquals("42", rec.child("id").value());
        assertEquals("1.0", rec.child("id").path());
        assertNull(rec.child("name")); // optional, absent
        assertEquals("1", rec.child("kind").value()); // raw int, like legacy (names on CompiledType)
        assertEquals("1234", rec.child("msisdn").value()); // TBCD nibble swap
        assertEquals("TBCDString", rec.child("msisdn").typeName());

        NamedNode events = rec.child("events");
        assertEquals(2, events.children().size());
        assertEquals("start", events.children().get(0).name()); // CHOICE by tag
        assertEquals("5", events.children().get(0).value());
        assertEquals("stop", events.children().get(1).name());
        assertEquals("9", events.children().get(1).value());

        NamedNode extra = rec.child("extra"); // EXPLICIT: wrapper + inner INTEGER
        assertEquals("7", extra.children().getFirst().value());
    }

    @Test
    void optionalSkippedByTag() {
        NamedNode rec = bind("61 0A 80 01 2A 81 02 68 69 82 01 00");
        assertEquals("hi", rec.child("name").value());
        assertEquals("0", rec.child("kind").value()); // raw int, like legacy
    }

    @Test
    void unknownTagKeepsTagPathAndHex() { // compatibility contract, REDESIGN.md §3
        NamedNode rec = bind("61 06 80 01 2A 89 01 FE");
        NamedNode unknown = rec.children().get(1);
        assertEquals("1.9", unknown.name());
        assertEquals("1.9", unknown.path());
        assertEquals("FE", unknown.value()); // legacy hex fallback is uppercase
    }

    @Test
    void recordWithWrongRootTagIsUnknownNotDropped() {
        NamedNode rec = bind("62 03 80 01 2A");
        assertEquals("2", rec.path());
        assertEquals(1, rec.children().size());
    }

    @Test
    void automaticTags() {
        String grammar = """
                AUTO DEFINITIONS AUTOMATIC TAGS ::= BEGIN
                Rec ::= SEQUENCE { a INTEGER, b IA5String, c CHOICE { x INTEGER, y IA5String } }
                END
                """;
        CompiledSchema schema = SchemaCompiler.compile(Asn1Parser.parse(grammar), "Rec");
        byte[] bytes = HexFormat.of().parseHex("300C80010581026869A203800109".replace(" ", ""));
        ByteSource src = ByteSource.of(bytes);
        Tlv tlv = BerReader.read(src, 0, bytes.length, Strictness.BER);
        NamedNode rec = new SchemaBinder(schema, src, DecoderRegistry.withDefaults()).bind(tlv);
        assertEquals("5", rec.child("a").value());
        assertEquals("hi", rec.child("b").value());
        // c is an automatic EXPLICIT wrapper (CHOICE keeps no tag of its own);
        // inside it, the CHOICE alternatives are auto-tagged [0]/[1]
        assertEquals("9", rec.child("c").children().getFirst().value());
        assertEquals("x", rec.child("c").children().getFirst().name());
    }

    @Test
    void componentsOfSplicesFields() {
        String grammar = """
                M DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Base ::= SEQUENCE { x [0] INTEGER }
                Rec ::= SEQUENCE { COMPONENTS OF Base, y [1] INTEGER }
                END
                """;
        CompiledSchema schema = SchemaCompiler.compile(Asn1Parser.parse(grammar), "Rec");
        byte[] bytes = HexFormat.of().parseHex("3006800101810102");
        ByteSource src = ByteSource.of(bytes);
        Tlv tlv = BerReader.read(src, 0, bytes.length, Strictness.BER);
        NamedNode rec = new SchemaBinder(schema, src, DecoderRegistry.withDefaults()).bind(tlv);
        assertEquals("1", rec.child("x").value());
        assertEquals("2", rec.child("y").value());
    }

    @Test
    void unionSetRootMatchesRecordByComponentTag() {
        // vendor idiom (aftel IMSRecord): the record union is declared SET but each file
        // record is one tagged alternative — bind by component tag, like the legacy decoder
        String grammar = """
                M DEFINITIONS IMPLICIT TAGS ::= BEGIN
                IMSRecord ::= SET { aRec [67] ARec, bRec [68] BRec }
                ARec ::= SEQUENCE { id [0] INTEGER }
                BRec ::= SEQUENCE { id [1] INTEGER }
                END
                """;
        CompiledSchema schema = SchemaCompiler.compile(Asn1Parser.parse(grammar), "IMSRecord");
        byte[] bytes = HexFormat.of().parseHex("BF430380012A"); // [67] { [0] 42 }
        ByteSource src = ByteSource.of(bytes);
        Tlv tlv = BerReader.read(src, 0, bytes.length, Strictness.BER);
        NamedNode rec = new SchemaBinder(schema, src, DecoderRegistry.withDefaults()).bind(tlv);
        assertEquals("aRec", rec.name());
        assertEquals("42", rec.child("id").value());
    }
}
