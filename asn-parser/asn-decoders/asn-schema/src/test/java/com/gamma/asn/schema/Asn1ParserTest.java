package com.gamma.asn.schema;

import com.gamma.asn.schema.ast.ModuleAst;
import com.gamma.asn.schema.ast.TypeAst;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Asn1ParserTest {

    @Test
    void moduleHeaderAndTagDefault() {
        ModuleAst m = Asn1Parser.parse("""
                TAP-0312 DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                Age ::= INTEGER
                END
                """).getFirst();
        assertEquals("TAP-0312", m.name());
        assertEquals(ModuleAst.TagDefault.IMPLICIT_TAGS, m.tagDefault());
        assertTrue(m.types().containsKey("Age"));
    }

    @Test
    void trailingCommasTolerated() { // the hand-doctored files in config/ have these
        ModuleAst m = Asn1Parser.parse("""
                M DEFINITIONS IMPLICIT TAGS ::= BEGIN
                T ::= [1] SEQUENCE
                {
                a A ,
                b B ,
                }
                END
                """).getFirst();
        TypeAst.Tagged tagged = (TypeAst.Tagged) m.types().get("T");
        TypeAst.SequenceType seq = (TypeAst.SequenceType) tagged.inner();
        assertEquals(2, seq.components().size());
    }

    @Test
    void commentsBothStyles() {
        ModuleAst m = Asn1Parser.parse("""
                M DEFINITIONS ::= BEGIN
                -- line comment
                T ::= SEQUENCE { -- inline -- a INTEGER /* block
                   spanning */ , b BOOLEAN }
                END
                """).getFirst();
        TypeAst.SequenceType seq = (TypeAst.SequenceType) m.types().get("T");
        assertEquals(2, seq.components().size());
    }

    @Test
    void sizeConstraintAndSequenceOf() {
        ModuleAst m = Asn1Parser.parse("""
                M DEFINITIONS ::= BEGIN
                L ::= SEQUENCE (SIZE(1..10)) OF INTEGER
                S ::= OCTET STRING (SIZE(2))
                END
                """).getFirst();
        TypeAst.Constrained list = (TypeAst.Constrained) m.types().get("L");
        assertInstanceOf(TypeAst.SequenceOf.class, list.inner());
        TypeAst.Constrained str = (TypeAst.Constrained) m.types().get("S");
        assertTrue(str.constraintText().contains("SIZE"));
    }

    @Test
    void optionalDefaultAndComponentsOf() {
        ModuleAst m = Asn1Parser.parse("""
                M DEFINITIONS ::= BEGIN
                Base ::= SEQUENCE { x INTEGER }
                T ::= SEQUENCE {
                  COMPONENTS OF Base,
                  a INTEGER OPTIONAL,
                  b INTEGER DEFAULT -5,
                  ...
                }
                END
                """).getFirst();
        TypeAst.SequenceType seq = (TypeAst.SequenceType) m.types().get("T");
        assertEquals(3, seq.components().size());
        assertTrue(seq.components().getFirst().componentsOf());
        assertTrue(seq.components().get(1).optional());
        assertEquals("-5", seq.components().get(2).defaultValue());
    }

    @Test
    void importsResolvedByName() {
        ModuleAst m = Asn1Parser.parse("""
                M DEFINITIONS ::= BEGIN
                IMPORTS Foo, Bar FROM Other-Module;
                T ::= Foo
                END
                """).getFirst();
        assertEquals("Other-Module", m.imports().get("Foo"));
        assertEquals("Other-Module", m.imports().get("Bar"));
    }

    @Test
    void enumeratedNamedValues() {
        ModuleAst m = Asn1Parser.parse("""
                M DEFINITIONS ::= BEGIN
                K ::= ENUMERATED { voice(0), data(1), sms }
                END
                """).getFirst();
        TypeAst.Enumerated e = (TypeAst.Enumerated) m.types().get("K");
        assertEquals(2L, e.namedValues().get("sms"));
    }

    @Test
    void vendorDialectTolerated() {
        // Huawei grammars (huwIMS/2980-gmsc/huwSgsn): no module name, unterminated
        // "EXPORTS everything", underscores in identifiers, no END
        var warnings = new java.util.ArrayList<String>();
        ModuleAst m = Asn1Parser.parseLenient("""
                DEFINITIONS IMPLICIT TAGS ::=
                BEGIN
                EXPORTS everything
                T ::= SEQUENCE { input_called_number [1] INTEGER OPTIONAL }
                """, warnings).getFirst();
        TypeAst.SequenceType seq = (TypeAst.SequenceType) m.types().get("T");
        assertEquals("input_called_number", seq.components().getFirst().name());
        assertEquals(ModuleAst.TagDefault.IMPLICIT_TAGS, m.tagDefault());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("missing END")), warnings.toString());
    }

    @Test
    void componentRecoveryStaysInsideTheList() {
        // the failed parse consumes the '[' before throwing; recovery must not let the
        // orphaned ']' unbalance it and eat the rest of the module (mtnOCC line 933)
        var warnings = new java.util.ArrayList<String>();
        ModuleAst m = Asn1Parser.parseLenient("""
                M DEFINITIONS ::= BEGIN
                A ::= SEQUENCE {
                  x [20] Bar,
                  [21] INTEGER,
                  z [22] INTEGER
                }
                B ::= INTEGER
                END
                """, warnings).getFirst();
        TypeAst.SequenceType a = (TypeAst.SequenceType) m.types().get("A");
        assertEquals(java.util.List.of("x", "z"),
                a.components().stream().map(c -> c.name()).toList());
        assertTrue(m.types().containsKey("B"));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void garbageFailsWithLocation() {
        Asn1ParseException e = assertThrows(Asn1ParseException.class, () -> Asn1Parser.parse("""
                M DEFINITIONS ::= BEGIN
                T ::= SEQUENCE { a }
                END
                """));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }
}
