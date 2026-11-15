package com.gamma.demoauth;

import com.gamma.control.ControlApi;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-FULL-CYCLE-TEST-1} finding 32 (decision D5) over real HTTP with real Demo Users: a Space a Demo Admin creates
 * is closed. Its creator and another administrator read it; a Demo User known only to ANOTHER Space's roster is answered
 * as if it did not exist (404, the unknown-Space answer) on its Datasets, arrivals, inbox and settings, and does not see
 * it listed - whether their role resolves to nothing there ({@code fraud-analyst}) or to the built-in grants
 * ({@code operations}). An administrator admits a role by defining it in that Space's role table. The Space placed on
 * disk stays open, as before. Synthetic fixtures only.
 */
class DemoSpaceAdmissionHttpTest {

    /** What the finding read, and the settings beside it. {@code transfers} is the import Dataset the creator adds. */
    private static final List<String> READS = List.of("/components/dataset", "/components/dataset/transfers",
            "/datasets/transfers/rows", "/datasets/transfers/arrivals", "/datasets/transfers/feed", "/datasets/transfers/storage",
            "/pipelines/authored/transfers/inbox", "/settings/data", "/settings/link-analysis", "/access/roles", "/bootstrap");

    private final HttpClient client = HttpClient.newHttpClient();
    private String bind, spacesRoot;

    @BeforeEach
    void save() {
        bind = System.getProperty("control.bind");
        spacesRoot = System.getProperty("spaces.root");
    }

    @AfterEach
    void restore() {
        set("control.bind", bind);
        set("spaces.root", spacesRoot);
    }

    private static void set(String k, String v) {
        if (v == null) System.clearProperty(k); else System.setProperty(k, v);
    }

    private HttpResponse<String> send(int port, String method, String path, String bearer, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> json(int port, String method, String path, String bearer, String body) throws Exception {
        return send(port, method, path, bearer, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private String signIn(int port, String user) throws Exception {
        HttpResponse<String> r = json(port, "POST", "/auth/exchange", null,
                "{\"code\":\"demo:" + user + "\",\"codeVerifier\":\"unused\",\"redirectUri\":\"unused\"}");
        assertEquals(200, r.statusCode(), r.body());
        Matcher m = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body());
        assertTrue(m.find(), r.body());
        return m.group(1);
    }

    /** The error an answer carries, without its per-request correlation id. */
    private static String error(HttpResponse<String> r) {
        return r.body().replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }

    private static String transfers(int rows) {
        StringBuilder sb = new StringBuilder("transfer_id,from_account,to_account,channel,amount,booked_at,currency\n");
        for (int i = 1; i <= rows; i++) {
            int from = (i * 7) % 40 + 1, to = (i * 13 + 5) % 40 + 1;
            if (to == from) to = to % 40 + 1;
            sb.append(String.format("T%05d,ACC-%04d,ACC-%04d,mobile,%d.00,2026-09-%02d %02d:%02d:00,EUR\n", i, from, to,
                    10 + (i * 37) % 900, 1 + i % 28, i % 24, i % 60));
        }
        return sb.toString();
    }

    @Test
    void aSpaceADemoAdminCreatesIsClosedToAnotherSpacesRosterUntilARoleIsAdmitted(@TempDir Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve("showcase").resolve("config"));
        Files.writeString(config.resolve("demo-users.toon"), """
                users[4]{id,displayName,title,roles}:
                  admin,Demo Admin,Platform administrator,admin
                  second.admin,Demo Second Admin,Platform administrator,admin
                  fm.analyst,Demo FM Analyst,Fraud analyst,fraud-analyst
                  ops.analyst,Demo Operations Analyst,Operations analyst,operations
                """);
        Files.writeString(config.resolve("roles.toon"), """
                roles[1]:
                  - name: fraud-analyst
                    capabilities[2]: canManageIncidents,canWorkIncidents
                """);
        System.setProperty("control.bind", "127.0.0.1");
        System.setProperty("spaces.root", root.toString());
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        try {
            int port = api.port();
            String admin = signIn(port, "admin"), second = signIn(port, "second.admin"),
                    fm = signIn(port, "fm.analyst"), ops = signIn(port, "ops.analyst");

            // the Demo Admin creates a Space and adds a file, as the full-cycle drive did
            HttpResponse<String> made = json(port, "POST", "/spaces", admin, "{\"id\":\"bank\",\"display_name\":\"Bank transfers\"}");
            assertEquals(200, made.statusCode(), made.body());
            assertTrue(made.body().contains("\"admission\":\"closed\"") && made.body().contains("\"createdBy\":\"admin\""),
                    "the answer says the Space is closed and who created it: " + made.body());
            assertEquals(200, json(port, "PUT", "/spaces/bank/settings/data", admin, "{\"selfImport\":true}").statusCode());
            HttpResponse<String> draft = send(port, "POST", "/spaces/bank/onboard/files?name=transfers.csv&kind=stream", admin,
                    transfers(40).getBytes(StandardCharsets.UTF_8));
            assertEquals(200, draft.statusCode(), draft.body());
            Matcher d = Pattern.compile("\"draftId\"\\s*:\\s*\"([^\"]+)\"").matcher(draft.body());
            assertTrue(d.find(), draft.body());
            assertEquals(200, json(port, "POST", "/spaces/bank/onboard/files/" + d.group(1) + "/commit", admin,
                    "{\"name\":\"transfers\"}").statusCode());

            // its creator, and another administrator of the installation, read it
            for (String administrator : List.of(admin, second)) {
                assertTrue(json(port, "GET", "/spaces", administrator, null).body().contains("\"id\":\"bank\""), "listed to an administrator");
                for (String read : READS)
                    assertEquals(200, json(port, "GET", "/spaces/bank" + read, administrator, null).statusCode(), read);
            }
            assertTrue(json(port, "GET", "/spaces/bank/datasets/transfers/rows", second, null).body().contains("EUR"),
                    "the rows themselves");

            // a Demo User of the showcase roster only: no such Space, in the words an unknown id gets
            String unknown = error(json(port, "GET", "/spaces/bank2/components/dataset", fm, null)).replace("bank2", "bank");
            assertTrue(unknown.contains("no such space 'bank'"), unknown);
            for (String outsider : List.of(fm, ops)) {
                HttpResponse<String> list = json(port, "GET", "/spaces", outsider, null);
                assertEquals(200, list.statusCode(), list.body());
                assertTrue(list.body().contains("\"id\":\"showcase\"") && !list.body().contains("bank"),
                        "a closed Space is not listed to someone it does not admit: " + list.body());
                assertFalse(json(port, "GET", "/bootstrap", outsider, null).body().contains("\"id\":\"bank\""), "nor in the bootstrap roster");
                for (String read : READS) {
                    HttpResponse<String> r = json(port, "GET", "/spaces/bank" + read, outsider, null);
                    assertEquals(404, r.statusCode(), read + ": " + r.body());
                    assertEquals(unknown, error(r), read + " answers exactly as an unknown Space does");
                    assertFalse(r.body().contains(root.getFileName().toString()), "and names no server path: " + r.body());
                }
                assertEquals(404, json(port, "POST", "/spaces/bank/datasets/transfers/run", outsider, null).statusCode(),
                        "a write is refused the same way");
                // the Space placed on disk carries no admission: open to every signed-in Demo User, as before
                assertEquals(200, json(port, "GET", "/spaces/showcase/components/dataset", outsider, null).statusCode());
            }
            assertEquals(401, json(port, "GET", "/spaces/bank/components/dataset", null, null).statusCode(), "no credential is still 401");

            // an administrator admits a role by defining it in THIS Space's role table
            assertEquals(200, json(port, "PUT", "/spaces/bank/access/roles", admin,
                    "{\"roles\":[{\"name\":\"fraud-analyst\",\"capabilities\":[\"canManageIncidents\",\"canUploadData\"]}]}").statusCode());
            assertTrue(json(port, "GET", "/spaces", fm, null).body().contains("\"id\":\"bank\""), "now listed to the admitted role");
            assertEquals(200, json(port, "GET", "/spaces/bank/datasets/transfers/arrivals", fm, null).statusCode());
            HttpResponse<String> feed = json(port, "GET", "/spaces/bank/datasets/transfers/feed", fm, null);
            assertEquals(200, feed.statusCode(), feed.body());
            assertTrue(feed.body().contains("\"inbox\":\"inbox/transfers\""),
                    "someone who does not administer sees the inbox relative to the Space's data folder: " + feed.body());
            assertTrue(json(port, "GET", "/spaces/bank/datasets/transfers/feed", admin, null).body()
                            .contains(root.getFileName().toString()), "an administrator sees where it is on the server");
            // the built-in `operations` role is not one this Space defines: its holder stays out
            assertEquals(404, json(port, "GET", "/spaces/bank/datasets/transfers/arrivals", ops, null).statusCode());
            assertFalse(json(port, "GET", "/spaces", ops, null).body().contains("bank"));
        } finally {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }
}
