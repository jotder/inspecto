package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.RemoteFile;
import com.gamma.pipeline.exec.EgressPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The object-store connectors dial through the egress policy (OBJECT-STORE-EGRESS-POLICY, 2026-09-27): a host
 * resolving to a private or metadata address is refused and nothing is dialled; an allowlisted host is resolved once
 * and dialled at the CHECKED address, keeping its name for the signed {@code Host}; a 3xx is never followed; bodies
 * stream both ways. The stub listens on loopback; the {@code dial} seam maps the checked address to it, so a
 * missing check would reach the stub and succeed — each refusal test would go green only through the policy.
 */
class ObjectStoreEgressTest {

    private static final String EXPECTED = "ID,AMT" + (char) 10 + "r1,10" + (char) 10;

    private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final InetAddress PRIVATE = InetAddress.ofLiteral("10.1.2.3");
    private static final InetAddress METADATA = InetAddress.ofLiteral("169.254.169.254");

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> hostHeaders = new CopyOnWriteArrayList<>();
    private final List<InetAddress> dialled = new CopyOnWriteArrayList<>();
    private final AtomicInteger resolutions = new AtomicInteger();
    private volatile byte[] received = new byte[0];

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        AbstractHttpObjectStoreConnector.dial = a -> {
            dialled.add(a);
            return LOOPBACK;
        };
    }

    @AfterEach
    void stop() {
        ObjectStoreEgressFixture.reset();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + path);
        hostHeaders.add(ex.getRequestHeaders().getFirst("Host"));
        received = ex.getRequestBody().readAllBytes();
        if (path.endsWith("/redirect.csv")) {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/bucket/in/a.csv");
            ex.sendResponseHeaders(307, -1);
        } else if (path.endsWith("/chunked.csv")) {
            ex.sendResponseHeaders(200, 0);   // 0 ⇒ chunked
            try (OutputStream out = ex.getResponseBody()) {
                for (int i = 0; i < 3; i++) out.write(("part" + i + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } else if ("PUT".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
        } else {
            byte[] body = "ID,AMT\nr1,10\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    private S3Connector connector(String host, String protocol, int port) {
        return new S3Connector(new ConnectionProfile("egress", "s3", host, port, null, "bucket/in", "AKIDEXAMPLE",
                "secret", Map.of("region", "us-east-1", "protocol", protocol), null));
    }

    private S3Connector connector(String host) {
        return connector(host, "http", server.getAddress().getPort());
    }

    private void resolveTo(InetAddress a) {
        AbstractHttpObjectStoreConnector.resolver = h -> {
            resolutions.incrementAndGet();
            return new InetAddress[] {a};
        };
    }

    private static RemoteFile file(String rel) {
        return new RemoteFile(rel, rel, RemoteFile.SIZE_UNKNOWN, null, null, null, null);
    }

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aHostResolvingToAPrivateAddressIsRefusedByDefaultAndNothingIsDialled() {
        resolveTo(PRIVATE);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.EMPTY;
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> connector("store.lan").open(file("a.csv")));
        assertTrue(e.getMessage().contains("egress refused") && e.getMessage().contains("10.1.2.3")
                && e.getMessage().contains("private"), e.getMessage());
        assertEquals(List.of(), dialled, "a refused host is never dialled");
        assertEquals(List.of(), requests);
    }

    @Test
    void theMetadataServiceIsRefusedEvenWhenTheHostIsAllowlisted() {
        resolveTo(METADATA);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of("meta.lan", "10.0.0.0/8"));
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> connector("meta.lan").open(file("a.csv")));
        assertTrue(e.getMessage().contains("link-local"), e.getMessage());
        assertEquals(List.of(), dialled);
        assertEquals(List.of(), requests);
    }

    @Test
    void aNonCanonicalNumericHostIsRefusedBeforeAnyResolution() {
        resolveTo(PRIVATE);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of("10.0.0.0/8"));
        AcquisitionException e = assertThrows(AcquisitionException.class, () -> connector("0x7f000001").open(file("a.csv")));
        assertTrue(e.getMessage().contains("non-canonical"), e.getMessage());
        assertEquals(0, resolutions.get());
        assertEquals(List.of(), dialled);
    }

    @Test
    void anAllowlistedHostIsResolvedOnceAndDialledAtTheCheckedAddressUnderItsSignedName() throws Exception {
        resolveTo(PRIVATE);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of("store.lan"));
        assertEquals("ID,AMT\nr1,10\n", read(connector("store.lan").open(file("a.csv"))));
        assertEquals(List.of(PRIVATE), dialled, "the checked address is the one dialled");
        assertEquals(1, resolutions.get(), "resolved once per request");
        int port = server.getAddress().getPort();
        assertEquals(List.of("store.lan:" + port), hostHeaders, "the Host sent is the name SigV4 signed");
        assertEquals("store.lan:" + port, AwsSigV4.hostHeader(URI.create("http://store.lan:" + port + "/")));
    }

    @Test
    void aRedirectIsNeverFollowed() {
        resolveTo(PRIVATE);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of("store.lan"));
        AcquisitionException e = assertThrows(AcquisitionException.class,
                () -> connector("store.lan").open(file("redirect.csv")));
        assertTrue(e.getMessage().contains("HTTP 307"), e.getMessage());
        assertEquals(List.of("GET /bucket/in/redirect.csv"), requests, "the Location was not dialled");
    }

    @Test
    void chunkedDownloadsStreamAndFileUploadsCarryTheirExactBytes() throws Exception {
        resolveTo(PRIVATE);
        AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of("10.1.0.0/16"));
        S3Connector c = connector("store.lan");
        assertEquals("part0\npart1\npart2\n", read(c.open(file("chunked.csv"))));

        Path upload = tmp.resolve("up.bin");
        byte[] bytes = new byte[200_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        Files.write(upload, bytes);
        String md5 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        c.put("up.bin", upload, md5, sha);
        assertArrayEquals(bytes, received, "the file body arrived whole over the pinned socket");
    }

    /** Pinning the address does not weaken TLS: SNI and certificate verification use the Connection's host name. */
    @Test
    void tlsVerifiesTheCertificateAgainstTheNameNotThePinnedAddress() throws Exception {
        Path ks = tmp.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "srv", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=store.test", "-ext", "SAN=dns:store.test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("srv", store.getCertificate("srv"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);

        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        https.createContext("/", this::handle);
        https.start();
        try {
            resolveTo(PRIVATE);
            AbstractHttpObjectStoreConnector.allowlist = () -> EgressPolicy.Allowlist.of(List.of("10.1.2.3"));
            AbstractHttpObjectStoreConnector.tls = clientTls::getSocketFactory;
            int port = https.getAddress().getPort();
            assertEquals("ID,AMT\nr1,10\n", read(connector("store.test", "https", port).open(file("a.csv"))));
            AcquisitionException e = assertThrows(AcquisitionException.class,
                    () -> connector("other.test", "https", port).open(file("a.csv")));
            assertInstanceOf(javax.net.ssl.SSLHandshakeException.class, e.getCause(), String.valueOf(e.getCause()));
        } finally {
            https.stop(0);
        }
    }

    /**
     * The production allowlist supplier ({@code EgressAllowlist.forCurrentSpace}), not a stub: a single-tenant
     * server's launch-config S3 Connection on a LAN is seeded by the boot migration into the write root, and a
     * Collector-style download on the default Space (no MDC) — and on a pool thread of a NAMED Space whose MDC is
     * propagated the way CollectorProcessor does — both reach the store.
     */
    @Test
    void theRealPerSpaceAllowlistLetsASeededLanStoreThroughOnEveryCollectorThread() throws Exception {
        resolveTo(PRIVATE);
        Path writeRoot = tmp.resolve("write");
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            com.gamma.pipeline.exec.EgressAllowlist.migrate(writeRoot, List.of("store.lan"));
            assertEquals(EXPECTED, read(connector("store.lan").open(file("a.csv"))));

            Path spaceRoot = tmp.resolve("spaceA/config");
            com.gamma.pipeline.exec.EgressAllowlist.migrate(spaceRoot, List.of("store.lan"));
            com.gamma.pipeline.SpaceConfigRoot.register("spaceA", spaceRoot);
            org.slf4j.MDC.put(com.gamma.event.EventLog.SPACE_MDC_KEY, "spaceA");
            Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
            org.slf4j.MDC.clear();
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                String got = pool.submit(() -> {
                    org.slf4j.MDC.setContextMap(mdc);
                    try {
                        return read(connector("store.lan").open(file("a.csv")));
                    } finally {
                        org.slf4j.MDC.clear();
                    }
                }).get();
                assertEquals(EXPECTED, got);
                // negative: a named Space that never allowlisted it is refused on the same thread shape
                Map<String, String> other = Map.of(com.gamma.event.EventLog.SPACE_MDC_KEY, "spaceB");
                var ex = assertThrows(java.util.concurrent.ExecutionException.class, () -> pool.submit(() -> {
                    org.slf4j.MDC.setContextMap(other);
                    try {
                        return read(connector("store.lan").open(file("a.csv")));
                    } finally {
                        org.slf4j.MDC.clear();
                    }
                }).get());
                assertInstanceOf(AcquisitionException.class, ex.getCause());
            }
        } finally {
            com.gamma.pipeline.SpaceConfigRoot.forget("spaceA");
            if (prior == null) System.clearProperty("assist.write.root");
            else System.setProperty("assist.write.root", prior);
        }
    }
    /**
     * Session decision 2026-09-27, end to end on the real supplier: with NO writable config root the launch config's
     * LAN store is trusted in memory and reached; a store the launch config does not name is refused.
     */
    @Test
    void withNoWriteRootTheLaunchConfigsLanStoreIsReachedAndAnotherIsNot() throws Exception {
        resolveTo(PRIVATE);
        String prior = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        org.slf4j.MDC.remove(com.gamma.event.EventLog.SPACE_MDC_KEY);
        Path launch = Files.createDirectories(tmp.resolve("launch"));
        try {
            Files.writeString(launch.resolve("minio_connection.toon"),
                    String.join(String.valueOf((char) 10), "connection:", "  id: minio", "  connector: s3", "  host: store.lan", ""));
            com.gamma.pipeline.exec.EgressAllowlist.bootDefaultSpace(launch, List.of());
            assertEquals(EXPECTED, read(connector("store.lan").open(file("a.csv"))));
            AcquisitionException e = assertThrows(AcquisitionException.class,
                    () -> connector("other.lan").open(file("a.csv")));
            assertTrue(e.getMessage().contains("egress refused"), e.getMessage());
        } finally {
            com.gamma.pipeline.exec.EgressAllowlist.bootDefaultSpace(Files.createDirectories(tmp.resolve("empty")), List.of());
            if (prior != null) System.setProperty("assist.write.root", prior);
        }
    }
}
