package com.gamma.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.config.spec.FieldSpec;
import com.gamma.parse.ParseResult;
import com.gamma.parse.ParserPlugin;
import com.gamma.parse.Parsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The served Step Processor catalog vs the committed TS contract ({@code processor-catalog.contract.json})
 * — the same pattern as {@link NodeAttributesContractTest}. Regenerate deliberately with
 * {@code -Dprocessor.catalog.write=true}, then check the TS suite still agrees.
 *
 * <p>The contract describes a FULLY-EQUIPPED install: the optional Telecom ASN.1 module is simulated by a stub
 * {@code asn1} parser registered for the tests that need it (the engine cannot depend on that module — it depends on
 * the engine), so the delivered/addable counts the docs and the UI floors pin stay as they were. The absent state
 * is pinned below, and the real module pins the present state in its own tests.
 */
class ProcessorCatalogContractTest {

    private static final String OWNER = "catalog-contract-test.jar";

    /** A stand-in for the ASN.1 plugin an installed inspecto-telecom-asn1 module registers (id only matters). */
    private static ParserPlugin asn1Stub() {
        return new ParserPlugin() {
            @Override public String id() { return "asn1"; }
            @Override public String label() { return "ASN.1 stub"; }
            @Override public boolean hierarchical() { return true; }
            @Override public List<FieldSpec> grammarSchema() { return List.of(); }
            @Override public ParseResult preview(byte[] sample, Map<String, Object> grammar) { return new ParseResult.Tree(0, List.of()); }
            @Override public Optional<String> ingesterClass() { return Optional.of("com.gamma.telecom.asn1.Asn1RecordIngester"); }
        };
    }

    /** The pack is installed for every test except the ones that deregister it to prove the absent state. */
    @BeforeEach
    void installTheAsn1Pack() { Parsers.register(asn1Stub(), OWNER); }

    @AfterEach
    void removeTheAsn1Pack() { Parsers.deregister(OWNER); }

    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    @Test
    void theServedProcessorCatalogMatchesTheCommittedContract() throws IOException {
        String actual = JSON.writeValueAsString(PipelineProjection.processorCatalog()).replace("\r\n", "\n").trim();
        if (Boolean.getBoolean("processor.catalog.write")) {
            Files.writeString(contractPath(), actual + "\n");
            return;
        }
        String expected = Files.readString(contractPath()).replace("\r\n", "\n").trim();
        assertEquals(expected, actual, "the served processor catalog and " + CONTRACT + " disagree — if the "
                + "Java side is right, regenerate with -Dprocessor.catalog.write=true and check the TS suite still passes");
    }

    /** Every delivered/partial processor that names a node type names a REAL one, and only an authorable one is addable. */
    @Test
    void mappedNodeTypesExistAndOnlyAuthorableOnesAreAddable() {
        Set<String> known = new HashSet<>();
        for (PipelineNodeType t : PipelineNodeTypes.catalog()) known.add(t.type());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) PipelineProjection.processorCatalog().get("processors");
        int addable = 0;
        for (Map<String, Object> m : procs) {
            Object nodeType = m.get("nodeType");
            if (nodeType != null) assertTrue(known.contains(nodeType), m.get("id") + " maps onto unknown node type " + nodeType);
            boolean isAddable = Boolean.TRUE.equals(m.get("addable"));
            if ("planned".equals(m.get("status"))) assertFalse(isAddable, m.get("id") + ": a PLANNED processor can never be addable");
            if (isAddable) { addable++; assertTrue(PipelineEditable.isAuthorable((String) nodeType)); }
        }
        // Floor SET FROM THE MEASUREMENT (35 addable on 2026-09-07), not a round number far below it:
        // `>= 10` let 25 palette entries vanish silently, which is not a ratchet, it is decoration.
        // A floor rather than an equality because addable GROWS as processors are delivered; 30 catches
        // a collapse while leaving room for a deliberate removal or two.
        assertTrue(addable >= 30, "the addable palette collapsed: " + addable + " (35 when this floor was set)");
        assertEquals(ProcessorCatalog.PROCESSORS.size(), procs.size());
    }

    /**
     * PROCESSOR-RELEASE-READINESS-1 G1: `parser.asn1.ber` was mapped as a CAPABILITY, so the palette drew it
     * inactive although `parser.asn1` is an authorable node type. A delivered processor over a Step must
     * name that node type, which is what makes it addable.
     */
    @Test
    void asn1BerDecoderMapsOntoTheAuthorableAsn1NodeType() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) PipelineProjection.processorCatalog().get("processors");
        Map<String, Object> ber = procs.stream().filter(m -> "parser.asn1.ber".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(BuiltinNodeType.PARSER_ASN1.type(), ber.get("nodeType"));
        assertNull(ber.get("capability"));
        assertEquals(Boolean.TRUE, ber.get("addable"), "parser.asn1.ber must be addable from the palette");
    }

    /**
     * CATALOG HONESTY (MODULE-REORG-1 P7): `parser.asn1.ber` is delivered by the optional inspecto-telecom-asn1 module,
     * so with no asn1 parser registered the catalog must NOT claim it. It reads PLANNED (the palette draws it
     * inactive), names the missing module, carries no node type and is not addable.
     */
    @Test
    void withTheTelecomPackAbsentTheAsn1BerDecoderIsNotDeliveredNorAddable() {
        Parsers.deregister(OWNER);
        Map<String, Object> ber = asn1Ber();
        assertEquals("planned", ber.get("status"));
        assertEquals("inspecto-telecom-asn1", ber.get("requires"));
        assertTrue(String.valueOf(ber.get("note")).contains("inspecto-telecom-asn1"), String.valueOf(ber.get("note")));
        assertNull(ber.get("nodeType"));
        assertEquals(Boolean.FALSE, ber.get("addable"));
    }

    /** …and with the parser registered it is the delivered, addable processor it always was, still naming its module. */
    @Test
    void withTheTelecomPackPresentTheAsn1BerDecoderIsDeliveredAndNamesItsModule() {
        Map<String, Object> ber = asn1Ber();
        assertEquals("delivered", ber.get("status"));
        assertEquals("inspecto-telecom-asn1", ber.get("requires"));
    }

    private static Map<String, Object> asn1Ber() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) PipelineProjection.processorCatalog().get("processors");
        return procs.stream().filter(m -> "parser.asn1.ber".equals(m.get("id"))).findFirst().orElseThrow();
    }
}
