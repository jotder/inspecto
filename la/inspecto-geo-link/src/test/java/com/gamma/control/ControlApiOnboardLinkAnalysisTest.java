package com.gamma.control;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Link Analysis half of Add data ({@code LA-ONBOARD-1} phase 2, OB-2, OB-8, F-9, DT-16) over real HTTP, with the Link
 * Analysis modules on the classpath, no sign-in (Basic): one confirm turns a transfers file into a Dataset whose link index
 * is built INSIDE the user's run (not by a signal-driven Job), {@code /inv/projection} draws it with no saved view, and the
 * Entity profile assesses the account links from the Dataset's own {@code defaultLinkMapping} - while a Dataset without one
 * stays "not assessed". Synthetic fixture.
 */
class ControlApiOnboardLinkAnalysisTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private String id;

    @BeforeEach
    void setUp() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
        id = "onboard_la_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
        for (Path p : List.of(Path.of("database", id), Path.of("database", "inbox", id), Path.of("database", "_onboard")))
            if (Files.exists(p))
                try (Stream<Path> w = Files.walk(p)) {
                    for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
                }
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? b.GET() : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** Transfers between 40 accounts, with one hub (ACC-0001) that receives every tenth transfer. */
    private static String transfers(int rows) {
        StringBuilder sb = new StringBuilder("transfer_id,from_account,to_account,channel,amount,booked_at\n");
        String[] channels = {"mobile", "branch", "online"};
        for (int i = 1; i <= rows; i++) {
            int from = (i * 7) % 40 + 2, to = i % 10 == 0 ? 1 : (i * 13 + 5) % 40 + 2;
            if (to == from) to = to % 40 + 2;
            sb.append(String.format("T%05d,ACC-%04d,ACC-%04d,%s,%d.50,2026-09-%02d 10:%02d:00\n", i, from, to, channels[i % 3],
                    10 + (i * 37) % 900, 1 + i % 28, i % 60));
        }
        return sb.toString();
    }

    @Test
    void anImportIsIndexedInTheRunDrawnWithoutASavedViewAndItsLinksAreAssessed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode s = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals(200, s.get("rowsAfter").asLong(), s.toString());

            // DT-16: on Basic the link index is built inside the user's own run, not dispatched as a signal-driven Job
            assertTrue(s.get("followOnInRun").asBoolean());
            assertEquals("BUILT", s.get("index").get("result").asText(), s.get("index").toString());
            assertTrue(s.get("index").get("edges").asLong() > 0, s.get("index").toString());
            boolean indexed = false;
            for (JsonNode ix : data(send(c, "GET", "/inv/index", null), 200).get("indexes"))
                if (id.equals(ix.get("dataset").asText())) indexed = true;
            assertTrue(indexed, "the index is published for the Dataset");

            // the Dataset draws with no saved view
            JsonNode g = data(send(c, "POST", "/inv/projection", ("{\"dataset\":\"" + id
                    + "\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\",\"linkKindCol\":\"channel\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertTrue(g.get("rows").size() > 0, "links are drawn (ids masked or not, per the Space's masking mode): " + g);
            assertTrue(g.toString().contains("\"kind\":\"mobile\""), "the kind column the draft proposed rides the links");

            // F-9: the account links are assessed from the Dataset's defaultLinkMapping - no view, no request mapping
            assertTrue(new ComponentStore(root.resolve("registry")).list("link-analysis-view").isEmpty(), "no saved view exists");
            JsonNode p = data(send(c, "GET", "/inv/entity-types/account/profile?sections=links,bindings", null), 200);
            assertTrue(p.at("/links/assessed").asBoolean(), p.get("links").toString());
            boolean source = false;
            for (JsonNode b : p.get("bindings"))
                if (id.equals(b.get("dataset").asText()) && "from_account".equals(b.get("column").asText()))
                    source = "source".equals(b.get("role").asText());
            assertTrue(source, "from_account is the link's source: " + p.get("bindings"));

            // F-9 isolated from the index: the same rows registered as wallets WITH a defaultLinkMapping (no index, no view) are
            // assessed; as agents WITHOUT one they stay "not assessed"
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("dataset", id + "_wallets", Map.of("physicalRef", id + "/database",
                    "columns", List.of(Map.of("name", "from_account", "classification", "WALLET"),
                            Map.of("name", "to_account", "classification", "WALLET")),
                    "defaultLinkMapping", Map.of("from", "from_account", "to", "to_account")));
            registry.write("dataset", id + "_agents", Map.of("physicalRef", id + "/database",
                    "columns", List.of(Map.of("name", "from_account", "classification", "AGENT"),
                            Map.of("name", "to_account", "classification", "AGENT"))));
            JsonNode w = data(send(c, "GET", "/inv/entity-types/wallet/profile?sections=links", null), 200);
            assertTrue(w.at("/links/assessed").asBoolean(), w.toString());
            JsonNode a = data(send(c, "GET", "/inv/entity-types/agent/profile?sections=links", null), 200);
            assertFalse(a.at("/links/assessed").asBoolean(), a.toString());

            // Run now builds again inside the run: the index is current
            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", new byte[0]), 200);
            assertTrue(List.of("BUILT", "UP_TO_DATE").contains(now.get("index").get("result").asText()), now.get("index").toString());
        }
    }
}
