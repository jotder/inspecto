package com.gamma.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-BI-PUBLICATION-1 TLS fix: {@link PublishPinnedSocketFactory} dials the checked address whatever host the
 * driver names, and {@link PublishSslFactory} verifies the server certificate against the AUTHORED host — so a cert
 * for {@code bi.example.test} served from the pinned {@code 127.0.0.1} passes, and the same socket presented as
 * any other host fails. A real TLS server on loopback with a keytool-generated certificate; no network.
 */
class PublishTlsTest {

    @AfterEach
    void clear() { System.clearProperty("publish.tls.test.ca"); }

    record Server(SSLServerSocket socket) {}

    static Path keystore(Path dir) throws Exception {
        Path ks = dir.resolve("server.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "pg", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=bi.example.test", "-ext", "SAN=dns:bi.example.test",
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool");
        return ks;
    }

    static Server serve(Path ks) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks.toFile())) { store.load(in, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try (SSLSocket s = (SSLSocket) ss.accept()) {
                    s.startHandshake();
                    s.getOutputStream().write(1);
                } catch (Exception ignored) {
                    // a refused handshake on the client side ends here too
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return new Server(ss);
    }

    static String pem(Path ks) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(ks.toFile())) { store.load(in, "changeit".toCharArray()); }
        return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(store.getCertificate("pg").getEncoded()) + "\n-----END CERTIFICATE-----\n";
    }

    static Properties props(String ca) {
        Properties p = new Properties();
        p.setProperty(PublishPinnedSocketFactory.PINNED, "127.0.0.1");
        if (ca != null) p.setProperty(PublishSslFactory.ROOT_CERT_REF, ca);
        return p;
    }

    /** What pgjdbc does: a socket from the socketFactory, connected to the URL's host, then TLS for that host. */
    static void handshake(Properties p, String host, int port) throws Exception {
        Socket raw = new PublishPinnedSocketFactory(p).createSocket();
        raw.connect(InetSocketAddress.createUnresolved(host, port), 5000);
        assertEquals(InetAddress.getLoopbackAddress(), raw.getInetAddress(), "dialled the pinned address");
        try (SSLSocket s = (SSLSocket) new PublishSslFactory(p).createSocket(raw, host, port, true)) {
            s.startHandshake();
            assertEquals(1, s.getInputStream().read());
        }
    }

    @Test
    void theAuthoredHostVerifiesOverThePinnedAddressAndAnyOtherHostFails(@TempDir Path dir) throws Exception {
        Path ks = keystore(dir);
        System.setProperty("publish.tls.test.ca", pem(ks));
        Server server = serve(ks);
        int port = server.socket().getLocalPort();
        try {
            handshake(props("${SYS:publish.tls.test.ca}"), "bi.example.test", port);
            assertThrows(SSLHandshakeException.class, () -> handshake(props("${SYS:publish.tls.test.ca}"), "evil.example.test", port),
                    "the certificate names bi.example.test, so any other authored host is refused");
            assertThrows(SSLHandshakeException.class, () -> handshake(props(null), "bi.example.test", port),
                    "without the CA reference the JDK roots do not trust a self-signed server");
        } finally {
            server.socket().close();
        }
    }

    @Test
    void aRootCertFilePathIsRefusedAndNoPinnedAddressRefusesTheFactory(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("ca.pem"), "x");
        assertThrows(IllegalArgumentException.class, () -> new PublishSslFactory(props(f.toString())));
        assertThrows(IllegalArgumentException.class, () -> new PublishPinnedSocketFactory(new Properties()));
        Properties host = new Properties();
        host.setProperty(PublishPinnedSocketFactory.PINNED, "bi.example.test");
        assertThrows(IllegalArgumentException.class, () -> new PublishPinnedSocketFactory(host), "an IP literal only");
    }
}
