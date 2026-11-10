package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PRODUCT-ASSEMBLY-1 PA-3 over real HTTP: {@code GET /bootstrap} names the Product this server was built as and, where the
 * Space roster is shown, the Apps and the nav this caller sees. The resolution matrix itself is {@link ProductDescriptorTest}.
 * The descriptor comes from {@code -Dproduct.descriptor} here (a test has no bundle {@code lib/} to sit beside).
 */
class ControlApiBootstrapProductTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer valid} holds canOperateRuns; {@code Bearer limited} holds nothing; anything else is anonymous. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer valid".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canOperateRuns")));
        if ("Bearer limited".equals(auth)) return Optional.of(new Subject("guest", Set.of()));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty(ProductDescriptor.PROPERTY);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private JsonNode bootstrap(int port, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bootstrap"));
        if (headers.length > 0) b.headers(headers);
        HttpResponse<String> r = client.send(b.GET().build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static Path descriptor(Path dir) throws Exception {
        Path f = dir.resolve("product.json");
        Files.writeString(f, ProductDescriptorTest.DESCRIPTOR);
        System.setProperty(ProductDescriptor.PROPERTY, f.toString());
        return f;
    }

    private static List<String> navIds(JsonNode product) {
        List<String> out = new ArrayList<>();
        product.get("nav").forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    @Test
    void aServerBuiltWithoutADescriptorReportsNoProduct(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode b = bootstrap(c.port);
            assertTrue(b.has("product"), "the field is always there");
            assertTrue(b.get("product").isNull());
        }
    }

    @Test
    void basicShowsTheProductItsAppsAndTheNavItsGatesAllow(@TempDir Path cfg, @TempDir Path root, @TempDir Path lib) throws Exception {
        descriptor(lib);
        try (Ctx c = open(cfg, root)) {
            JsonNode p = bootstrap(c.port).get("product");
            assertEquals("la", p.get("id").asText());
            assertEquals("Inspecto LA", p.get("brand").get("name").asText());
            assertEquals("la-app", p.get("host").asText());
            JsonNode apps = p.get("apps");
            assertTrue(apps.get(0).get("installed").asBoolean(), "the engine module is ACTIVE in every build");
            assertFalse(apps.get(1).get("installed").asBoolean());
            assertEquals("no-such-module", apps.get(1).get("missing").get(0).asText(), "the missing module is named");
            // No Authenticator (Basic): the capability entry is drawn; the feature entries follow THIS response's features,
            // which report neither featOn nor featOff, so both stay hidden; the uninstalled App contributes nothing.
            assertEquals(List.of("open", "needsCap"), navIds(p));
        }
    }

    @Test
    void beforeSignInOnlyTheProductsIdentityIsShown(@TempDir Path cfg, @TempDir Path root, @TempDir Path lib) throws Exception {
        descriptor(lib);
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            JsonNode p = bootstrap(c.port).get("product");
            assertEquals("Inspecto LA", p.get("title").asText(), "the sign-in page names the Product");
            assertFalse(p.has("apps"), "Apps are inventory, like the Space roster: withheld before sign-in");
            assertFalse(p.has("nav"));
        }
    }

    @Test
    void aSignedInCallerSeesACapabilityEntryOnlyWhenHoldingTheCapability(@TempDir Path cfg, @TempDir Path root, @TempDir Path lib) throws Exception {
        descriptor(lib);
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            assertEquals(List.of("open", "needsCap"), navIds(bootstrap(c.port, "Authorization", "Bearer valid").get("product")));
            assertEquals(List.of("open"), navIds(bootstrap(c.port, "Authorization", "Bearer limited").get("product")),
                    "the same Product, a caller without canOperateRuns: the entry is not drawn");
        }
    }

    @Test
    void aDescriptorThatDoesNotParseFailsTheBootNamingTheFile(@TempDir Path cfg, @TempDir Path root, @TempDir Path lib) throws Exception {
        Path f = lib.resolve("product.json");
        Files.writeString(f, "{\"schema\":\"inspecto.product/1\",\"title\":\"no id\",\"apps\":[]}");
        System.setProperty(ProductDescriptor.PROPERTY, f.toString());
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        try {
            java.io.IOException e = assertThrows(java.io.IOException.class, () -> new ControlApi(svc, 0));
            assertTrue(e.getMessage().contains(f.toString()) && e.getMessage().contains("has no id"), e.getMessage());
        } finally {
            svc.close();
        }
    }
}
