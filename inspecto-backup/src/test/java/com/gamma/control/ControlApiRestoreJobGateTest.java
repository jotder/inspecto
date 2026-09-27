package com.gamma.control;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MAINT-RESTORE-ESCALATION-1: a {@code restore} maintenance Job writes an archive's files into any jailed
 * directory, and its only integrity check is a sidecar manifest that carries its OWN hash — so whoever can plant a
 * zip + sidecar can make one "valid". Restored over the config root with {@code overwrite: true}, it rewrote
 * {@code roles.toon}: a {@code power} user (canAuthorWorkbench + canOperateRuns, no canAdminister) granted
 * themselves {@code super}. Authoring one now needs {@code canAdminister} at every door, case-insensitively.
 *
 * <p>Lives in inspecto-backup because this is the only module whose classpath holds the {@code restore} task, so
 * the administrator case below really RUNS the restore over HTTP — the probe that proves the refused author would
 * otherwise have succeeded. Real HTTP, armed Subjects.
 */
class ControlApiRestoreJobGateTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer power" -> Optional.of(new Subject("power-1", Set.of("canAuthorWorkbench", "canOperateRuns")));
            case "Bearer admin" -> Optional.of(new Subject("admin-1",
                    Set.of("canAuthorWorkbench", "canOperateRuns", "canAdminister")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        if (priorRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", priorRoots);   // surefire's, which BackupTaskTest relies on
    }

    private String priorRoots;

    private Ctx open(Path dir) throws Exception {
        priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toAbsolutePath().toString());
        System.setProperty("assist.write.root", Files.createDirectories(dir.resolve("wr")).toString());
        try {
            CollectorService svc = new CollectorService(List.of(), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        return client.send(b.method(method, BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** The planted payload: a zip holding a forged roles.toon, plus the self-hashed sidecar restore trusts. */
    private static Path plantArchive(Path dropDir) throws Exception {
        Files.createDirectories(dropDir);
        byte[] forged = "roles:\n  power:\n    capabilities[1]: canAdminister\n".getBytes(StandardCharsets.UTF_8);
        Path zip = dropDir.resolve("drop.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("roles.toon"));
            z.write(forged);
            z.closeEntry();
        }
        String sidecar = "{\"archiveSha256\":\"" + sha(Files.readAllBytes(zip)) + "\",\"totalBytes\":" + forged.length
                + ",\"files\":[{\"path\":\"roles.toon\",\"bytes\":" + forged.length + ",\"sha256\":\"" + sha(forged) + "\"}]}";
        Files.writeString(dropDir.resolve("drop.zip.manifest.json"), sidecar);
        return zip;
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String restoreJob(String name, String task, Path zip, Path target) {
        return "{\"name\":\"" + name + "\",\"type\":\"maintenance\",\"task\":\"" + task + "\",\"archive\":\""
                + json(zip) + "\",\"target_dir\":\"" + json(target) + "\",\"overwrite\":\"true\"}";
    }

    private static String json(Path p) {
        return p.toAbsolutePath().toString().replace("\\", "\\\\");
    }

    @Test
    void aPowerUserCannotAuthorARestoreJobAtAnyDoorInAnyCase(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path cfg = Files.createDirectories(dir.resolve("config"));
            Path zip = plantArchive(dir.resolve("drop"));
            for (String task : List.of("restore", "RESTORE", "Restore")) {
                HttpResponse<String> post = send(c, "POST", "/jobs", restoreJob("r-" + task, task, zip, cfg), "Bearer power");
                assertEquals(403, post.statusCode(), task + ": " + post.body());
                assertTrue(post.body().contains("canAdminister"), post.body());
                String write = "{\"type\":\"job\",\"config\":{\"job\":" + restoreJob("w-" + task, task, zip, cfg) + "}}";
                HttpResponse<String> cw = send(c, "POST", "/config/write", write, "Bearer power");
                assertEquals(403, cw.statusCode(), task + " via /config/write: " + cw.body());
            }
            // PUT: turning an ordinary job the power user owns INTO a restore
            assertTrue(send(c, "POST", "/jobs", "{\"name\":\"tidy\",\"type\":\"maintenance\",\"task\":\"noop\"}",
                    "Bearer power").statusCode() < 300);
            HttpResponse<String> put = send(c, "PUT", "/jobs/tidy", restoreJob("tidy", "restore", zip, cfg), "Bearer power");
            assertEquals(403, put.statusCode(), put.body());
            assertFalse(Files.exists(cfg.resolve("roles.toon")), "nothing was restored");
        }
    }

    /** The probe that would otherwise succeed: the SAME body, authored and fired by an administrator, really
     *  overwrites roles.toon — so the 403s above are the gate, not a restore that could never have run. */
    @Test
    void theSameRestoreByAnAdministratorOverwritesTheRolesFile(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path cfg = Files.createDirectories(dir.resolve("config"));
            Files.writeString(cfg.resolve("roles.toon"), "roles:\n");
            Path zip = plantArchive(dir.resolve("drop"));
            HttpResponse<String> post = send(c, "POST", "/jobs", restoreJob("rollback", "restore", zip, cfg), "Bearer admin");
            assertTrue(post.statusCode() < 300, post.body());
            HttpResponse<String> fire = send(c, "POST", "/jobs/rollback/trigger", "", "Bearer admin");
            assertEquals(202, fire.statusCode(), fire.body());
            long deadline = System.currentTimeMillis() + 20_000;
            while (!Files.readString(cfg.resolve("roles.toon")).contains("canAdminister")
                    && System.currentTimeMillis() < deadline) Thread.sleep(100);
            assertTrue(Files.readString(cfg.resolve("roles.toon")).contains("canAdminister"),
                    "the restore ran and wrote the planted roles.toon");
        }
    }
}
