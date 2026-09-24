package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC review F3 over real HTTP: {@code ApiContext.ip} — the per-IP rate-limit key and the audit trail's
 * {@code ip} — must not take {@code X-Forwarded-For} from an arbitrary caller. Observed through the public
 * delivery-status callback's per-IP bucket: an unknown adapter is a cheap 404 that still spends a token, so
 * whether rotating XFF values escape the throttle is exactly whether XFF was believed.
 */
class ControlApiClientIpTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void clear() { System.clearProperty(TrustedProxies.PROPERTY); }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private int callback(int port, String forwardedFor) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/public/delivery-status/no-such-provider"))
                .POST(BodyPublishers.ofString("[]"));
        if (forwardedFor != null) b.header("X-Forwarded-For", forwardedFor);
        return client.send(b.build(), BodyHandlers.discarding()).statusCode();
    }

    @Test
    void aSpoofedForwardedForFromAnUntrustedPeerDoesNotEscapeThePerIpThrottle(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {   // default: no trusted proxies
            boolean throttled = false;
            for (int i = 0; i < 400 && !throttled; i++)
                throttled = callback(c.port, "203.0.113." + (i % 250) + ", 198.51.100." + (i / 250)) == 429;
            assertTrue(throttled, "with no trusted proxy configured the socket peer is the key — a fresh "
                    + "X-Forwarded-For per request must not mint a fresh bucket");
        }
    }

    @Test
    void aTrustedProxysForwardedForIsHonoured(@TempDir Path dir) throws Exception {
        System.setProperty(TrustedProxies.PROPERTY, "127.0.0.1, ::1");
        try (Ctx c = open(dir)) {
            for (int i = 0; i < 120; i++)
                assertEquals(404, callback(c.port, "203.0.113." + i),
                        "behind a trusted proxy each distinct client IP has its own bucket (request " + i + ")");
            boolean throttled = false;
            for (int i = 0; i < 400 && !throttled; i++) throttled = callback(c.port, "203.0.113.7") == 429;
            assertTrue(throttled, "…and one forwarded client is still throttled on its own IP");
        }
    }

    @Test
    void anUnparseableTrustedProxyEntryFailsTheBoot(@TempDir Path dir) throws Exception {
        System.setProperty(TrustedProxies.PROPERTY, "10.0.0.0/8, not-an-ip");
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1)) {
            IOException boot = assertThrows(IOException.class, () -> new ControlApi(svc, 0).close());
            assertTrue(boot.getMessage().contains(TrustedProxies.PROPERTY), boot.getMessage());
        }
    }
}
