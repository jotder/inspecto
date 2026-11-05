package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.service.CollectorService;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /datasets/{id}/coverage} (LA-REFERENCE-DETAILS-MORE-1, UC-04 H9): over a synthetic pair - accounts ACC-0021..0060
 * described, transfers between ACC-0001..0040 - 40 accounts are described, 20 appear in the transfers and 20 do not; the
 * answer carries counts only. Real HTTP over Add data imports, plus the bounded scan on its own.
 */
class ControlApiDatasetCoverageTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
        Authenticators.forTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                java.nio.file.Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        return send(c, method, path, body, null);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (user != null) b.header("Authorization", "Bearer " + user);
        b = body == null ? b.GET() : b.method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private String imported(Ctx c, String base, String file, String kind, String csv) throws Exception {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        JsonNode d = data(send(c, "POST", "/onboard/files?name=" + file + "&kind=" + kind, csv), 200);
        data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);
        return id;
    }

    /** Accounts ACC-{from}..ACC-{to}, one row each. */
    private static String accounts(int from, int to) {
        StringBuilder sb = new StringBuilder("account_id,holder_name,kyc_level\n");
        for (int i = from; i <= to; i++) sb.append(String.format("ACC-%04d,Holder %d,%d\n", i, i, 1 + i % 3));
        return sb.toString();
    }

    @Test
    void aReferenceSaysHowManyOfItsEntitiesAppearInTheTransfers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // transfers between ACC-0001..0040 (every account sends and receives)
            String transfers = imported(c, "transfers", "transfers.csv", "stream", ControlApiOnboardFilesTest.transfers(400));
            String accounts = imported(c, "accounts", "accounts.csv", "reference", accounts(21, 60));

            HttpResponse<String> r = send(c, "GET", "/datasets/" + accounts + "/coverage", null);
            JsonNode out = data(r, 200);
            assertEquals(accounts, out.get("dataset").asText());
            assertEquals("account_id", out.get("identifyingColumn").asText());
            assertEquals(40, out.get("described").asLong(), out.toString());
            assertEquals(1, out.get("datasets").size(), out.toString());
            JsonNode t = out.get("datasets").get(0);
            assertEquals(transfers, t.get("dataset").asText());
            assertTrue(t.get("columns").toString().contains("from_account"), t.toString());
            assertTrue(t.get("columns").toString().contains("to_account"), t.toString());
            assertEquals(20, t.get("appear").asLong(), t.toString());
            assertEquals(20, t.get("absent").asLong(), t.toString());
            assertFalse(out.get("truncated").asBoolean());
            // counts only: no key or detail value leaves
            assertFalse(r.body().contains("ACC-"), r.body());
            assertFalse(r.body().contains("Holder"), r.body());

            // not a Reference: 200, no counts, the reason
            JsonNode notRef = data(send(c, "GET", "/datasets/" + transfers + "/coverage", null), 200);
            assertTrue(notRef.get("described").isNull());
            assertTrue(notRef.get("reason").asText().contains("not a Reference"), notRef.toString());
            // unknown: 404
            assertEquals(404, send(c, "GET", "/datasets/no_such_dataset/coverage", null).statusCode());
        }
    }

    /** A Dataset over a view of VALUES: {@code column} (classified) holding {@code keys}; {@code extra} = more entry keys. */
    private static void dataset(Ctx c, String id, String column, String classification, List<String> keys,
                                Map<String, Object> extra) throws Exception {
        StringBuilder values = new StringBuilder();
        for (String k : keys) values.append(values.isEmpty() ? "" : ",").append("('").append(k).append("', 1)");
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition(id + "_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES " + values + ") AS t(" + column + ", qty)", "2026-11-06T00:00:00Z"));
        Map<String, Object> entry = new java.util.LinkedHashMap<>(extra);
        entry.put("view", id + "_view");
        entry.put("columns", List.of(classification == null ? Map.of("name", column, "type", "string")
                : Map.of("name", column, "type", "string", "classification", classification), Map.of("name", "qty", "type", "number")));
        new ComponentStore(c.root.resolve("registry")).write("dataset", id, entry);
    }

    /**
     * The verifier's probe (2026-11-06): under masking, a caller who may not reveal ids makes Datasets of chosen numbers
     * classified as the Entity Type; their {@code appear} would say which numbers the Reference holds. No count is given
     * over a column masked for the caller, so the probe learns nothing; one who may reveal gets the counts. Falsified: with
     * the {@code hidesFromCaller} checks removed from {@code DatasetCoverage}, the viewer's answer carries
     * {@code probe_hit appear 1}.
     */
    @Test
    void noCountIsGivenOverAColumnMaskedForTheCaller(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer viewer" -> Optional.of(new Subject("viewer-1", Set.of("canUploadData")));
            case "Bearer revealer" -> Optional.of(new Subject("revealer-1", Set.of("canUploadData", BiResultMasking.REVEAL)));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            java.nio.file.Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            dataset(c, "subs", "msisdn", "MSISDN", List.of("44170001", "44170002"),
                    Map.of("kind", "reference", "identifyingColumn", "msisdn", "primaryEntityType", "msisdn"));
            dataset(c, "probe_hit", "msisdn", "MSISDN", List.of("44170001"), Map.of());
            dataset(c, "probe_miss", "msisdn", "MSISDN", List.of("99999999"), Map.of());

            HttpResponse<String> masked = send(c, "GET", "/datasets/subs/coverage", null, "viewer");
            JsonNode m = data(masked, 200);
            assertTrue(m.get("described").isNull(), m.toString());
            assertEquals(0, m.get("datasets").size(), m.toString());
            assertTrue(m.get("reason").asText().contains("masked for you"), m.toString());
            assertFalse(masked.body().contains("probe_"), masked.body());

            // a Dataset the caller may not view: 404 as the Reference, never counted as a holder
            Map<String, Object> privateEntry = Map.of("owner", "someone-else", "shares", List.of());
            dataset(c, "probe_private", "msisdn", "MSISDN", List.of("44170002"), privateEntry);
            Map<String, Object> privateRef = new java.util.LinkedHashMap<>(privateEntry);
            privateRef.putAll(Map.of("kind", "reference", "identifyingColumn", "msisdn", "primaryEntityType", "msisdn"));
            dataset(c, "subs_private", "msisdn", "MSISDN", List.of("44170001"), privateRef);
            assertEquals(404, send(c, "GET", "/datasets/subs_private/coverage", null, "revealer").statusCode());

            JsonNode r = data(send(c, "GET", "/datasets/subs/coverage", null, "revealer"), 200);
            assertEquals(2, r.get("described").asLong(), r.toString());
            assertEquals(2, r.get("datasets").size(), r.toString());   // probe_private is not listed
            assertEquals(1, r.get("datasets").get(0).get("appear").asLong(), r.toString());   // probe_hit
            assertEquals(0, r.get("datasets").get(1).get("appear").asLong(), r.toString());   // probe_miss
        }
    }

    /**
     * A holder's column masked for the caller is never compared, even when the Reference's own key is visible: under
     * {@code all} the unclassified key {@code phone} is shown raw while {@code calls.caller} (MSISDN) is masked. Falsified:
     * without the holder-side {@code hidesFromCaller}, the viewer's answer lists {@code calls}.
     */
    @Test
    void aHoldersMaskedColumnIsNeverCompared(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer viewer" -> Optional.of(new Subject("viewer-1", Set.of("canUploadData")));
            case "Bearer revealer" -> Optional.of(new Subject("revealer-1", Set.of("canUploadData", BiResultMasking.REVEAL)));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            java.nio.file.Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            dataset(c, "plans", "phone", null, List.of("44170001", "44170002"),
                    Map.of("kind", "reference", "identifyingColumn", "phone", "primaryEntityType", "msisdn"));
            dataset(c, "calls", "caller", "MSISDN", List.of("44170001"), Map.of());
            JsonNode out = data(send(c, "GET", "/datasets/plans/coverage", null, "viewer"), 200);
            assertEquals(2, out.get("described").asLong(), out.toString());
            assertEquals(0, out.get("datasets").size(), out.toString());
            JsonNode r = data(send(c, "GET", "/datasets/plans/coverage", null, "revealer"), 200);
            assertEquals(1, r.get("datasets").size(), r.toString());
            assertEquals(1, r.get("datasets").get(0).get("appear").asLong(), r.toString());
        }
    }

    @Test
    void theScanIsBoundedAndSaysSo() throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection conn = DuckDbUtil.openInMemory(null, List.of(), null); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE ref AS SELECT * FROM (VALUES ('A'), ('B'), (' C '), (NULL), (''), ('A')) t(k)");
            st.execute("CREATE TABLE ev AS SELECT * FROM (VALUES ('A', 'x'), ('x', 'C'), ('Z', 'y')) t(s, d)");
            // whole: A, B, C described (trimmed, blanks and nulls left out); A and C appear in either column
            assertArrayEquals(new long[]{3, 0}, DatasetCoverage.describe(conn, "SELECT * FROM ref", "k", 100));
            assertArrayEquals(new long[]{2, 0}, DatasetCoverage.appear(conn, "SELECT * FROM ev", List.of("s", "d"), 100));
            // one column only: only A
            assertArrayEquals(new long[]{1, 0}, DatasetCoverage.appear(conn, "SELECT * FROM ev", List.of("s"), 100));
            // a cap below the row count says so
            assertEquals(1, DatasetCoverage.describe(conn, "SELECT * FROM ref", "k", 2)[1]);
            assertEquals(1, DatasetCoverage.appear(conn, "SELECT * FROM ev", List.of("s", "d"), 1)[1]);
        }
    }
}
