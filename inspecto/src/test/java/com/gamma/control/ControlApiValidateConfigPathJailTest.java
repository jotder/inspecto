package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code VALIDATE-CONFIGPATH-UNJAILED-1} over real HTTP: {@code POST /validate {configPath}} must jail the
 * caller-named file under the allowed roots ({@link com.gamma.config.safety.PathJail#allowedRoots()}, the
 * same roots the load already enforces on the config's own schema refs) BEFORE touching the filesystem.
 * Until 2026-09-23 it read any server path: a missing file answered differently from a present one (an
 * existence oracle) and a present non-config file's decode refusal came back in the 422 body.
 */
class ControlApiValidateConfigPathJailTest {

    private static final String SECRET = "hunter2-do-not-leak"; // secret-allow: fake canary the test asserts never leaks
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir Path cfg;       // the only allowed root for this test
    @TempDir Path outside;   // NOT an allowed root

    private String priorRoots;
    private CollectorService svc;
    private ControlApi api;

    @BeforeEach
    void boot() throws Exception {
        priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", cfg.toString());
        svc = new CollectorService(List.of(PipelineConfigBatchTest.writePipeline(cfg, "")), 3600, 1);
        api = new ControlApi(svc, 0);
        api.start();
    }

    @AfterEach
    void close() {
        api.close();
        svc.close();
        // RESTORE, never clear — surefire supplies a baseline the rest of the JVM depends on.
        if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
        else System.clearProperty("assist.safety.roots");
    }

    private HttpResponse<String> validate(String configPath) throws Exception {
        String body = "{\"configPath\":\"" + configPath.replace('\\', '/') + "\"}";
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/validate"))
                .POST(BodyPublishers.ofString(body)).build();
        return client.send(r, BodyHandlers.ofString());
    }

    @Test
    void anAbsolutePathOutsideTheAllowedRootsIs403AndLeaksNothing() throws Exception {
        Path secret = Files.writeString(outside.resolve("secret.toon"), "password: " + SECRET + "\n  bad: [\n");
        HttpResponse<String> r = validate(secret.toString());
        assertEquals(403, r.statusCode(), r.body());
        assertFalse(r.body().contains(SECRET), "file content must not leak: " + r.body());
    }

    @Test
    void aMissingFileOutsideTheRootsAnswersExactlyLikeAPresentOne() throws Exception {
        Files.writeString(outside.resolve("present.toon"), "x: 1\n");
        HttpResponse<String> present = validate(outside.resolve("present.toon").toString());
        HttpResponse<String> missing = validate(outside.resolve("missing.toon").toString());
        assertEquals(403, present.statusCode(), present.body());
        assertEquals(403, missing.statusCode(), "no existence oracle: " + missing.body());
    }

    @Test
    void aDotDotEscapeFromAnAllowedRootIs403() throws Exception {
        Path secret = Files.writeString(outside.resolve("secret.toon"), "password: " + SECRET + "\n");
        String escape = cfg + "/../" + outside.getFileName() + "/secret.toon";
        HttpResponse<String> r = validate(escape);
        assertEquals(403, r.statusCode(), r.body());
        assertFalse(r.body().contains(SECRET), r.body());
    }

    @Test
    void aRelativePathWithNoWriteRootIsRefusedNotReadFromTheWorkingDirectory() throws Exception {
        HttpResponse<String> r = validate("../../etc/passwd");
        assertEquals(400, r.statusCode(), r.body());
    }

    @Test
    void aConfigUnderTheAllowedRootsStillValidates() throws Exception {
        Path toon = svc.pathFor("mini_etl").orElseThrow();
        HttpResponse<String> r = validate(toon.toString());
        assertEquals(200, r.statusCode(), r.body());
    }

    /**
     * {@code VALIDATE-PREPARE-WRITES-STATUS-DIR-1}: CapabilityManifest exempts {@code /validate} as
     * read-shaped ("writes nothing"). Until 2026-09-23 it ran {@code PipelineConfig.load}, whose
     * {@code prepare()} CREATED the Pipeline's status directory. The whole tree under the root must be
     * byte-for-byte the same listing before and after.
     */
    @Test
    void validatingAConfigWhoseStatusDirDoesNotExistWritesNothing() throws Exception {
        Path fresh = Files.createDirectories(cfg.resolve("fresh"));
        Path toon = PipelineConfigBatchTest.writePipeline(fresh, "", false);
        assertFalse(Files.exists(fresh.resolve("status")), "precondition: status dir absent");
        List<String> before = tree(cfg);
        HttpResponse<String> r = validate(toon.toString());
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(before, tree(cfg), "POST /validate must not touch the filesystem");
    }

    private static List<String> tree(Path root) throws Exception {
        try (var s = Files.walk(root)) {
            return s.map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }

    @Test
    void aMissingFileInsideTheRootsIs404() throws Exception {
        HttpResponse<String> r = validate(cfg.resolve("nope.toon").toString());
        assertEquals(404, r.statusCode(), r.body());
    }
}
