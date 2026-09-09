package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four-stage write gate: <b>its ORDER, and {@link WriteGates} itself</b> — one of
 * {@code SPEC-NOPROOF-1}'s six Musts, filed because the order "is depended on by thirteen route modules
 * and verified by reading call sites".
 *
 * <p>Two gaps, and the second was wider than the row said. {@code .claude/skills/endpoint} makes the
 * sequence binding for every write route — <b>write-root 503 → spec/{@code ConfigSafetyValidator} 422 →
 * path jail 403 → conflict 409</b> — and nothing asserted it. But {@link WriteGates}, the shared helper
 * every one of those routes calls, had <b>no test of any kind</b>: not the order, not the individual
 * statuses.
 *
 * <p><b>Why the order is the thing to test, not just the gates.</b> Each gate alone is easy to get right;
 * the security property is which one wins when a request trips several. A request that escapes the write
 * root <i>and</i> carries an invalid payload must not have its traversal reported first — the earlier gate
 * is the cheaper, safer refusal, and reordering them silently would leak which paths exist to a caller who
 * was never allowed to write at all. So every rung below violates <b>more than one</b> gate and asserts the
 * expected one answers.
 *
 * <p>🔴 <b>A grep cannot see this order, which is worth recording because it misled me.</b> Counting
 * {@code WriteGates.} call sites in {@code ConfigWriteRoutes} suggests the subdir jail (403) runs before
 * {@code safeName} (422) — apparently inverting the documented sequence. It does not: the canonical
 * gate 2 is the spec + safety block, thrown as {@code respondJson(ex, 422, …)} with its {@code findings},
 * <b>not</b> through {@code WriteGates} at all. {@code safeName}'s 422 is a different, later check on a
 * different input. Read the route, not the helper's call sites — and assert on the 422 that carries
 * {@code findings}, which is what distinguishes the two.
 *
 * <p><b>Mutation-proven 2026-09-09, both levels:</b>
 * <ul>
 *   <li>making {@code WriteGates.jail} throw <b>422 instead of 403</b> failed <b>2 of 9</b> — the unit
 *       test on the status, and rung 3 over HTTP. One token, caught at both levels;</li>
 *   <li>moving gate 1 ({@code requireWriteRoot}) <b>below</b> the spec/safety 422 block in
 *       {@code ConfigWriteRoutes} — the exact reorder this class exists to catch — failed rung 1, which
 *       then saw a 422 where the contract requires a 503.</li>
 * </ul>
 * Both were reverted and the tree verified clean after each.
 *
 * <p>⛔ Do not "fix" a failure here by loosening a rung. A rung that returns a LATER status than expected
 * means a gate moved, and moving a write gate is the change the route's own comment calls "a bigger change
 * than the defect warrants".
 */
class WriteGateOrderTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * Boot a server; {@code writeRoot == null} means writes are disabled. {@code ControlApi} reads
     * {@code assist.write.root} ONCE in its constructor, so the property is set only around construction
     * and cleared immediately — never left set across a test.
     */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .method("POST", BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    /** A draft that passes every ERROR-level finding, so only the gate under test can refuse it. */
    private static String validDraft(String name, String subdir) {
        return """
                {"type":"pipeline",%s"config":{
                   "name":"%s",
                   "dirs":{"poll":"in","database":"out"},
                   "parsing":{"delimiter":";"},
                   "collector":{"connector":"local","discovery":"poll",
                                "duplicate":{"mode":"checksum","algorithm":"xxh64"}},
                   "processing":{"threads":1}}}"""
                .formatted(subdir == null ? "" : "\"subdir\":\"" + subdir + "\",", name);
    }

    /** The same draft with a spec-modelled field made invalid, so gate 2 has something to refuse. */
    private static String invalidDraft(String name, String subdir) {
        return validDraft(name, subdir).replace("\"threads\":1", "\"threads\":\"not-a-number\"");
    }

    // ── the ORDER: every rung violates more than one gate ─────────────────────────────────────────

    /** Rung 1 — writes disabled beats an invalid payload AND a traversing subdir. */
    @Test
    void writeRootDisabledAnswersFirst(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            HttpResponse<String> r = post(c.port, "/config/write", invalidDraft("gate1", "../escape"));
            assertEquals(503, r.statusCode(),
                    "gate 1 must answer before the payload is even validated — a caller with no write "
                            + "permission must not learn whether their path or payload was acceptable: " + r.body());
            assertTrue(r.body().contains("assist.write.root"),
                    "the 503 must name the switch that enables writes: " + r.body());
        }
    }

    /** Rung 2 — the spec/safety findings beat a traversing subdir. */
    @Test
    void specValidationAnswersBeforeThePathJail(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", invalidDraft("gate2", "../escape"));
            assertEquals(422, r.statusCode(),
                    "gate 2 must answer before gate 3 — an invalid payload is refused without resolving "
                            + "any path at all: " + r.body());
            assertTrue(r.body().contains("findings"),
                    "it must be the SPEC 422 (which carries findings), not safeName's later 422: " + r.body());
        }
    }

    /** Rung 3 — the path jail beats the conflict check, on an otherwise valid payload. */
    @Test
    void thePathJailAnswersBeforeTheConflictCheck(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, post(c.port, "/config/write", validDraft("gate3", null)).statusCode(),
                    "precondition: the same name must already exist, so a conflict is available to lose to");

            HttpResponse<String> r = post(c.port, "/config/write", validDraft("gate3", "../escape"));
            assertEquals(403, r.statusCode(),
                    "gate 3 must answer before gate 4 — a traversal is refused without disclosing whether "
                            + "the target already exists: " + r.body());
        }
    }

    /** Rung 4 — with every earlier gate satisfied, the conflict is what is left. */
    @Test
    void theConflictCheckAnswersLast(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, post(c.port, "/config/write", validDraft("gate4", null)).statusCode(),
                    "precondition: first write must succeed");

            HttpResponse<String> r = post(c.port, "/config/write", validDraft("gate4", null));
            assertEquals(409, r.statusCode(),
                    "a second write of the same identity without overwrite is the last gate: " + r.body());
        }
    }

    // ── WriteGates itself, which had no test at all ───────────────────────────────────────────────

    @Test
    void safeNameRefusesEverythingThatCouldEscapeAFilename() {
        assertEquals("ok_name-1.toon", WriteGates.safeName("  ok_name-1.toon  ", "config name"),
                "a good name is returned TRIMMED");

        for (String bad : List.of("", "   ", "..", "../etc", "a/b", "a\\b", ".hidden", "-lead")) {
            ApiException e = assertThrows(ApiException.class, () -> WriteGates.safeName(bad, "config name"),
                    "must refuse " + (bad.isBlank() ? "<blank>" : bad));
            assertEquals(422, e.status, "an unusable name is a 422, not a 403: " + bad);
        }
    }

    /** The predicate and the throwing gate must never disagree — a caller picks one by intent, not luck. */
    @Test
    void theIsSafeNamePredicateAgreesWithTheThrowingGate() {
        for (String candidate : List.of("ok", "ok.toon", "a_b-c.1", "", "..", "../x", "a/b", ".hidden", "-x")) {
            boolean predicate = WriteGates.isSafeName(candidate);
            boolean throwing = true;
            try {
                WriteGates.safeName(candidate, "n");
            } catch (ApiException e) {
                throwing = false;
            }
            assertEquals(predicate, throwing,
                    "isSafeName and safeName disagree on '" + candidate + "' — a probing caller and a "
                            + "demanding one would then see different rules");
        }
    }

    @Test
    void theJailRefusesAnEscapeAndReturnsTheNormalisedPathOtherwise(@TempDir Path root) {
        Path inside = WriteGates.jail(root, root.resolve("sub").resolve("..").resolve("f.toon"), "p");
        assertEquals(root.resolve("f.toon"), inside, "an inside path comes back NORMALISED");

        ApiException e = assertThrows(ApiException.class,
                () -> WriteGates.jail(root, root.resolve("..").resolve("escape.toon"), "target"));
        assertEquals(403, e.status, "an escape is a 403, not a 422");
        assertTrue(e.getMessage().contains("escapes the write root"), e.getMessage());
    }

    @Test
    void conflictIfThrows409OnlyWhenItConflicts() {
        assertDoesNotThrow(() -> WriteGates.conflictIf(false, "no conflict"));
        ApiException e = assertThrows(ApiException.class, () -> WriteGates.conflictIf(true, "already exists"));
        assertEquals(409, e.status);
        assertEquals("already exists", e.getMessage());
    }

    @Test
    void aBlankOrTraversingNameIsRefusedByThePredicateToo() {
        assertFalse(WriteGates.isSafeName(null), "null must be unsafe, not a crash");
        assertFalse(WriteGates.isSafeName(".."));
        assertTrue(WriteGates.isSafeName("fine"));
    }
}
