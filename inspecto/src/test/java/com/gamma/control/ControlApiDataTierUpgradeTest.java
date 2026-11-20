package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.BasicProfile;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DATA-TIERS-1} P7, the upgrade path (DT-2): a person adds a file on <b>Basic</b> - the import runs on its hidden
 * one-step Pipeline - and the same Space is then served by a <b>Standard</b> server, where that import is an ordinary
 * Pipeline: registered under its own name, read and edited through the authoring routes, its Dataset and rows still there.
 * The two boots share the write root and the data directory, as an in-place upgrade of a bundle does; the second boot is
 * given the import's {@code *_pipeline.toon} as the boot scan would find it on disk.
 *
 * <p>Falsified: with the import's {@code *_pipeline.toon} left out of the Standard boot (the import not carried over), the
 * read and the patch answer 404 and the test fails; with the {@code /bootstrap} screen flags removed, the per-boot edition
 * check fails. (DT-9 narrowed: Basic would accept the same edit, so the edition difference visible here is the screens'.)
 */
class ControlApiDataTierUpgradeTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        EditionFeatures.overrideForTest(null);
        OnboardImport.followOnsForTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx boot(List<Path> pipelines, Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(pipelines, 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static void assertScreens(JsonNode boot, boolean standard) {
        for (String flag : BasicProfile.SCREEN_FLAGS.keySet()) {
            assertEquals(standard, boot.at("/features/" + flag).asBoolean(!standard), flag + ": " + boot.get("features"));
            assertEquals(!standard, boot.at("/featureReasons").has(flag), flag + ": " + boot.get("featureReasons"));
        }
    }

    @Test
    void aBasicImportIsAnOrdinaryEditablePipelineOnStandard(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Path sample = PipelineConfigBatchTest.writePipeline(cfg, "");
        String id = "upgrade_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);

        // ── Basic: Add data ────────────────────────────────────────────────────────────────────────────
        EditionFeatures.overrideForTest(Set.of());
        try (Ctx basic = boot(List.of(sample), root)) {
            assertScreens(data(send(basic, "GET", "/bootstrap", null), 200), false);
            byte[] csv = ControlApiOnboardFilesTest.transfers(40).getBytes(StandardCharsets.UTF_8);
            HttpRequest up = HttpRequest.newBuilder(URI.create("http://localhost:" + basic.port + "/api/v1/onboard/files?name=transfers.csv&kind=stream"))
                    .POST(BodyPublishers.ofByteArray(csv)).build();
            JsonNode draft = data(client.send(up, BodyHandlers.ofString()), 200);
            JsonNode s = data(send(basic, "POST", "/onboard/files/" + draft.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);
            assertEquals(40, s.get("rowsIn").asLong(), s.toString());
            JsonNode pipe = data(send(basic, "GET", "/config/pipeline/" + id, null), 200);
            assertEquals("import", pipe.get("config").get("origin").asText(), "the hidden one-step Pipeline (DT-2)");
        }
        Path importToon = root.resolve("imports").resolve(id + "_pipeline.toon");
        assertTrue(Files.isRegularFile(importToon), "the import's Pipeline is on disk for the next boot");

        // ── Standard on the same Space: the import is an ordinary Pipeline ──────────────────────────────────
        EditionFeatures.overrideForTest(Set.copyOf(BasicProfile.SCREEN_FLAGS.values()));
        try (Ctx standard = boot(List.of(sample, importToon), root)) {
            assertScreens(data(send(standard, "GET", "/bootstrap", null), 200), true);

            JsonNode pipe = data(send(standard, "GET", "/config/pipeline/" + id, null), 200);
            assertEquals(id, pipe.get("name").asText());
            assertEquals("import", pipe.get("config").get("origin").asText(), "still known as the import it came from");

            // edited through the authoring route every Pipeline uses
            HttpResponse<String> patch = send(standard, "POST", "/config/patch",
                    "{\"type\":\"pipeline\",\"name\":\"" + id + "\",\"patch\":{\"description\":\"edited after the upgrade\"}}");
            assertEquals(200, patch.statusCode(), patch.body());
            JsonNode after = data(send(standard, "GET", "/config/pipeline/" + id, null), 200);
            assertEquals("edited after the upgrade", after.get("config").get("description").asText(), after.toString());
            assertTrue(Files.readString(importToon).contains("edited after the upgrade"), "the edit lands in the import's own file");

            // its Dataset and rows came through the upgrade
            JsonNode ds = data(send(standard, "GET", "/components/dataset/" + id, null), 200).get("content");
            assertEquals("import", ds.get("origin").asText());
            JsonNode rows = data(send(standard, "GET", "/datasets/" + id + "/rows?limit=5", null), 200);
            assertTrue(rows.toString().contains("ACC-"), rows.toString());
        }
    }
}
