package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.BasicProfile;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code DATA-TIERS-1} screen gates over real HTTP (P2, P4, P5, P6): what a Basic and a Standard server tell the screens.
 * {@code /bootstrap} reports {@code features.pipelineAutomation / pipelineTransform / pipelineEgress / remoteSystems} and, for each
 * withheld one, a {@code featureReasons} line under the same key; {@code GET /jobs/types} names {@code pipelineEgress} on the two
 * outbound-delivery types; {@code GET /pipelines/processor-catalog} names {@code pipelineTransform} on the Steps beyond no-code
 * shaping and {@code remoteSystems} on the remote acquisitions. The gates are in the screens only (plan §5.3): the same answers
 * on both editions apart from the flags, nothing refused.
 *
 * <p>Falsified: with the {@code SCREEN_FLAGS} line removed from {@code BootstrapRoutes.features} the Basic flags read absent
 * (MissingNode), not false, and {@link #basicReportsEveryScreenGateFalseWithItsReason} fails; with the {@code featureFlag} put
 * removed from {@code JobService.jobTypeView} or {@code ProcessorCatalog.asMap} the other assertions fail.
 */
class ControlApiDataTierScreenGatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FLAGS = List.of("pipelineAutomation", "pipelineTransform", "pipelineEgress", "remoteSystems");
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
    }

    private interface Body {
        void run(int port) throws Exception;
    }

    private void serve(Path cfg, Path root, Body body) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc;
        ControlApi api;
        try {
            // one disabled maintenance Job, so the Job routes (and GET /jobs/types) are served
            JobConfig hb = new JobConfig("hb", JobType.MAINTENANCE, null, null, false, false, Map.of("task", "heartbeat"));
            svc = new CollectorService(List.of(pipe), List.of(), List.of(hb), 3600L, 1, null);
            api = new ControlApi(svc, 0);
            api.start();
        } finally {
            System.clearProperty("assist.write.root");
        }
        try {
            body.run(api.port());
        } finally {
            api.close();
            svc.close();
        }
    }

    private JsonNode get(int port, String path) throws Exception {
        var r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    /** {@code featureFlag} by id over a list of {@code {id, featureFlag?}} objects. */
    private static Map<String, String> flagsById(JsonNode list) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode n : list) out.put(n.get("id").asText(), n.path("featureFlag").isMissingNode() ? null : n.get("featureFlag").asText());
        return out;
    }

    @Test
    void basicReportsEveryScreenGateFalseWithItsReason(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no module declares them
        serve(cfg, root, port -> {
            JsonNode boot = get(port, "/bootstrap");
            for (String flag : FLAGS) {
                assertTrue(boot.at("/features/" + flag).isBoolean(), flag + " is reported: " + boot.get("features"));
                assertFalse(boot.at("/features/" + flag).asBoolean(), flag + " is withheld on Basic");
                String why = boot.at("/featureReasons/" + flag).asText();
                assertTrue(why.contains("Standard and above"), flag + " says which edition has it: " + why);
                assertEquals(EditionFeatures.lockedReason(BasicProfile.SCREEN_FLAGS.get(flag)), why);
            }
            assertTrue(boot.at("/featureReasons/remoteSystems").asText().contains("copies Postgres tables"),
                    "Basic keeps files and the Postgres snapshot: " + boot.get("featureReasons"));

            // P5: the two outbound-delivery Job types name their gate; the others name none
            Map<String, String> jobTypes = flagsById(get(port, "/jobs/types"));
            assertEquals("pipelineEgress", jobTypes.get("objectstore.export"), jobTypes.toString());
            assertEquals("pipelineEgress", jobTypes.get("publish.postgres"), jobTypes.toString());
            long gated = jobTypes.values().stream().filter(v -> v != null).count();
            assertEquals(2, gated, "only the egress types are gated: " + jobTypes);

            // P4 / P6: the catalog names the remote acquisitions and the Steps beyond no-code shaping
            Map<String, String> procs = flagsById(get(port, "/pipelines/processor-catalog").get("processors"));
            assertEquals("remoteSystems", procs.get("acquisition.file.sftp"));
            assertEquals("remoteSystems", procs.get("acquisition.db.jdbc"));
            assertNull(procs.get("acquisition.file.local"), "a folder on this machine is every edition's");
            // BASIC-ELT-PROFILE-1 BE-4: the postgres connector is listed, and is the one remote acquisition Basic keeps
            assertTrue(procs.containsKey("acquisition.db.postgres"), "the catalog lists the postgres connector: " + procs.keySet());
            assertNull(procs.get("acquisition.db.postgres"), "Add data's Choose a table reads through it on Basic");
            assertEquals("pipelineTransform", procs.get("transform.join"));
            assertEquals("pipelineTransform", procs.get("transform.record"), "the SQL Step");
            assertEquals("pipelineTransform", procs.get("enrichment.reference"));
            assertNull(procs.get("transform.filter"), "filter rows is no-code shaping (DT-7)");
            assertNull(procs.get("quality.dedup.exact"), "dedupe stays on Basic (DT-15)");
            assertNull(procs.get("quality.dedup.file"), "the Collector's duplicate check is Basic's (BASIC-ELT-PROFILE-1 Gate, 2026-11-20)");
            assertNull(procs.get("quality.pii.mask"), "masking keeps its own gate (Advanced)");
            assertNull(procs.get("parser.delimited"), "parsing is every edition's");
        });
    }

    @Test
    void standardWithholdsNoScreenGateAndServesTheSameMarkers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.copyOf(BasicProfile.SCREEN_FLAGS.values()));   // what inspecto-ops declares
        serve(cfg, root, port -> {
            JsonNode boot = get(port, "/bootstrap");
            for (String flag : FLAGS) {
                assertTrue(boot.at("/features/" + flag).asBoolean(), flag + " is offered on Standard: " + boot.get("features"));
                assertFalse(boot.at("/featureReasons").has(flag), flag + " has no lock reason: " + boot.get("featureReasons"));
            }
            // the markers are static: the screens decide with the flags, so Standard serves them too
            assertEquals("pipelineEgress", flagsById(get(port, "/jobs/types")).get("objectstore.export"));
            assertEquals("pipelineTransform", flagsById(get(port, "/pipelines/processor-catalog").get("processors")).get("transform.join"));
        });
    }
}
