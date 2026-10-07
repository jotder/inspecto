package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.gamma.access.Roles;
import com.gamma.access.ComponentAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding a Pending Change across Pods (`ASSURE-MAKER-CHECKER-MULTIPOD-1`): two control planes over the SAME
 * Space directory race to decide one change — exactly one wins, the change is applied once, the loser gets a
 * 409 naming who decided it. Plus two real JVM processes racing on the store lock, which the in-JVM monitor
 * cannot serialise — only the OS file lock does.
 */
class PendingChangesMultiPodTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHOR = "Bearer author", CHECKER = "Bearer checker", ADMIN = "Bearer admin";
    private static final String POLICY = "{\"approval\":{\"pattern-pack\":{\"required\":true}}}";
    private static final int ROUNDS = 8;
    private final HttpClient client = HttpClient.newHttpClient();

    private record Pod(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void armAuthenticator() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String[] who = switch (h) {
                case AUTHOR -> new String[] {"author-1", "pipeline-developer"};
                case CHECKER -> new String[] {"checker-1", "admin"};
                case ADMIN -> new String[] {"admin-1", "admin"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private static Pod pod(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            seedApproverRoster(writeRoot);   // OIDC-shaped Authenticator: the Space's approver roster decides
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Pod(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Pod p, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + p.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private String propose(Pod p, String id) throws Exception {
        HttpResponse<String> r = send(p, "POST", "/components/pattern-pack", "{\"id\":\"" + id + "\",\"title\":\"x\"}", AUTHOR);
        assertEquals(202, r.statusCode(), r.body());
        return JSON.readTree(r.body()).at("/data/pendingChange/id").asText();
    }

    /** Fire both decisions at once from two threads released by one latch — CHECKER on Pod a, ADMIN on Pod b. */
    private List<HttpResponse<String>> race(Pod a, String pathA, Pod b, String pathB) throws Exception {
        return race(a, pathA, CHECKER, b, pathB, ADMIN);
    }

    private List<HttpResponse<String>> race(Pod a, String pathA, String authA, Pod b, String pathB, String authB)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<HttpResponse<String>> fa = pool.submit(() -> { go.await(); return send(a, "POST", pathA, "{}", authA); });
            Future<HttpResponse<String>> fb = pool.submit(() -> { go.await(); return send(b, "POST", pathB, "{}", authB); });
            go.countDown();
            return List.of(fa.get(60, TimeUnit.SECONDS), fb.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static int count(List<HttpResponse<String>> rs, int status) {
        return (int) rs.stream().filter(r -> r.statusCode() == status).count();
    }

    private JsonNode record(Pod p, String id) throws Exception {
        return JSON.readTree(send(p, "GET", "/pending-changes/" + id, null, CHECKER).body()).get("data");
    }

    @Test
    void twoPodsApprovingTheSameChangeExactlyOneWinsAndItAppliesOnce(@TempDir Path cfgA, @TempDir Path cfgB,
                                                                      @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Pod a = pod(cfgA, root); Pod b = pod(cfgB, root)) {
            assertEquals(200, send(a, "PUT", "/settings/approval", POLICY, ADMIN).statusCode());
            for (int i = 0; i < ROUNDS; i++) {
                String id = propose(a, "p" + i);
                List<HttpResponse<String>> rs = race(a, "/pending-changes/" + id + "/approve", b, "/pending-changes/" + id + "/approve");
                String both = rs.get(0).body() + " | " + rs.get(1).body();
                assertEquals(1, count(rs, 200), "exactly one Pod wins: " + both);
                assertEquals(1, count(rs, 409), "the other gets 409: " + both);
                HttpResponse<String> won = rs.get(0).statusCode() == 200 ? rs.get(0) : rs.get(1);
                HttpResponse<String> lost = rs.get(0).statusCode() == 409 ? rs.get(0) : rs.get(1);
                String winner = JSON.readTree(won.body()).at("/data/pendingChange/decidedBy").asText();
                assertTrue(lost.body().contains("already approved (decided by " + winner), lost.body());
                JsonNode rec = record(b, id);
                assertEquals("approved", rec.get("status").asText(), "the winner's decision stands: " + rec);
                assertEquals(winner, rec.get("decidedBy").asText());
                assertTrue(new ComponentStore(root.resolve("registry")).exists("pattern-pack", "p" + i), "applied");
            }
        }
    }

    @Test
    void declineRacingApproveLeavesOneConsistentOutcome(@TempDir Path cfgA, @TempDir Path cfgB,
                                                        @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Pod a = pod(cfgA, root); Pod b = pod(cfgB, root)) {
            assertEquals(200, send(a, "PUT", "/settings/approval", POLICY, ADMIN).statusCode());
            for (int i = 0; i < ROUNDS; i++) {
                String id = propose(a, "d" + i);
                List<HttpResponse<String>> rs = race(a, "/pending-changes/" + id + "/approve", b, "/pending-changes/" + id + "/decline");
                String both = rs.get(0).body() + " | " + rs.get(1).body();
                assertEquals(1, count(rs, 200), both);
                assertEquals(1, count(rs, 409), both);
                boolean approved = rs.get(0).statusCode() == 200;
                JsonNode rec = record(b, id);
                boolean written = new ComponentStore(root.resolve("registry")).exists("pattern-pack", "d" + i);
                assertEquals(approved ? "approved" : "declined", rec.get("status").asText(), rec.toString());
                assertEquals(approved, written, "applied iff the approval won: " + rec);
            }
        }
    }

    /** `ASSURE-MAKER-CHECKER-RESIDUALS-1` (2): the author withdrawing on one Pod while a checker approves on another. */
    @Test
    void withdrawRacingApproveLeavesOneConsistentOutcome(@TempDir Path cfgA, @TempDir Path cfgB,
                                                         @TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        try (Pod a = pod(cfgA, root); Pod b = pod(cfgB, root)) {
            assertEquals(200, send(a, "PUT", "/settings/approval", POLICY, ADMIN).statusCode());
            for (int i = 0; i < ROUNDS; i++) {
                String id = propose(a, "w" + i);
                List<HttpResponse<String>> rs = race(a, "/pending-changes/" + id + "/approve", CHECKER,
                        b, "/pending-changes/" + id + "/withdraw", AUTHOR);
                String both = rs.get(0).body() + " | " + rs.get(1).body();
                assertEquals(1, count(rs, 200), both);
                assertEquals(1, count(rs, 409), both);
                boolean approved = rs.get(0).statusCode() == 200;
                JsonNode rec = record(b, id);
                boolean written = new ComponentStore(root.resolve("registry")).exists("pattern-pack", "w" + i);
                assertEquals(approved ? "approved" : "withdrawn", rec.get("status").asText(), rec.toString());
                assertEquals(approved, written, "applied iff the approval won: " + rec);
            }
        }
    }

    // ── two JVM processes ─────────────────────────────────────────────────────────────────────────

    /**
     * Two real processes decide one record through {@link PendingChanges#underStoreLock} with the read → still
     * pending? → apply → save the approve route runs, a pause widening the window. Each appends its name to
     * {@code applied.log} when it applies — exactly one line proves it applied once.
     */
    @Test
    void twoProcessesDecidingOneRecordApplyItExactlyOnce(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        String id = "pc-20260927000000-abcdef";
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("status", "pending");
        PendingChanges.save(root, rec);
        Path go = tmp.resolve("go");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> names = List.of("pod-a", "pod-b");
        List<Process> procs = new ArrayList<>();
        for (String name : names)
            procs.add(new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    Child.class.getName(), root.toString(), id, name, go.toString())
                    .redirectErrorStream(true).redirectOutput(tmp.resolve(name + ".out").toFile()).start());
        Thread.sleep(2000);   // both JVMs up and polling for the go file
        Files.writeString(go, "go");
        for (int i = 0; i < procs.size(); i++) {
            assertTrue(procs.get(i).waitFor(60, TimeUnit.SECONDS), "child finished");
            assertEquals(0, procs.get(i).exitValue(), Files.readString(tmp.resolve(names.get(i) + ".out")));
        }
        List<String> applied = Files.readAllLines(root.resolve("applied.log"));
        assertEquals(1, applied.size(), "applied exactly once: " + applied);
        Map<String, Object> after = PendingChanges.read(root, id);
        assertFalse(PendingChanges.invalid(after));
        assertEquals("approved", after.get("status"));
        assertEquals(applied.get(0), after.get("decidedBy"), "the record names the process that applied it");
        String outs = Files.readString(tmp.resolve("pod-a.out")) + Files.readString(tmp.resolve("pod-b.out"));
        assertEquals(1, outs.split("LOST", -1).length - 1, "the other process lost: " + outs);
    }

    /** The child "Pod": wait for the go file, then compare-and-set the record under the store lock. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            String id = args[1], name = args[2];
            Path go = Path.of(args[3]);
            while (!Files.exists(go)) Thread.sleep(5);
            PendingChanges.<Void, Exception>underStoreLock(root, () -> {
                Map<String, Object> r = PendingChanges.read(root, id);
                if (!"pending".equals(r.get("status"))) {
                    System.out.println("LOST to " + r.get("decidedBy"));
                    return null;
                }
                Thread.sleep(400);   // the apply takes time: without the lock the other process reads PENDING here
                Files.writeString(root.resolve("applied.log"), name + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                r.put("status", "approved");
                r.put("decidedBy", name);
                PendingChanges.save(root, r);
                System.out.println("WON");
                return null;
            });
        }
    }

    /** The Space's approver roster ({@link ApproverRoster}): every id this class's Authenticator mints. */
    private static void seedApproverRoster(Path root) throws java.io.IOException {
        java.nio.file.Files.createDirectories(root);
        java.nio.file.Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                java.util.Map.of("users", java.util.List.of("admin-1", "author-1", "checker-1"))));
    }
}
