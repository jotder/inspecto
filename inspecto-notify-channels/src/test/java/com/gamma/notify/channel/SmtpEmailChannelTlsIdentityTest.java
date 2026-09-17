package com.gamma.notify.channel;

import com.gamma.notify.Notification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.mail.Session;
import javax.mail.Transport;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code NOTIFY-SMTP-TLS-VERIFY-1} — the STARTTLS session {@link SmtpEmailChannel} builds must verify that
 * the certificate actually names {@code notify.smtp.host}.
 *
 * <p><b>This drives a real handshake against a real STARTTLS server; it does not assert that a
 * {@link java.util.Properties} map contains a key.</b> That distinction is the whole point of the class:
 * {@code mail.smtp.ssl.checkserveridentity} is consumed inside {@code com.sun.mail.util.SocketFetcher},
 * after the handshake, and a test that only reads the property back out of the session would still pass
 * if javax.mail stopped honouring it, if the spelling drifted, or if a future change set it on a session
 * the transport never sees.
 *
 * <p><b>Why there are two tests and not one.</b> A lone "the connection fails" assertion proves nothing —
 * a handshake fails for a dozen reasons (protocol, trust chain, a bug in the fake server below), and a
 * test that passes for the wrong reason is worse than none. So the pair differs in <em>exactly one
 * variable</em>: the {@code subjectAltName} on the server certificate. The matching one connects, the
 * mismatched one is refused. Nothing else changes.
 *
 * <p>⚠ The certificate CHAIN is deliberately taken out of the experiment: both servers present a
 * self-signed cert and the test adds {@code mail.smtp.ssl.trust=*} to the session the channel built, so
 * chain validation cannot be what fails. {@code checkserveridentity} is still whatever the channel itself
 * set — the test never writes it — and javax.mail runs the identity check BEFORE the trust-all hook
 * ({@code SocketFetcher.configureSSLSocket}), which is what makes isolating it this way legitimate.
 *
 * <p><b>Mutation proof (2026-09-17).</b> Deleting the {@code props.put("mail.smtp.ssl.checkserveridentity",
 * "true")} line from {@code SmtpEmailChannel.buildMessage} turns
 * {@link #aCertificateForAnotherNameIsRefused()} RED (the mismatched server connects happily) while the
 * positive control stays green — so this test cannot pass without the fix.
 */
class SmtpEmailChannelTlsIdentityTest {

    /** Placeholder by {@code tools/check-secrets.mjs}'s own rules, and ≥6 chars as PKCS12 requires. */
    private static final String STORE_PASSWORD = "testing";

    /** The host the channel is configured with — so this is the name the certificate has to carry. */
    private static final String HOST = "127.0.0.1";

    private static Notification sample() {
        return Notification.create("ops", "ALERT_FIRED", "evt-1",
                "Alert: reject-rate-high", "reject_rate 0.4 breached threshold 0.1", "alert:orders:reject-rate-high");
    }

    // ── the two tests ────────────────────────────────────────────────────────────────────────────

    @Test
    void aCertificateForAnotherNameIsRefused(@TempDir Path dir) throws Exception {
        // Chain-valid as far as this test is concerned (trust-all below), but it names a different host —
        // exactly the certificate a party who can answer for notify.smtp.host is able to obtain legitimately.
        SSLContext server = serverContext(dir, "wrong", "CN=not-our-relay.invalid", "dns:not-our-relay.invalid");

        try (FakeStarttlsSmtpServer smtp = new FakeStarttlsSmtpServer(server)) {
            Exception e = assertThrows(Exception.class, () -> connect(smtp.port()),
                    "a STARTTLS session whose certificate does not name notify.smtp.host must NOT be used "
                            + "to send SMTP AUTH credentials");
            assertTrue(messageChain(e).contains("Can't verify identity of server"),
                    "the refusal must be the server-identity check, not some unrelated handshake failure: "
                            + messageChain(e));
        }
    }

    @Test
    void aCertificateForTheConfiguredHostIsAccepted(@TempDir Path dir) throws Exception {
        // The positive control: same server, same trust-all, same channel — only the SAN differs.
        SSLContext server = serverContext(dir, "right", "CN=" + HOST, "ip:" + HOST);

        try (FakeStarttlsSmtpServer smtp = new FakeStarttlsSmtpServer(server)) {
            connect(smtp.port());  // throws on failure; reaching here is the assertion
            assertTrue(smtp.negotiatedTls(), "the control must actually have completed a TLS handshake — "
                    + "otherwise it proves nothing about verification");
        }
    }

    // ── driving the channel ──────────────────────────────────────────────────────────────────────

    /**
     * Connect a transport over the session {@link SmtpEmailChannel} itself built for {@code HOST}. Note the
     * channel is constructed with credentials: they are what is at stake, and their presence is what makes
     * {@code mail.smtp.auth} part of the session under test.
     */
    private void connect(int port) throws Exception {
        SmtpEmailChannel ch = new SmtpEmailChannel(HOST, port,
                "inspecto@example.com", "ops@example.com", "smtp-user", "smtp-pass", true);
        Session session = ch.message(sample()).getSession();
        assertEquals("true", session.getProperty("mail.smtp.starttls.enable"), "precondition: STARTTLS on");

        // Orthogonal knob, set by the TEST and not by the channel: take chain validation out of the
        // experiment so the only thing that can refuse the handshake is the name on the certificate.
        session.getProperties().put("mail.smtp.ssl.trust", "*");

        Transport t = session.getTransport("smtp");
        try {
            t.connect(HOST, port, null, null);
        } finally {
            try { t.close(); } catch (Exception ignore) { }
        }
    }

    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) sb.append(c).append(" | ");
        return sb.toString();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** A self-signed PKCS12 for {@code dname}/{@code san}, produced by the JDK's own keytool — never committed. */
    private static SSLContext serverContext(Path dir, String alias, String dname, String san) throws Exception {
        Path ks = dir.resolve(alias + ".p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().startsWith("win") ? "keytool.exe" : "keytool");
        Process p = new ProcessBuilder(keytool.toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-dname", dname + ",OU=test,O=test,C=US",
                "-ext", "san=" + san)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "keytool failed to generate the test keystore: " + out);
        assertTrue(Files.exists(ks), "keytool reported success but wrote no keystore: " + out);

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ks)) {
            store.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, STORE_PASSWORD.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /**
     * The smallest SMTP server that can get javax.mail through a STARTTLS upgrade: greeting, EHLO
     * capabilities including {@code STARTTLS}, the {@code 220} go-ahead, then the TLS wrap. It exists
     * because the property under test is only read during that upgrade — nothing short of a real
     * handshake exercises it. One connection, then done.
     */
    private static final class FakeStarttlsSmtpServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread thread;
        private final AtomicReference<Boolean> tls = new AtomicReference<>(false);

        FakeStarttlsSmtpServer(SSLContext ctx) throws IOException {
            this.listener = new ServerSocket(0, 1, InetAddress.getByName(HOST));
            this.thread = new Thread(() -> serve(ctx), "fake-smtp");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() { return listener.getLocalPort(); }

        /** True once a client completed the TLS handshake — the positive control's proof it was real TLS. */
        boolean negotiatedTls() throws InterruptedException {
            thread.join(5_000);
            return tls.get();
        }

        private void serve(SSLContext ctx) {
            try (Socket plain = listener.accept()) {
                BufferedReader in = reader(plain);
                OutputStream out = plain.getOutputStream();
                say(out, "220 fake ESMTP\r\n");
                assertNotNull(in.readLine());                       // EHLO
                say(out, "250-fake\r\n250-STARTTLS\r\n250 HELP\r\n");
                assertNotNull(in.readLine());                       // STARTTLS
                say(out, "220 Ready to start TLS\r\n");

                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                        .createSocket(plain, HOST, plain.getPort(), false);
                ssl.setUseClientMode(false);
                ssl.startHandshake();
                tls.set(true);

                BufferedReader sin = reader(ssl);
                OutputStream sout = ssl.getOutputStream();
                for (String line; (line = sin.readLine()) != null; ) {
                    if (line.regionMatches(true, 0, "QUIT", 0, 4)) { say(sout, "221 Bye\r\n"); break; }
                    say(sout, line.regionMatches(true, 0, "EHLO", 0, 4) ? "250-fake\r\n250 HELP\r\n" : "250 OK\r\n");
                }
            } catch (Exception expectedWhenTheClientRefusesUs) {
                // The mismatched-certificate case ends here: the client tears the socket down the moment the
                // identity check fails, which is precisely the behaviour the negative test asserts.
            }
        }

        private static BufferedReader reader(Socket s) throws IOException {
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        }

        private static void say(OutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override public void close() throws IOException {
            listener.close();
        }
    }

}
