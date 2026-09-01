package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@link Finding} diagnostic contract (authoring-residuals R1) through the REAL serializer the
 * control plane responds with ({@link ApiContext#JSON} — the same mapper {@code respondJson} uses),
 * not a fresh ObjectMapper: {@code code} and {@code guidance} are ADDITIVE and OPTIONAL, so an
 * uncoded finding must serialize byte-compatibly with the pre-R1 three-field shape (absent keys,
 * never {@code null} values), and a coded one must carry both.
 */
class FindingJsonShapeTest {

    @Test
    @DisplayName("an uncoded finding serializes with NO code/guidance keys — the pre-R1 envelope is unchanged")
    void uncodedFindingOmitsTheNewFields() throws Exception {
        JsonNode n = ApiContext.JSON.readTree(
                ApiContext.JSON.writeValueAsString(Finding.error("processing.threads", "boom")));
        assertEquals("ERROR", n.get("severity").asText());
        assertEquals("processing.threads", n.get("fieldPath").asText());
        assertEquals("boom", n.get("message").asText());
        assertFalse(n.has("code"), n.toString());
        assertFalse(n.has("guidance"), n.toString());
    }

    @Test
    @DisplayName("a coded finding carries both fields; blank coalesces to absent, not to an empty string")
    void codedFindingCarriesCodeAndGuidance() throws Exception {
        Finding f = Finding.error("active", "no schema")
                .coded(FindingCodes.ERR_ARMED_WITHOUT_SCHEMA, "attach a schema");
        JsonNode n = ApiContext.JSON.readTree(ApiContext.JSON.writeValueAsString(f));
        assertEquals("ERR_ARMED_WITHOUT_SCHEMA", n.get("code").asText());
        assertEquals("attach a schema", n.get("guidance").asText());

        JsonNode blank = ApiContext.JSON.readTree(
                ApiContext.JSON.writeValueAsString(Finding.error("active", "no schema").coded("", " ")));
        assertFalse(blank.has("code"), blank.toString());
        assertFalse(blank.has("guidance"), blank.toString());
    }

    @Test
    @DisplayName("the arming helper emits a coded finding whose message still names the entities and whose guidance is split out")
    void armingHelperFindingsAreCodedAndSplit() throws Exception {
        List<Finding> fs = ConfigRoutes.armedWithoutSchemaFindings("pipeline",
                Map.of("name", "x", "active", true, "processing", Map.of("threads", 1)));
        assertEquals(1, fs.size());
        JsonNode n = ApiContext.JSON.readTree(ApiContext.JSON.writeValueAsString(fs)).get(0);
        assertEquals("ERR_ARMED_WITHOUT_SCHEMA", n.get("code").asText());
        // what is WRONG stays in the message, naming the schema sources checked…
        assertTrue(n.get("message").asText().contains("no schema is configured"), n.toString());
        assertTrue(n.get("message").asText().contains("processing.schema_file"), n.toString());
        // …and what to DO moved to guidance (split, not duplicated).
        assertTrue(n.get("guidance").asText().contains("keep the draft inactive"), n.toString());
        assertFalse(n.get("message").asText().contains("keep the draft inactive"), n.toString());
    }
}
