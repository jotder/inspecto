package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MODULE-REORG-1 P2a: {@code GET /modules} over real HTTP. A read route — no write-root, spec or conflict gate
 * exists to test (endpoint skill); the 200 itself proves no earlier catch-all shadows it.
 */
class ModulesRoutesTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void reportsTheLiveClassPathModulesWithRolesStateAndNoDiagnostics(@TempDir Path dir) throws Exception {
        CollectorService svc = new CollectorService(
                List.of(TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write()), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        try {
            api.start();
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + api.port() + "/api/v1/modules")).GET().build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = V1Body.of(r.body());
            assertEquals(0, body.get("diagnostics").size(), body.get("diagnostics").toString());
            JsonNode processor = byId(body.get("modules"), "processor");
            assertNotNull(processor, "the host's own manifest must be on the class path: " + body);
            assertEquals("platform", processor.get("buildRole").asText());
            assertEquals("base", processor.get("offeringRole").asText());
            assertEquals("ACTIVE", processor.get("state").asText());
            assertTrue(processor.get("reasons").isArray());
            assertNotNull(byId(body.get("modules"), "util"));
            for (JsonNode m : body.get("modules"))
                assertTrue("ACTIVE".equals(m.get("state").asText()) || "not-installed".equals(m.get("state").asText()),
                        "nothing on the test class path may be inert: " + m);
            JsonNode scoring = byId(body.get("modules"), "scoring");   // optional, and not on the processor's test class path
            assertNotNull(scoring, "a known-but-absent module must be listed: " + body);
            assertEquals("not-installed", scoring.get("state").asText());
            assertEquals("module scoring is not on the class path", scoring.get("reason").asText());
        } finally {
            api.close();
            svc.close();
        }
    }

    @Test
    void anUnsatisfiedModuleIsReportedInertWithTheMissingRequirementNamed() {
        ModuleManifest geo = new ModuleManifest("geo-link", "Geo", "implementation", "optional", "boot",
                ModuleManifest.Provides.NONE, new ModuleManifest.Requires(List.of("la-api"), List.of("identity")), null);
        Map<String, Object> out = ModulesRoutes.build(new ModuleManifests.Loaded(List.of(geo), List.of("bad.toon: nope")));
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) ((List<?>) out.get("modules")).get(0);
        assertEquals("INERT", m.get("state"));
        @SuppressWarnings("unchecked") List<String> reasons = (List<String>) m.get("reasons");
        assertEquals(2, reasons.size(), reasons.toString());
        assertTrue(reasons.get(0).contains("'la-api'") && reasons.get(1).contains("'identity'"), reasons.toString());
        assertEquals(List.of("bad.toon: nope"), out.get("diagnostics"));
    }

    @Test
    void aSwitchedOffModuleListsTheBackgroundWorkThisSpaceHasPaused() {
        ModuleManifest ops = new ModuleManifest("zz-ops", "Ops", "implementation", "optional", "boot",
                new ModuleManifest.Provides(List.of("zzOps"), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of("sla-sweep"), List.of("incident_purge")),
                ModuleManifest.Requires.NONE, null);
        ModuleManifests.Loaded loaded = new ModuleManifests.Loaded(List.of(ops), List.of());
        @SuppressWarnings("unchecked") Map<String, Object> on = (Map<String, Object>)
                ((List<?>) ModulesRoutes.build(loaded, java.util.Set.of()).get("modules")).get(0);
        assertEquals(List.of(), on.get("backgroundPaused"), "enabled: nothing is paused");
        @SuppressWarnings("unchecked") Map<String, Object> off = (Map<String, Object>)
                ((List<?>) ModulesRoutes.build(loaded, java.util.Set.of("zzOps")).get("modules")).get(0);
        assertEquals(List.of("sla-sweep", "incident_purge"), off.get("backgroundPaused"));
        @SuppressWarnings("unchecked") Map<String, Object> provides = (Map<String, Object>) off.get("provides");
        assertEquals(List.of("sla-sweep"), provides.get("background"));
        assertEquals(List.of("incident_purge"), provides.get("maintenanceTasks"));
    }

    @Test
    void aDependantOfAKnownButAbsentModuleGoesInertNamingIt() {
        ModuleManifest scoring = new ModuleManifest("scoring", "Scoring", "implementation", "optional", "boot",
                ModuleManifest.Provides.NONE, new ModuleManifest.Requires(List.of("entity-list"), List.of()), null);
        ModuleManifest entityList = new ModuleManifest("entity-list", "Entity Lists", "implementation", "optional", "boot",
                ModuleManifest.Provides.NONE, ModuleManifest.Requires.NONE, null);
        Map<String, Object> out = ModulesRoutes.build(new ModuleManifests.Loaded(List.of(scoring), List.of()),
                java.util.Set.of(), List.of(scoring, entityList));
        List<?> modules = (List<?>) out.get("modules");
        assertEquals(2, modules.size(), "the installed one plus the absent one: " + modules);
        @SuppressWarnings("unchecked") Map<String, Object> dependant = (Map<String, Object>) modules.get(0);
        assertEquals("INERT", dependant.get("state"));
        assertEquals(List.of("requires module 'entity-list', which is not on the class path"), dependant.get("reasons"));
        @SuppressWarnings("unchecked") Map<String, Object> absent = (Map<String, Object>) modules.get(1);
        assertEquals("entity-list", absent.get("id"));
        assertEquals("not-installed", absent.get("state"));
        assertEquals("module entity-list is not on the class path", absent.get("reason"));
    }

    @Test
    void aModuleWhoseBuildIdDiffersFromTheHostIsInertAndReported() {
        ModuleManifest stale = new ModuleManifest("geo-link", "Geo", "implementation", "optional", "boot",
                ModuleManifest.Provides.NONE, ModuleManifest.Requires.NONE, null, "old111");
        Map<String, Object> out = ModulesRoutes.build(new ModuleManifests.Loaded(List.of(stale), List.of("module 'geo-link': x"), "new222"));
        assertEquals("new222", out.get("hostBuildId"));
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) ((List<?>) out.get("modules")).get(0);
        assertEquals("old111", m.get("buildId"));
        assertEquals("INERT", m.get("state"));
        assertEquals(List.of("build id old111 does not match host new222"), m.get("reasons"));
    }

    private static JsonNode byId(JsonNode modules, String id) {
        List<JsonNode> hit = new ArrayList<>();
        for (JsonNode m : modules) if (id.equals(m.get("id").asText())) hit.add(m);
        return hit.isEmpty() ? null : hit.get(0);
    }
}
