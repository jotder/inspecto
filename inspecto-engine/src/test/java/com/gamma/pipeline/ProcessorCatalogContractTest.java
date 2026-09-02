package com.gamma.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The served Step Processor catalog vs the committed TS contract ({@code processor-catalog.contract.json})
 * — the same pattern as {@link StepTypesContractTest}. Regenerate deliberately with
 * {@code -Dprocessor.catalog.write=true}, then check the TS suite still agrees.
 */
class ProcessorCatalogContractTest {

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
        assertTrue(addable >= 10, "the delivered core (collector, parsers, map, filter, route, dedup, join, summarize, sink) is addable: " + addable);
        assertEquals(ProcessorCatalog.PROCESSORS.size(), procs.size());
    }
}
