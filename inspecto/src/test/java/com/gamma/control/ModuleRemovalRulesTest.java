package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4a} - removal semantics (plan section 2.5) for Decision Rules and Alert Rules.
 *
 * <p>A Decision Rule whose consequence names an action no installed module provides: PRESERVED through create,
 * update and simulate (every key, the consequence's own config included); {@code apply} reports it
 * {@code skipped} naming the action and the missing module, and executes nothing.
 *
 * <p>An Alert Rule has no module-contributed action type, but its write rebuilds the stored file from the
 * modelled {@code AlertRule} - so a key it does not model is DROPPED, silently, on a 200. That is the section 9 trap
 * ("silent loss on save") in its pure form and is pinned here as today's behaviour; it needs a policy call (refuse
 * like a Pipeline, or preserve like a Decision Rule) and is filed as MODULE-REORG-P4-2.
 */
class ModuleRemovalRulesTest {

    @BeforeEach void professionalBuild() {
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
    }
    @AfterEach void restoreEdition() { com.gamma.etl.EditionFeatures.overrideForTest(null); }

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path wr) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", wr.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static final String GHOST_RULE = "{\"name\":\"ghost_rule\",\"targetType\":\"pipeline\",\"target\":\"orders\","
            + "\"consequences\":[{\"action\":\"zz-absent-action\",\"destination\":\"d\",\"zz_cfg\":{\"k\":\"v\"}}],"
            + "\"zz_top\":\"t\",\"when\":{\"kind\":\"group\",\"op\":\"AND\",\"items\":["
            + "{\"kind\":\"condition\",\"field\":\"cost\",\"operator\":\">\",\"value\":\"5\"}]}}";

    @Test
    void aDecisionRuleNamingAnAbsentActionKeepsEveryKeyThroughSaveAndSimulate(@TempDir Path cfg, @TempDir Path wr)
            throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEquals(200, send(c.port, "POST", "/decision-rules", GHOST_RULE).statusCode());
            JsonNode listed = V1Body.of(send(c.port, "GET", "/decision-rules", null).body()).get(0);
            assertEquals(200, send(c.port, "PUT", "/decision-rules/ghost_rule", listed.toString()).statusCode());
            assertEquals(200, send(c.port, "POST", "/decision-rules/ghost_rule/simulate",
                    "{\"sampleRows\":[{\"cost\":\"9\"}]}").statusCode());

            Map<String, Object> stored = ToonHelper.load(wr.resolve("registry/decision-rules/ghost_rule.toon").toString());
            assertEquals("t", stored.get("zz_top"), "an unmodelled top-level key survives every save: " + stored.keySet());
            Map<?, ?> consequence = (Map<?, ?>) ((List<?>) stored.get("consequences")).get(0);
            assertEquals("zz-absent-action", consequence.get("action"));
            assertEquals(Map.of("k", "v"), consequence.get("zz_cfg"), "the consequence's own config is untouched");
        }
    }

    @Test
    void applyingAnAbsentActionIsSkippedNamingTheActionAndTheModule(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEquals(200, send(c.port, "POST", "/decision-rules", GHOST_RULE).statusCode());
            HttpResponse<String> r = send(c.port, "POST", "/decision-rules/ghost_rule/apply",
                    "{\"sampleRows\":[{\"cost\":\"9\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode executed = V1Body.of(r.body()).get("executed").get(0);
            assertEquals("skipped", executed.get("status").asText());
            String detail = executed.get("detail").asText();
            assertTrue(detail.contains("zz-absent-action") && detail.contains("no installed module"), detail);
        }
    }

    @Test
    void anUnmodelledKeyOnAnAlertRuleIsDroppedBySaveWithA200_pinnedUntilP4Policy(@TempDir Path cfg, @TempDir Path wr)
            throws Exception {
        try (Ctx c = open(cfg, wr)) {
            HttpResponse<String> r = send(c.port, "POST", "/alerts/rules", "{\"name\":\"plain-alert\","
                    + "\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.1,\"window\":\"1h\","
                    + "\"severity\":\"WARNING\",\"zz_cfg\":\"keep\"}");
            assertEquals(200, r.statusCode(), r.body());
            Map<String, Object> stored = ToonHelper.load(wr.resolve("registry/alert-rules/plain-alert.toon").toString());
            assertFalse(stored.containsKey("zz_cfg"),
                    "TODAY the write rebuilds from AlertRule.toMap and drops what it does not model "
                            + "(MODULE-REORG-P4-2) - if this fails the policy landed: flip the assertion");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
