package com.gamma.notify.channel;

import com.gamma.pipeline.exec.WebhookSinkTransport;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pinning the address does not weaken TLS (ASSURE-ACTION-REQUESTS-1, verification finding 1b): over a real
 * {@link HttpsServer} whose certificate names {@code tickets.test}, a request for {@code tickets.test} pinned to
 * 127.0.0.1 is verified against that name and sends it as SNI; the same address under any other name fails the
 * handshake. The certificate is minted by the JDK's own {@code keytool} into a temp keystore.
 */
class PinnedHttpTlsTest {

    private static HttpsServer server;
    private static SSLContext clientTls;
    private static final List<String> sni = new CopyOnWriteArrayList<>();
    private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

    @BeforeAll
    static void start() throws Exception {
        Path dir = Files.createTempDirectory("pinned-tls");
        Path ks = dir.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "srv", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=tickets.test", "-ext", "SAN=dns:tickets.test", "-validity", "2", "-storetype", "PKCS12",
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
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls) {
            @Override public void configure(com.sun.net.httpserver.HttpsParameters params) {
                javax.net.ssl.SSLParameters sp = serverTls.getDefaultSSLParameters();
                sp.setSNIMatchers(List.of(new javax.net.ssl.SNIMatcher(0) {
                    @Override public boolean matches(javax.net.ssl.SNIServerName name) {
                        sni.add(new String(name.getEncoded(), StandardCharsets.US_ASCII));
                        return true;
                    }
                }));
                params.setSSLParameters(sp);
            }
        });
        server.createContext("/api", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = "secure".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static WebhookSinkTransport.Response call(String name) throws Exception {
        return new HttpWebhookSinkTransport(clientTls.getSocketFactory()).exchange("POST",
                URI.create("https://" + name + ":" + server.getAddress().getPort() + "/api"), LOOPBACK, null,
                Duration.ofSeconds(5), "{}", Map.of(), 100);
    }

    @Test
    void theCertificateIsVerifiedAgainstTheNameAndTheNameTravelsAsSni() throws Exception {
        sni.clear();
        WebhookSinkTransport.Response r = call("tickets.test");
        assertEquals(200, r.status());
        assertEquals("secure", r.bodyExcerpt());
        assertTrue(sni.contains("tickets.test"), "SNI carried the name, not the pinned address: " + sni);
    }

    @Test
    void theSameAddressUnderAnotherNameFailsTheHandshake() {
        assertThrows(javax.net.ssl.SSLHandshakeException.class, () -> call("other.test"));
    }
}
