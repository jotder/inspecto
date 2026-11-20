package com.gamma.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP authorization discovery over real HTTP (AGENT-KIT-1 AK-2a, plan §4.3): {@code GET /.well-known/oauth-protected-resource}
 * names the issuer as the authorization server (RFC 9728), only when sign-in names one; a 401 from {@code /mcp} — and
 * only from {@code /mcp} — carries {@code WWW-Authenticate: Bearer resource_metadata="…"} pointing at it.
 */
class ControlApiMcpDiscoveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUER = "https://idp.example/realms/inspecto";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** "Bearer ops" authenticates as operations; {@code issuer} is what the Authenticator names (null = none). */
    private static void arm(String issuer) {
        Authenticators.forTest(new Authenticator() {
            @Override
            public Optional<Subject> authenticate(HttpExchange ex) {
                if (!"Bearer ops".equals(ex.getRequestHeaders().getFirst("Authorization"))) return Optional.empty();
                Roles.Def def = Roles.effective(ex).get("operations");
                ComponentAccess.heldRoles(ex, Set.of("operations"));
                return Optional.of(new Subject("ops-1", def.capabilities(), def.dataScopes()));
            }

            @Override
            public Optional<String> issuer() {
                return Optional.ofNullable(issuer);
            }
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        System.clearProperty("mcp.publicOrigin");
        System.clearProperty("auth.oidc.scopes");
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        System.setProperty("assist.write.root", Files.createDirectories(dir.resolve("root")).toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> postMcp(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).build(),
                BodyHandlers.ofString());
    }

    @Test
    void theMetadataNamesTheIssuerWithoutSignInAndThe401PointsAtIt(@TempDir Path dir) throws Exception {
        arm(ISSUER);
        System.setProperty("auth.oidc.scopes", "openid profile");
        try (Ctx c = open(dir)) {
            HttpResponse<String> meta = get(c.port, McpRoutes.METADATA);
            assertEquals(200, meta.statusCode(), meta.body());
            JsonNode doc = JSON.readTree(meta.body());
            assertEquals("http://localhost:" + c.port + "/api/v1/mcp", doc.get("resource").asText(), meta.body());
            assertEquals(ISSUER, doc.at("/authorization_servers/0").asText());
            assertEquals(List.of("openid", "profile"), List.of(doc.at("/scopes_supported/0").asText(), doc.at("/scopes_supported/1").asText()));
            assertEquals("header", doc.at("/bearer_methods_supported/0").asText());

            HttpResponse<String> refused = postMcp(c.port);
            assertEquals(401, refused.statusCode(), refused.body());
            assertEquals(Optional.of("Bearer resource_metadata=\"http://localhost:" + c.port + "/api/v1" + McpRoutes.METADATA + "\""),
                    refused.headers().firstValue("WWW-Authenticate"));
            HttpResponse<String> other = get(c.port, "/pipelines");
            assertEquals(401, other.statusCode());
            assertTrue(other.headers().firstValue("WWW-Authenticate").isEmpty(), "only the MCP endpoint's 401 points at it");
        }
    }

    @Test
    void aPinnedPublicOriginWinsOverTheHostHeader(@TempDir Path dir) throws Exception {
        arm(ISSUER);
        System.setProperty("mcp.publicOrigin", "https://inspecto.example.com/");
        try (Ctx c = open(dir)) {
            JsonNode doc = JSON.readTree(get(c.port, McpRoutes.METADATA).body());
            assertEquals("https://inspecto.example.com/api/v1/mcp", doc.get("resource").asText());
            assertEquals(Optional.of("Bearer resource_metadata=\"https://inspecto.example.com/api/v1" + McpRoutes.METADATA + "\""),
                    postMcp(c.port).headers().firstValue("WWW-Authenticate"));
        }
    }

    @Test
    void withoutAnIssuerThereIsNoMetadataAndNoChallenge(@TempDir Path dir) throws Exception {
        arm(null);
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, McpRoutes.METADATA).statusCode());
            HttpResponse<String> refused = postMcp(c.port);
            assertEquals(401, refused.statusCode());
            assertTrue(refused.headers().firstValue("WWW-Authenticate").isEmpty());
        }
    }
}
