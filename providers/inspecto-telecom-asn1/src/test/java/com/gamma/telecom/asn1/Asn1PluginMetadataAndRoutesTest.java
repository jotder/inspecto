package com.gamma.telecom.asn1;

import com.gamma.config.spec.FieldSpec;
import com.gamma.parse.ParseResult;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Asn1PluginMetadataAndRoutesTest {

    private final Asn1ParserPlugin asn1 = new Asn1ParserPlugin();

    private static final String GRAMMAR = """
            TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN
            Record ::= [APPLICATION 1] SEQUENCE
            {
              id [0] INTEGER,
              name [1] IA5String OPTIONAL,
              events [2] SEQUENCE OF Event
            }
            Event ::= CHOICE { start [3] INTEGER, stop [4] INTEGER }
            END
            """;

    // 61 0C { 80 01 2A, 81 02 68 69, A2 03 { 83 01 05 } } == id=42, name="hi", events=[start=5]
    private static final String RECORD_1_HEX = "61 0C 80 01 2A 81 02 68 69 A2 03 83 01 05";

    // 61 0F { 80 01 2A, 81 02 68 69, A2 03 { 83 01 05 }, 9F 1F 01 AA } == extra unmatched tag [31]
    private static final String RECORD_WITH_UNMATCHED_HEX =
            "61 10 80 01 2A 81 02 68 69 A2 03 83 01 05 9F 1F 01 AA";

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }

    private static Map<String, Object> grammar(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return Map.of("asn1", m);
    }

    @Test
    void grammarSchemaHasNonBlankSectionsAcrossExpectedGroups() {
        List<FieldSpec> schema = asn1.grammarSchema();
        assertFalse(schema.isEmpty());
        for (FieldSpec field : schema) {
            assertNotNull(field.section(), "section should not be null for " + field.path());
            assertFalse(field.section().isBlank(), "section should not be blank for " + field.path());
        }
        Set<String> sections = schema.stream().map(FieldSpec::section).collect(Collectors.toSet());
        assertEquals(Set.of("Grammar & Event Roots", "Header & Framing", "Limits"), sections);
    }

    @Test
    void previewPopulatesNodeRolePathsMatchedAndLeafOnlyDecoderId() throws Exception {
        ParseResult.Tree tree = assertInstanceOf(ParseResult.Tree.class,
                asn1.preview(hex(RECORD_WITH_UNMATCHED_HEX),
                        grammar("grammar", GRAMMAR, "root_type", "Record")));
        assertEquals(1, tree.recordCount());

        ParseResult.Node root = tree.nodes().getFirst();
        assertEquals("container", root.meta().get("nodeRole"));
        assertEquals("Record", root.meta().get("fieldPath"));
        assertEquals("1", root.meta().get("tagPath"));
        assertEquals(true, root.meta().get("matched"));
        assertNull(root.meta().get("decoderId"), "container node must not carry decoderId");

        ParseResult.Node id = root.children().get(0);
        assertEquals("leaf", id.meta().get("nodeRole"));
        assertEquals("Record.id", id.meta().get("fieldPath"));
        assertEquals("1.0", id.meta().get("tagPath"));
        assertEquals(true, id.meta().get("matched"));
        assertEquals("INTEGER", id.meta().get("decoderId"));

        ParseResult.Node name = root.children().get(1);
        assertEquals("leaf", name.meta().get("nodeRole"));
        assertEquals("Record.name", name.meta().get("fieldPath"));
        assertEquals("1.1", name.meta().get("tagPath"));
        assertEquals(true, name.meta().get("matched"));
        assertEquals("IA5_STRING", name.meta().get("decoderId"));

        ParseResult.Node events = root.children().get(2);
        assertEquals("repeated", events.meta().get("nodeRole"));
        assertEquals("Record.events", events.meta().get("fieldPath"));
        assertEquals("1.2", events.meta().get("tagPath"));
        assertEquals(true, events.meta().get("matched"));
        assertNull(events.meta().get("decoderId"), "repeated node must not carry decoderId");

        ParseResult.Node start = events.children().getFirst();
        assertEquals("leaf", start.meta().get("nodeRole"));
        assertEquals("Record.events.start", start.meta().get("fieldPath"));
        assertEquals("1.2.3", start.meta().get("tagPath"));
        assertEquals(true, start.meta().get("matched"));
        assertEquals("INTEGER", start.meta().get("decoderId"));

        ParseResult.Node unmatched = root.children().get(3);
        assertEquals("leaf", unmatched.meta().get("nodeRole"));
        assertEquals("1.31", unmatched.meta().get("tagPath"));
        assertEquals(false, unmatched.meta().get("matched"));
        assertNull(unmatched.meta().get("decoderId"), "unmatched fallback node must not carry resolved decoderId");
    }

    @Test
    void previewWithSimplifyAliasesCollapsesAliasChains() throws Exception {
        String aliasGrammar = """
                ALIAS-TEST DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Record ::= [APPLICATION 1] SEQUENCE {
                    id [0] CallingId,
                    name [1] DisplayName OPTIONAL,
                    events [2] SEQUENCE OF Event
                }
                CallingId ::= AliasInt
                AliasInt ::= INTEGER
                DisplayName ::= IA5String
                Event ::= CHOICE { start [3] INTEGER, stop [4] INTEGER }
                END
                """;
        ParseResult.Tree tree = assertInstanceOf(ParseResult.Tree.class,
                asn1.preview(hex(RECORD_1_HEX),
                        grammar("grammar", aliasGrammar, "root_type", "Record", "simplify_aliases", true)));
        assertEquals(1, tree.recordCount());
        ParseResult.Node id = tree.nodes().getFirst().children().getFirst();
        assertEquals("42", id.value());
        assertEquals("CallingId", id.type());
        assertEquals("INTEGER", id.meta().get("decoderId"));
    }

    @Test
    void decodersAndValidateGrammarExposeCatalogAndStructuredDiagnostics() {
        List<Map<String, Object>> decoders = asn1.decoders();
        assertFalse(decoders.isEmpty());
        assertTrue(decoders.stream().anyMatch(d -> "INTEGER".equals(d.get("id")) && "BIGINT".equals(d.get("sqlType"))));
        assertTrue(decoders.stream().anyMatch(d -> "tbcd".equals(d.get("id")) && "VARCHAR".equals(d.get("sqlType"))));

        Map<String, Object> validResult = asn1.validateGrammar(GRAMMAR, "Record", true);
        assertEquals(true, validResult.get("valid"));
        assertTrue(((List<?>) validResult.get("diagnostics")).isEmpty());

        String broken = """
                BROKEN DEFINITIONS IMPLICIT TAGS ::= BEGIN
                Bad ::= [BADTAG] INTEGER
                END
                """;
        Map<String, Object> brokenResult = asn1.validateGrammar(broken, "Bad", true);
        assertFalse(((List<?>) brokenResult.get("diagnostics")).isEmpty());
    }
}
