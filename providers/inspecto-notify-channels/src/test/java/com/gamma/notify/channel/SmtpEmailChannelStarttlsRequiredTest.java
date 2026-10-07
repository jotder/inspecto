package com.gamma.notify.channel;

import com.gamma.notify.Notification;
import org.junit.jupiter.api.Test;

import javax.mail.Session;
import javax.mail.Transport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1} — once {@code starttls} is on, the channel must fail loudly
 * against a relay that never advertises {@code STARTTLS} rather than silently falling back to a plaintext
 * session (which would carry SMTP AUTH credentials in the clear and skip the identity check entirely).
 *
 * <p>Drives a real handshake against a fake relay, as {@link SmtpEmailChannelTlsIdentityTest} does — a test
 * that only inspected {@code Properties} would still pass if javax.mail stopped honouring the required flag.
 */
class SmtpEmailChannelStarttlsRequiredTest {

    private static final String HOST = "127.0.0.1";

    private static Notification sample() {
        return Notification.create("ops", "ALERT_FIRED", "evt-1",
                "Alert: reject-rate-high", "reject_rate 0.4 breached threshold 0.1", "alert:orders:reject-rate-high");
    }

    @Test
    void aRelayThatNeverOffersStarttlsIsRefusedNotFallenBackTo() throws Exception {
        try (NoStarttlsSmtpServer smtp = new NoStarttlsSmtpServer()) {
            Exception e = assertThrows(Exception.class, () -> connect(smtp.port()),
                    "starttls.required=true must refuse a relay that doesn't advertise STARTTLS, "
                            + "not silently send over a plaintext session");
            assertEquals(0, smtp.authSeen(), "no AUTH/credentials must ever reach a relay that refused STARTTLS");
        }
    }

    @Test
    void aRelayThatDoesOfferStarttlsIsUnaffected() throws Exception {
        // Existing behaviour (SmtpEmailChannelTlsIdentityTest.aCertificateForTheConfiguredHostIsAccepted)
        // already proves the required flag doesn't block a relay that DOES support STARTTLS with a valid
        // cert; here we only need the plain non-TLS control path (starttls=false) to stay untouched.
        SmtpEmailChannel ch = new SmtpEmailChannel(HOST, 25,
                "inspecto@example.com", "ops@example.com", null, null, false);
        Session session = ch.message(sample()).getSession();
        assertEquals(null, session.getProperty("mail.smtp.starttls.required"),
                "starttls not requested at all ⇒ no required flag should appear");
    }

    private void connect(int port) throws Exception {
        SmtpEmailChannel ch = new SmtpEmailChannel(HOST, port,
                "inspecto@example.com", "ops@example.com", "smtp-user", "smtp-pass", true);
        Session session = ch.message(sample()).getSession();
        assertEquals("true", session.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", session.getProperty("mail.smtp.starttls.required"),
                "precondition: STARTTLS required");

        Transport t = session.getTransport("smtp");
        try {
            t.connect(HOST, port, null, null);
        } finally {
            try { t.close(); } catch (Exception ignore) { }
        }
    }

    /** A fake relay whose EHLO capabilities never include {@code STARTTLS} — the opportunistic-fallback trap. */
    private static final class NoStarttlsSmtpServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Thread thread;
        private volatile int authSeen = 0;

        NoStarttlsSmtpServer() throws IOException {
            this.listener = new ServerSocket(0, 1, InetAddress.getByName(HOST));
            this.thread = new Thread(this::serve, "fake-smtp-no-starttls");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() { return listener.getLocalPort(); }

        int authSeen() throws InterruptedException {
            thread.join(5_000);
            return authSeen;
        }

        private void serve() {
            try (Socket plain = listener.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(plain.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = plain.getOutputStream();
                say(out, "220 fake ESMTP\r\n");
                assertNotNull(in.readLine());                          // EHLO
                say(out, "250-fake\r\n250 HELP\r\n");                   // deliberately no STARTTLS
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.regionMatches(true, 0, "AUTH", 0, 4)) authSeen++;
                    if (line.regionMatches(true, 0, "QUIT", 0, 4)) { say(out, "221 Bye\r\n"); break; }
                    say(out, "250 OK\r\n");
                }
            } catch (Exception expectedWhenTheClientRefusesToProceed) {
                // The client is expected to give up once it finds no STARTTLS capability and required=true.
            }
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
