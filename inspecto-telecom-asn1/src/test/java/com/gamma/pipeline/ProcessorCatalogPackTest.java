package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With THIS module on the classpath the Step Processor catalog's {@code parser.asn1.ber} is the delivered, addable
 * processor over the {@code parser.asn1} node type (the engine's ProcessorCatalogContractTest pins the absent state).
 */
class ProcessorCatalogPackTest {

    @Test
    void theRealPluginMakesTheAsn1BerDecoderDeliveredAndAddable() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) PipelineProjection.processorCatalog().get("processors");
        Map<String, Object> ber = procs.stream().filter(m -> "parser.asn1.ber".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals("delivered", ber.get("status"));
        assertEquals(BuiltinNodeType.PARSER_ASN1.type(), ber.get("nodeType"));
        assertEquals(Boolean.TRUE, ber.get("addable"));
        assertEquals("inspecto-telecom-asn1", ber.get("requires"));
    }
}
