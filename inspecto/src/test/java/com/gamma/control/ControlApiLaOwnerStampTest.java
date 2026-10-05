package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.job.JobConfig;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T5 owner-spoofing: the SAVE path stamps an {@code la.index.build} Job's {@code owner} from the saving Subject unless
 * they hold {@code canConfigureAccess}. Real HTTP, armed Authenticator.
 */
class ControlApiLaOwnerStampTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path wr) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer builder" -> {
                ComponentAccess.heldRoles(ex, Set.of("developer"));
                yield Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench", "canOperateRuns")));
            }
            case "Bearer admin" -> {
                ComponentAccess.heldRoles(ex, Set.of("admin"));
                yield Optional.of(new Subject("admin-1", Set.of("canAuthorWorkbench", "canOperateRuns", "canAdminister")));
            }
            case "Bearer other" -> {
                ComponentAccess.heldRoles(ex, Set.of("developer"));
                yield Optional.of(new Subject("other-2", Set.of("canAuthorWorkbench", "canOperateRuns")));
            }
            case "Bearer access" -> {
                ComponentAccess.heldRoles(ex, Set.of("admin"));
                yield Optional.of(new Subject("access-1", Set.of("canAuthorWorkbench", "canOperateRuns", "canConfigureAccess")));
            }
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty("jobs.audit.dir");
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path wr = Files.createDirectories(dir.resolve("wr"));
        System.setProperty("assist.write.root", wr.toString());
        // the flat SpaceRoot's run log is CWD-relative "jobs_audit" — keep it in the @TempDir
        System.setProperty("jobs.audit.dir", dir.resolve("jobs_audit").toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), wr);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String job(String name, String owner, String extra) {
        return "{\"name\":\"" + name + "\",\"type\":\"la.index.build\",\"dataset\":\"d\",\"source_col\":\"a\","
                + "\"target_col\":\"b\",\"owner\":\"" + owner + "\"" + extra + "}";
    }

    private HttpResponse<String> put(Ctx c, String name, String body, String auth) throws Exception {
        String etag = send(c, "GET", "/jobs/" + name, "", "Bearer admin").headers().firstValue("ETag").orElse("*");
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/jobs/" + name))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("If-Match", etag).PUT(BodyPublishers.ofString(body)).build();
        return client.send(r, BodyHandlers.ofString());
    }

    private static String owner(Ctx c, String name) {
        return c.svc().jobServiceOrCreate().jobConfig(name).orElseThrow().params().get("owner");
    }

    @Test
    void ordinarySaverTypedForeignOwnerIsOverwritten(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c, "POST", "/jobs", job("ix", "victim", ""), "Bearer builder");
            assertTrue(r.statusCode() < 300, r.body());
            assertTrue(r.body().contains("builder-1") && !r.body().contains("victim"), "returned config shows the stamp: " + r.body());
            assertEquals("builder-1", owner(c, "ix"));
            assertTrue(Files.readString(c.wr().resolve("jobs/ix_job.toon")).contains("builder-1"));
        }
    }

    @Test
    void configureAccessSaverTypedOwnerIsHonoured(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertTrue(send(c, "POST", "/jobs", job("ix", "analyst-9", ""), "Bearer access").statusCode() < 300);
            assertEquals("analyst-9", owner(c, "ix"));
        }
    }

    @Test
    void sameOwnerUpdateKeepsAndOtherUserUpdateRestamps(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertTrue(send(c, "POST", "/jobs", job("ix", "x", ""), "Bearer builder").statusCode() < 300);
            HttpResponse<String> same = put(c, "ix", job("ix", "victim", ",\"allow_full\":\"true\""), "Bearer builder");
            assertTrue(same.statusCode() < 300, same.body());
            assertEquals("builder-1", owner(c, "ix"));
            assertEquals("true", c.svc().jobServiceOrCreate().jobConfig("ix").orElseThrow().params().get("allow_full"));

            HttpResponse<String> other = put(c, "ix", job("ix", "builder-1", ""), "Bearer other");
            assertTrue(other.statusCode() < 300, other.body());
            assertEquals("other-2", owner(c, "ix"));
        }
    }

    @Test
    void otherJobTypesAreUntouched(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = "{\"name\":\"tidy\",\"type\":\"maintenance\",\"task\":\"noop\",\"owner\":\"keepme\"}";
            assertTrue(send(c, "POST", "/jobs", body, "Bearer builder").statusCode() < 300);
            assertEquals("keepme", owner(c, "tidy"));
        }
    }

    @Test
    void oddlyCasedTypeStillStampedViaConfigWriteAndPatch(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            int i = 0;
            for (String t : new String[] {"LA.INDEX.BUILD", " la.index.build "}) {
                String n = "cw" + i++;
                String body = "{\"type\":\"job\",\"config\":{\"job\":" + job(n, "victim", "").replace("\"la.index.build\"", "\"" + t + "\"") + "}}";
                HttpResponse<String> w = send(c, "POST", "/config/write", body, "Bearer builder");
                assertTrue(w.statusCode() < 300, w.body());
                Path f;
                try (var walk = Files.walk(c.wr())) {
                    f = walk.filter(p -> p.getFileName().toString().startsWith(n)).findFirst().orElseThrow(() -> new AssertionError(w.body()));
                }
                String disk = Files.readString(f);
                assertTrue(disk.contains("builder-1") && !disk.contains("victim"), "write: " + disk);

                // patch by another user with a spoofed owner and an odd type
                String patch = "{\"type\":\"job\",\"name\":\"" + n + "\",\"patch\":{\"job\":{\"type\":\"" + t
                        + "\",\"owner\":\"victim\"}}}";
                HttpResponse<String> p = send(c, "POST", "/config/patch", patch, "Bearer other");
                assertTrue(p.statusCode() < 300, p.body());
                disk = Files.readString(f);
                assertTrue(disk.contains("other-2") && !disk.contains("victim"), "patch: " + disk);
            }
        }
    }

    private static final String Q = String.valueOf((char) 34);

    /** the run-time ladder (args / bind) could override the stamped owner: they are stripped of `owner` on save. */
    @Test
    void argsAndBindOwnerAreStrippedOnSave(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = job("ab", "victim", ",QargsQ:{QownerQ:QvictimQ,QxQ:Q1Q},QbindQ:{QownerQ:Q$signal.uQ}".replace("Q", Q));
            HttpResponse<String> w = send(c, "POST", "/config/write", ("{QtypeQ:QjobQ,QconfigQ:{QjobQ:" + body + "}}").replace("Q", Q), "Bearer builder");
            assertTrue(w.statusCode() < 300, w.body());
            Path f;
            try (var walk = Files.walk(c.wr())) {
                f = walk.filter(x -> x.getFileName().toString().startsWith("ab")).findFirst().orElseThrow(() -> new AssertionError(w.body()));
            }
            String disk = Files.readString(f);
            assertTrue(disk.contains("builder-1") && !disk.contains("victim"), disk);
            assertTrue(disk.contains("x"), disk);
        }
    }
}
