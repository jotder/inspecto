package com.gamma.asn.schema;

import com.gamma.asn.schema.ast.BuiltinKind;
import com.gamma.asn.schema.ast.ModuleAst;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiagnosticsAndAliasTest {

    @Test
    void parseLenientDiagnosticsCapturesLineAndColumnOnBrokenAsn1() {
        String broken = """
                BROKEN-MOD DEFINITIONS IMPLICIT TAGS ::= BEGIN
                GoodType ::= INTEGER
                BadAssignment ::= [BADTAG] INTEGER
                BrokenSeq ::= SEQUENCE {
                    ok [0] INTEGER,
                    bad [INVALID] BOOLEAN,
                    tail [1] OCTET STRING
                }
                END
                """;
        List<Diagnostic> diags = new ArrayList<>();
        List<ModuleAst> modules = Asn1Parser.parseLenientDiagnostics(broken, diags);

        assertEquals(1, modules.size());
        ModuleAst mod = modules.getFirst();
        assertTrue(mod.types().containsKey("GoodType"));
        assertTrue(mod.types().containsKey("BrokenSeq"));
        assertFalse(diags.isEmpty(), "expected diagnostics for broken constructs");

        for (Diagnostic d : diags) {
            assertTrue(d.line() > 0, "line should be 1-based, got " + d.line());
            assertTrue(d.column() > 0, "column should be 1-based, got " + d.column());
            assertNotNull(d.severity());
            assertFalse(d.severity().isBlank());
            assertNotNull(d.message());
            assertFalse(d.message().isBlank());
        }
        assertTrue(diags.stream().anyMatch(d -> d.line() == 3),
                "expected diagnostic on line 3 for BadAssignment, got " + diags);
        assertTrue(diags.stream().anyMatch(d -> d.line() == 6),
                "expected diagnostic on line 6 for bad component, got " + diags);
    }

    @Test
    void compileLenientCollapsesAliasChainsWhenSimplifyAliasesIsTrue() {
        String asn = """
                ALIAS-MOD DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    caller [0] CallingNumber,
                    missing [1] UnknownType
                }
                CallingNumber ::= NumberString
                NumberString ::= OCTET STRING
                END
                """;
        List<ModuleAst> modules = Asn1Parser.parse(asn);
        List<Diagnostic> diags = new ArrayList<>();
        CompiledSchema schema = SchemaCompiler.compileLenient(modules, "Root", true, diags);

        CompiledType callingNumber = schema.types().get("CallingNumber");
        assertNotNull(callingNumber);
        assertEquals(CompiledType.Kind.PRIMITIVE, callingNumber.kind());
        assertEquals(BuiltinKind.OCTET_STRING, callingNumber.primitive());
        assertEquals(List.of("CallingNumber", "NumberString"), callingNumber.nameChain(),
                "alias chain should retain intermediate type names for decoder resolution");
        assertFalse(schema.types().containsKey("NumberString"),
                "intermediate alias NumberString should be collapsed out of compiled types");

        assertFalse(diags.isEmpty(), "expected diagnostic for UnknownType");
        assertTrue(diags.stream().anyMatch(d -> "ERROR".equals(d.severity()) && d.message().contains("UnknownType")),
                "expected ERROR unresolved type diagnostic for UnknownType, got " + diags);
    }

    @Test
    void compileLenientPreservesAliasChainsWhenSimplifyAliasesIsFalse() {
        String asn = """
                ALIAS-MOD DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Root ::= SEQUENCE {
                    caller [0] CallingNumber
                }
                CallingNumber ::= NumberString
                NumberString ::= OCTET STRING
                END
                """;
        List<ModuleAst> modules = Asn1Parser.parse(asn);
        List<Diagnostic> diags = new ArrayList<>();
        CompiledSchema schema = SchemaCompiler.compileLenient(modules, "Root", false, diags);

        CompiledType callingNumber = schema.types().get("CallingNumber");
        assertNotNull(callingNumber);
        assertEquals(List.of("CallingNumber", "NumberString"), callingNumber.nameChain());
        assertTrue(schema.types().containsKey("NumberString"));
        assertTrue(diags.isEmpty());
    }

    @Test
    void decoderRegistryListDecodersReturnsMetadataWithSqlTypes() {
        List<DecoderRegistry.DecoderMeta> metas = DecoderRegistry.withDefaults().listDecoders();
        assertFalse(metas.isEmpty());

        Map<String, DecoderRegistry.DecoderMeta> byId = metas.stream()
                .collect(Collectors.toMap(DecoderRegistry.DecoderMeta::id, m -> m));

        assertTrue(byId.containsKey("tbcd"));
        assertEquals("VARCHAR", byId.get("tbcd").sqlType());
        assertFalse(byId.get("tbcd").description().isBlank());

        assertTrue(byId.containsKey("ccnTimeStamp"));
        assertEquals("TIMESTAMP", byId.get("ccnTimeStamp").sqlType());

        assertTrue(byId.containsKey("BOOLEAN"));
        assertEquals("BOOLEAN", byId.get("BOOLEAN").sqlType());

        assertTrue(byId.containsKey("INTEGER"));
        assertEquals("BIGINT", byId.get("INTEGER").sqlType());
    }
}
