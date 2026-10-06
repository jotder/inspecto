package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.config.io.ConfigCodec;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4a} — removal semantics (plan §2.5) for Jobs: a Job whose Job Type is registered by a
 * module that is not installed. The type {@code zz.absent-module-job} is registered nowhere.
 *
 * <p>Verdicts: listed and readable with every key (PRESERVED); not hosted, so a trigger answers 503 naming the
 * type (was a misleading 404); an enabled save is refused 422 BEFORE any write (was a 500 after the file had been
 * rewritten and the job dropped from memory); a disable is allowed and keeps every key; a sibling's save leaves the
 * file byte-identical.
 */
class ModuleRemovalJobTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private static final String BASE = "/spaces/acme";

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** A Space {@code acme} holding one authored Job of an unregistered type; returns that Job's file. */
    private Path spaceWithGhostJob(Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
        }
        Path jobs = Files.createDirectories(root.resolve("acme").resolve("config").resolve("jobs"));
        Path ghost = jobs.resolve("ghost_job.toon");
        Files.writeString(ghost, """
                job:
                  name: ghost
                  type: zz.absent-module-job
                  enabled: true
                  cron: "0 3 * * *"
                  zz_param: keepme
                """);
        return ghost;
    }

    @Test
    void anAbsentTypeJobIsListedAndReadableWithEveryKey(@TempDir Path root) throws Exception {
        Path ghost = spaceWithGhostJob(root);
        byte[] before = Files.readAllBytes(ghost);
        try (Ctx c = open(root)) {
            JsonNode list = V1Body.of(send(c.port, "GET", BASE + "/jobs", null).body());
            assertEquals("zz.absent-module-job", find(list, "ghost").get("type").asText(), "stays LISTED: " + list);
            JsonNode detail = V1Body.of(send(c.port, "GET", BASE + "/jobs/ghost", null).body());
            assertEquals("keepme", detail.get("zz_param").asText(), "an unmodelled parameter is served: " + detail);
            assertArrayEquals(before, Files.readAllBytes(ghost), "reads never rewrite the file");
        }
    }

    @Test
    void aTriggerNamesTheMissingTypeInsteadOfPretendingTheJobDoesNotExist(@TempDir Path root) throws Exception {
        spaceWithGhostJob(root);
        try (Ctx c = open(root)) {
            HttpResponse<String> r = send(c.port, "POST", BASE + "/jobs/ghost/trigger", null);
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("CAPABILITY_UNAVAILABLE") && r.body().contains("zz.absent-module-job")
                    && r.body().contains("not installed"), r.body());
            assertEquals(404, send(c.port, "POST", BASE + "/jobs/never-existed/trigger", null).statusCode(),
                    "a name nothing configures is still a plain 404");
        }
    }

    @Test
    void anEnabledSaveIsRefusedBeforeAnyWriteAndTheJobStaysListed(@TempDir Path root) throws Exception {
        Path ghost = spaceWithGhostJob(root);
        byte[] before = Files.readAllBytes(ghost);
        try (Ctx c = open(root)) {
            HttpResponse<String> det = send(c.port, "GET", BASE + "/jobs/ghost", null);
            HttpResponse<String> put = send(c.port, "PUT", BASE + "/jobs/ghost", V1Body.of(det.body()).toString());
            assertEquals(422, put.statusCode(), put.body());
            assertTrue(put.body().contains("zz.absent-module-job") && put.body().contains("no installed module"), put.body());
            assertArrayEquals(before, Files.readAllBytes(ghost), "refused BEFORE the write - the file is byte-identical");
            assertEquals("ghost", find(V1Body.of(send(c.port, "GET", BASE + "/jobs", null).body()), "ghost")
                    .get("name").asText(), "the refused save did not drop the job from memory");

            HttpResponse<String> typo = send(c.port, "POST", BASE + "/jobs", "{\"name\":\"typo\",\"type\":\"zz.typo\"}");
            assertEquals(422, typo.statusCode(), typo.body());
            assertFalse(Files.exists(ghost.resolveSibling("typo_job.toon")), "nothing was written for a typo'd type");
        }
    }

    @Test
    void aDisableKeepsEveryKeyAndASiblingSaveLeavesTheFileUntouched(@TempDir Path root) throws Exception {
        Path ghost = spaceWithGhostJob(root);
        byte[] before = Files.readAllBytes(ghost);
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", BASE + "/jobs",
                    "{\"name\":\"sib\",\"type\":\"maintenance\",\"task\":\"cleanup\"}").statusCode());
            assertTrue(Arrays.equals(before, Files.readAllBytes(ghost)), "a sibling's save does not touch the ghost file");

            assertEquals(200, send(c.port, "POST", BASE + "/jobs/ghost/disable", null).statusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) ConfigCodec.toMap(Files.readString(ghost)).get("job");
            assertEquals("keepme", job.get("zz_param"), "the unmodelled parameter survives the rewrite: " + job);
            assertEquals("zz.absent-module-job", job.get("type"));
            assertEquals("0 3 * * *", job.get("cron"));
            assertEquals(false, job.get("enabled"));

            HttpResponse<String> enable = send(c.port, "POST", BASE + "/jobs/ghost/enable", null);
            assertEquals(422, enable.statusCode(), "enabling an unhostable job is refused, loudly: " + enable.body());
        }
    }

    private static JsonNode find(JsonNode list, String name) {
        for (JsonNode j : list) if (name.equals(j.path("name").asText())) return j;
        throw new AssertionError("no job '" + name + "' in " + list);
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
