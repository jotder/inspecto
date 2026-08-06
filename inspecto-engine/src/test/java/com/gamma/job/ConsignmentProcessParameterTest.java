package com.gamma.job;

import com.gamma.consignment.ConsignmentProcessJobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §14.4 step 3's acceptance criterion, at the layer that actually decides it: <b>"a third-party processor gets
 * the right Consignment id from a {@code pipeline.commit} Signal without declaring anything about signals."</b>
 *
 * <p>Lives in {@code com.gamma.job} because {@link ParameterResolver} is package-private — this drives the real
 * resolver over the real descriptor, rather than asserting the {@code deduce} string looks right.
 */
class ConsignmentProcessParameterTest {

    /** The payload shape {@code JobService.mirrorPipelineCommit} publishes for every pipeline.commit. */
    private static final ExpressionRegistry EXPR = ExpressionRegistry.withBuiltins();

    private static Map<String, Object> commitPayload(String batchId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", "TEST_ETL");
        payload.put("batchId", batchId);
        payload.put("status", "SUCCESS");
        payload.put("rows", 42);
        return payload;
    }

    private static ExpressionContext ctx(Map<String, Object> signalPayload) {
        return new ExpressionContext("run-1", Instant.parse("2026-08-04T10:00:00Z"), "tester",
                ZoneId.of("UTC"), Optional::empty, (job, artifact) -> Optional.empty(), signalPayload);
    }

    private static List<ParameterDecl> decls() {
        return new ConsignmentProcessJobType().descriptor().parameters();
    }

    private static ParameterResolver.Resolution resolve(Map<String, Object> payload, Map<String, String> config) {
        return ParameterResolver.resolve(decls(), Map.of(), Map.of(), config, EXPR, ctx(payload));
    }

    @Test
    void deducesTheConsignmentIdFromTheCommitSignalPayload() {
        ParameterResolver.Resolution r =
                resolve(commitPayload("20260804_mini_0001"), Map.of("processor", "row-counter"));

        assertEquals(List.of(), r.missingRequired(), "nothing may be left unresolved");
        assertEquals(List.of(), r.invalidType());
        assertEquals("20260804_mini_0001", r.resolved().get("consignment_id"),
                "the id comes from $signal.batchId — the author's processor never mentions signals");
        assertEquals("row-counter", r.resolved().get("processor"));
    }

    /** A manual/cron fire has no signal, so the id must be bindable from config instead. */
    @Test
    void acceptsAnExplicitlyBoundConsignmentIdWhenThereIsNoSignal() {
        ParameterResolver.Resolution r = resolve(Map.of(),
                Map.of("consignment_id", "20260804_manual_0007", "processor", "row-counter"));

        assertEquals(List.of(), r.missingRequired());
        assertEquals("20260804_manual_0007", r.resolved().get("consignment_id"));
    }

    /**
     * Neither a signal nor a binding ⇒ the Run is REJECTED before any author code runs, which is why the
     * parameter is declared {@code required} rather than defaulted.
     */
    @Test
    void reportsTheConsignmentIdMissingWhenNeitherSignalNorConfigSuppliesIt() {
        ParameterResolver.Resolution r = resolve(Map.of(), Map.of("processor", "row-counter"));

        assertTrue(r.missingRequired().contains("consignment_id"), r.missingRequired().toString());
        assertFalse(r.resolved().containsKey("consignment_id"));
    }

    @Test
    void reportsTheProcessorIdMissingWhenUnbound() {
        ParameterResolver.Resolution r = resolve(commitPayload("b1"), Map.of());
        assertTrue(r.missingRequired().contains("processor"), r.missingRequired().toString());
    }

    /** A commit payload that carries no batchId must not silently resolve to something else. */
    @Test
    void doesNotInventAnIdWhenTheSignalCarriesNoBatchId() {
        Map<String, Object> payload = new LinkedHashMap<>(commitPayload("ignored"));
        payload.remove("batchId");

        ParameterResolver.Resolution r = resolve(payload, Map.of("processor", "row-counter"));
        assertTrue(r.missingRequired().contains("consignment_id"), r.missingRequired().toString());
    }
}
