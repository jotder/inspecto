package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DR-T6: the user manual's section 10 worked-example figures, REPRODUCED over the shipped {@code la-showcase} corpus
 * ({@code spaces/_templates/la-showcase/data}, the three landed days, read in place) through the real
 * {@code GET /inv/value-measures} route with every default threshold. The corpus is loaded as a VALUES view, one row per
 * CSV line. If a figure the manual quotes moves, this fails and the manual text is what gets corrected.
 */
class ControlApiLaShowcaseFiguresTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DATA = Path.of("..", "..", "spaces", "_templates", "la-showcase", "data", "inbox", "mule_transfers");
    private static final String Q = "/inv/value-measures?dataset=mule_ds&sourceCol=PAYER_ACCOUNT&targetCol=PAYEE_ACCOUNT"
            + "&linkKindCol=CHANNEL&valueCol=AMOUNT&timeCol=BOOKED_AT&from=2026-09-01&to=2026-09-04";
    private final HttpClient client = HttpClient.newHttpClient();

    private static String valuesSql() throws Exception {
        List<String> rows = new ArrayList<>();
        for (String f : List.of("TRANSFERS_20260901.csv", "TRANSFERS_20260902.csv", "TRANSFERS_20260903.csv")) {
            List<String> lines = Files.readAllLines(DATA.resolve(f));
            for (String l : lines.subList(1, lines.size())) {
                String[] c = l.split(",", -1);
                rows.add("('" + c[1] + "','" + c[2] + "','" + c[3] + "'," + c[4] + ",TIMESTAMP '" + c[9] + "')");
            }
        }
        return "SELECT PAYER_ACCOUNT, PAYEE_ACCOUNT, CHANNEL, AMOUNT, BOOKED_AT FROM (VALUES " + String.join(",", rows)
                + ") AS t(PAYER_ACCOUNT,PAYEE_ACCOUNT,CHANNEL,AMOUNT,BOOKED_AT)";
    }

    private static String names(JsonNode m) {
        List<String> n = new ArrayList<>();
        m.get("entities").forEach(e -> n.add(e.get("entity").asText()));
        return n.toString();
    }

    private static long near(JsonNode m) {
        long c = 0;
        for (JsonNode e : m.get("entities")) if (Math.abs(e.get("ratio").asDouble() - 0.985) < 0.0005) c++;
        return c;
    }

    private JsonNode measure(int port, String query) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + query)).GET().build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void sectionTenFiguresHoldOverTheShowcaseCorpus(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            try {
                new ViewStore(root.resolve("views")).write(new ViewDefinition("mule_view", "flow-x", List.of(), valuesSql(),
                        "2026-09-30T00:00:00Z"));
                new ComponentStore(root.resolve("registry")).write("dataset", "mule_ds", Map.of("view", "mule_view"));
                JsonNode st = measure(api.port(), Q + "&name=structuring");
                assertEquals("MULE-HUB-01", st.at("/entities/0/entity").asText());
                assertEquals(96, st.at("/entities/0/legs").asInt(), "96 in-band legs");
                assertEquals(12, st.at("/entities/0/payers").asInt(), "from 12 payers");

                JsonNode pt = measure(api.port(), Q + "&name=passThrough");
                assertEquals(13, pt.get("count").asInt(), "Pass-through flags 13 accounts: " + names(pt));
                for (JsonNode e : pt.get("entities")) {
                    String id = e.get("entity").asText();
                    if (id.startsWith("RELAY-")) assertEquals(0.985, e.get("ratio").asDouble(), 0.0005, id + " relays at about 0.985");
                }
                assertEquals(2, near(pt), "exactly the two relays sit at 0.985");
                assertTrue(names(pt).contains("MULE-HUB-01"), names(pt));
                assertTrue(measure(api.port(), Q + "&name=velocity").get("count").asInt() >= 3, "Velocity lists the fast forwarders");

                JsonNode co = measure(api.port(), Q + "&name=cashOutConcentration&cashOutKinds=cash_out");
                assertEquals("TILL-06", co.at("/entities/0/entity").asText());
                assertEquals(0.97, co.at("/entities/0/share").asDouble(), 0.005, "TILL-06 holds 97 % of cash-out value");
                assertEquals(8, co.at("/entities/0/payers").asInt(), "from 8 payers");

                JsonNode bt = measure(api.port(), Q + "&name=benefitTransfer&benefitKinds=benefit");
                assertEquals(1, bt.get("count").asInt());
                assertEquals("SKIMMER-01", bt.at("/entities/0/entity").asText());
                assertEquals(6, bt.at("/entities/0/recipients").asInt());
            } finally {
                api.close();
                svc.close();
            }
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
