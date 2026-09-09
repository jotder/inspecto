package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SEC-4} — HTTPS over the pure-JDK {@code HttpsServer} + keystore. A **Must (S)** that the security
 * spec itself marks "✅ SHIPPED — ⚠ untested", and one of {@code SPEC-NOPROOF-1}'s six Musts: measured,
 * <b>no</b> test under {@code inspecto/src/test} referenced {@code keystore}, {@code HttpsServer} or
 * {@code SSLContext}.
 *
 * <p><b>The property that matters most is fail-closed, not the happy path.</b> A bad keystore must
 * <b>throw</b>, never silently fall back to plain HTTP: an operator who set {@code -Dhttps.keystore},
 * mistyped the password and got a working server on the same port would believe the control plane was
 * encrypted when every request was in the clear. {@code createServer} throws
 * {@code IOException("failed to configure HTTPS from -Dhttps.keystore=…")} for exactly that reason, and
 * that behaviour had no proof.
 *
 * <p>⚠ <b>The keystore is GENERATED per test by {@code keytool}, never committed.</b> The JDK ships
 * {@code keytool}, so this stays offline and no key material enters the repository — and the store
 * password is the literal {@code testing}, which {@code tools/check-secrets.mjs} treats as a placeholder
 * by design ("test fixtures using {@code password: \"test\"} are not the risk this guard exists for").
 *
 * <p><b>What writing this test found, and mutation-proved 2026-09-09.</b> The wrong-password case did NOT
 * name the property: {@code KeyStore.load} signals a bad password as a plain {@code IOException}
 * ("keystore password was incorrect"), and the catch covered only {@code GeneralSecurityException}, so the
 * diagnostic was lost on the likeliest operator mistake. Fixed in the same change by widening the catch —
 * and the bind was moved OUTSIDE it, because a port already in use is not a keystore problem. The test
 * failed before that fix and passes after, which is the first proof. The second is the dangerous
 * behaviour: making a broken keystore fall back silently to plain HTTP fails <b>2 of 5</b> here — both
 * fail-closed tests — so the shape that would hand an operator a cleartext server on the TLS port cannot
 * land unnoticed.
 *
 * <p>⚠ <b>The client verifies the certificate properly</b> — its trust store is the same PKCS12 the server
 * presents from, so the handshake is a real verification. ⛔ Deliberately NOT a trust-all
 * {@code TrustManager}: a test for a TLS feature that disables certificate checking proves the socket
 * opened, not that TLS works, and it would sit in the repository as a copyable example of the wrong thing.
 */
class ControlApiHttpsTest {

    /** A placeholder by `check-secrets.mjs`'s own rules, and ≥6 chars as PKCS12 requires. */
    private static final String STORE_PASSWORD = "testing";

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /**
     * Boot a server. {@code keystore == null} ⇒ plain HTTP. The HTTPS properties are read ONCE inside
     * {@code createServer} during construction, so they are set only around it and cleared in a finally —
     * never left set for a sibling test in this fork.
     */
    private Ctx open(Path configDir, Path keystore, String password) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (keystore != null) {
            System.setProperty("https.keystore", keystore.toString());
            System.setProperty("https.keystore.password", password);
        }
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("https.keystore");
            System.clearProperty("https.keystore.password");
        }
    }

    /** A self-signed PKCS12 for {@code localhost}, produced by the JDK's own keytool. */
    private static Path generateKeystore(Path dir) throws Exception {
        Path ks = dir.resolve("test-https.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().startsWith("win") ? "keytool.exe" : "keytool");
        Process p = new ProcessBuilder(keytool.toString(),
                "-genkeypair", "-alias", "inspecto", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", "CN=localhost,OU=test,O=test,C=US",
                "-ext", "san=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "keytool failed to generate the test keystore: " + out);
        assertTrue(Files.exists(ks), "keytool reported success but wrote no keystore: " + out);
        return ks;
    }

    /** A client whose TRUST store is that same keystore — a real verification, not a bypass. */
    private static HttpClient tlsClientTrusting(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, STORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASSWORD.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
    }

    // ── the baseline: no keystore means plain HTTP, unchanged (Personal) ──────────────────────────

    @Test
    void plainHttpIsTheDefaultWhenNoKeystoreIsConfigured(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null, null)) {
            HttpResponse<String> r = get(HttpClient.newHttpClient(),
                    "http://localhost:" + c.port + "/api/v1/health");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.sslSession().isEmpty(), "the default transport must NOT be TLS");
        }
    }

    // ── SEC-4 proper ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aKeystoreMakesTheControlPlaneServeOverTls(@TempDir Path dir, @TempDir Path ksDir) throws Exception {
        Path keystore = generateKeystore(ksDir);
        try (Ctx c = open(dir, keystore, STORE_PASSWORD)) {
            HttpResponse<String> r = get(tlsClientTrusting(keystore),
                    "https://localhost:" + c.port + "/api/v1/health");
            assertEquals(200, r.statusCode(), "SEC-4: the control plane must answer over HTTPS: " + r.body());

            Optional<SSLSession> session = r.sslSession();
            assertTrue(session.isPresent(), "the response must carry a TLS session");
            assertEquals("TLSv1.3", session.get().getProtocol(),
                    "createServer pins SSLContext.getInstance(\"TLSv1.3\") — a silent downgrade to 1.2 "
                            + "would still pass a naive 'it responded' check");
            assertNotNull(session.get().getCipherSuite());
        }
    }

    @Test
    void plainHttpDoesNotWorkOnceTlsIsConfigured(@TempDir Path dir, @TempDir Path ksDir) throws Exception {
        Path keystore = generateKeystore(ksDir);
        try (Ctx c = open(dir, keystore, STORE_PASSWORD)) {
            assertThrows(IOException.class,
                    () -> get(HttpClient.newHttpClient(), "http://localhost:" + c.port + "/api/v1/health"),
                    "a cleartext request to a TLS listener must fail, not be served — otherwise the "
                            + "transport would be whatever the caller chose");
        }
    }

    // ── fail-closed: the half an operator's safety actually rests on ──────────────────────────────

    @Test
    void aWrongKeystorePasswordFailsClosedAndNamesTheProperty(@TempDir Path dir, @TempDir Path ksDir)
            throws Exception {
        Path keystore = generateKeystore(ksDir);
        IOException e = assertThrows(IOException.class, () -> open(dir, keystore, "wrong-password"),
                "⛔ a bad password must THROW. Falling back to plain HTTP would hand the operator a "
                        + "working server on the expected port with every request in the clear");
        assertTrue(e.getMessage().contains("https.keystore"),
                "the failure must name the property to fix: " + e.getMessage());
    }

    @Test
    void aMissingKeystoreFileFailsClosedToo(@TempDir Path dir, @TempDir Path ksDir) throws Exception {
        Path absent = ksDir.resolve("no-such-store.p12");
        assertThrows(IOException.class, () -> open(dir, absent, STORE_PASSWORD),
                "a keystore path that does not exist must refuse to start, not start without TLS");
    }
}
