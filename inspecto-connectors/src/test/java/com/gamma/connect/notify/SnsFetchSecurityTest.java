package com.gamma.connect.notify;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🔒 D8-SES-SNS-1 slice S3 — the outbound certificate fetch and the subscription confirmation an UNAUTHENTICATED
 * caller can cause. Proof tests for the design's T-S7, T-S11–T-S19 and the adversarial review (redirects, DNS
 * rebinding, {@code SigningCertURL} host tricks, oversized bodies, slow-loris).
 *
 * <p>Never touches amazonaws.com: a real {@link HttpsServer} on loopback presents a certificate for
 * {@code sns.us-east-1.amazonaws.com} issued by the test CA; the fetcher's resolver seam answers a PUBLIC address
 * for that name (so the egress policy passes it) and its dial seam maps that checked address to the stub. Every
 * refusal test first shows its control succeeding, so no test passes against a fetcher that refuses everything.
 */
class SnsFetchSecurityTest {

    private static final String HOST = "sns.us-east-1.amazonaws.com";
    private static final InetAddress PUBLIC = InetAddress.ofLiteral("52.94.0.10");
    private static HttpsServer server;
    private static SSLSocketFactory clientTls;
    private static final List<String> hits = new CopyOnWriteArrayList<>();
    private static volatile String mode = "cert";

    private final AtomicInteger resolves = new AtomicInteger();
    private final List<InetAddress> dialled = new CopyOnWriteArrayList<>();
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    @BeforeAll
    static void start() throws Exception {
        SnsTestPki.Leaf tls = SnsTestPki.tlsServer(HOST);
        String signerPem = SnsTestPki.signer().pem();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(SnsTestPki.serverKeyStore(tls), SnsTestPki.password());
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", SnsTestPki.ca());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), null);
        clientTls = clientCtx.getSocketFactory();

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            hits.add(ex.getRequestURI().toString());
            try (ex) {
                switch (mode) {
                    case "redirect" -> {
                        ex.getResponseHeaders().add("Location", "https://169.254.169.254/latest/meta-data/");
                        ex.sendResponseHeaders(302, -1);
                    }
                    case "notfound" -> ex.sendResponseHeaders(404, -1);
                    case "big" -> {
                        byte[] b = new byte[20 * 1024];
                        ex.sendResponseHeaders(200, b.length);
                        ex.getResponseBody().write(b);
                    }
                    case "big-chunked" -> {
                        ex.sendResponseHeaders(200, 0);   // chunked, no declared length
                        OutputStream out = ex.getResponseBody();
                        for (int i = 0; i < 20; i++) out.write(new byte[1024]);
                    }
                    case "slow" -> {
                        ex.sendResponseHeaders(200, 0);
                        OutputStream out = ex.getResponseBody();
                        for (int i = 0; i < 30; i++) {
                            out.write('-');
                            out.flush();
                            Thread.sleep(500);
                        }
                    }
                    case "confirm" -> {
                        byte[] b = "<ConfirmSubscriptionResponse><SubscriptionArn>arn:x</SubscriptionArn></ConfirmSubscriptionResponse>"
                                .getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, b.length);
                        ex.getResponseBody().write(b);
                    }
                    default -> {
                        byte[] b = signerPem.getBytes(StandardCharsets.US_ASCII);
                        ex.sendResponseHeaders(200, b.length);
                        ex.getResponseBody().write(b);
                    }
                }
            } catch (InterruptedException | IOException ignored) {
                // the client hung up — the point of the slow case
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @AfterEach
    void reset() {
        mode = "cert";
        hits.clear();
    }

    private SnsHttpsFetcher fetcher(InetAddress[] answer, boolean allowPrivate, Duration deadline) {
        return new SnsHttpsFetcher(clientTls, h -> {
            resolves.incrementAndGet();
            return answer;
        }, a -> {
            dialled.add(a);
            return new InetSocketAddress("127.0.0.1", server.getAddress().getPort());
        }, allowPrivate, false, deadline);
    }

    private SnsHttpsFetcher fetcher() {
        return fetcher(new InetAddress[] { PUBLIC }, false, SnsHttpsFetcher.DEADLINE);
    }

    private SnsSigningCerts certs(SnsSigningCerts.Fetcher f, int budget) throws Exception {
        return new SnsSigningCerts(f, SnsCertTrust.anchoredAt(List.of(SnsTestPki.ca())), null,
                new SnsSigningCerts.Budget(budget, clock::get), clock::get);
    }

    private static final URI CERT = URI.create(SnsFixtures.CERT_URL);

    private static String certUrl(int n) {
        return "https://" + HOST + "/SimpleNotificationService-" + String.format("%032x", n) + ".pem";
    }

    private static SnsEnvelope env(String certUrl) throws Exception {
        return SnsEnvelope.parse(SnsFixtures.sign(SnsFixtures.notification("delivery"), "2", SnsTestPki.signer().key(),
                e -> e.addProperty("SigningCertURL", certUrl)));
    }

    // ---- end to end: a genuine message verifies through the real fetcher ------------------------------------

    @Test
    void aSignedMessageVerifiesEndToEndOverTheRealFetcherAndTheCertificateIsCached() throws Exception {
        var a = new SesSnsDeliveryStatusAdapter(Set.of(SnsFixtures.TOPIC), certs(fetcher(), 12), e -> {}, 3600, 300);
        assertTrue(a.verify(SnsFixtures.sign(SnsFixtures.notification("bounce-permanent")), Map.of()));
        assertTrue(a.verify(SnsFixtures.sign(SnsFixtures.notification("complaint")), Map.of()));
        assertEquals(1, hits.size(), "the second message is served from the cache: " + hits);
        assertEquals(List.of(PUBLIC), dialled, "the socket went to the CHECKED address");
    }

    // ---- T-S11: SigningCertURL host tricks, all refused BEFORE the fetcher --------------------------------

    @Test
    void everyHostTrickIsRefusedBeforeAnyRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SnsSigningCerts c = certs(u -> { calls.incrementAndGet(); return fetcher().get(u); }, 100);
        c.certificateFor(env(SnsFixtures.CERT_URL));   // control
        c.certificateFor(env("https://" + HOST + ":443/SimpleNotificationService-0000000000000000000000000000000c.pem"));
        assertEquals(2, calls.get(), "controls: the plain URL and an explicit :443 are both served");
        calls.set(0);
        String p = "/SimpleNotificationService-0000000000000000000000000000000b.pem";
        List<String> tricks = List.of(
                "https://sns.s3.amazonaws.com" + p,                       // S3 bucket "sns": someone else's content
                "https://sns.us-east-1.amazonaws.com.evil.com" + p,      // suffix
                "https://evil.com/sns.us-east-1.amazonaws.com" + p,
                "https://sns.eu-west-1.amazonaws.com" + p,               // another region than our ARN's
                "https://x@" + HOST + p,                                  // userinfo
                "https://" + HOST + ":@evil.com" + p,
                "https://" + HOST + "@evil.com" + p,
                "https://" + HOST + ":8443" + p,                          // port
                "https://" + HOST + ":0443" + p,
                "http://" + HOST + p,                                     // scheme
                "HTTPS://" + HOST + p,
                "https://" + HOST + "." + p,                              // trailing dot
                "https://SNS.US-EAST-1.AMAZONAWS.COM" + p,                // case
                "https://sns.us-east-1.amazonaws.xn--com-9o0a" + p,       // punycode
                "https://sns.us-east-1.amazonaws.cоm" + p,          // IDN homoglyph (Cyrillic o)
                "https://sns.us-east-1.amazonaws%2ecom" + p,              // percent-encoded dot
                "https://sns.us-east-1.amazonaws.com%00.evil.com" + p,
                "https://[::1]" + p,
                "https://169.254.169.254" + p,
                "https://" + HOST + "/SimpleNotificationService-0000000000000000000000000000000b.pem?x=1",
                "https://" + HOST + "/SimpleNotificationService-0000000000000000000000000000000b.pem#f",
                "https://" + HOST + "/SimpleNotificationService-../../latest/meta-data.pem",
                "https://" + HOST + "/SimpleNotificationService-%30000000000000000000000000000000.pem",
                "https://" + HOST + "/SimpleNotificationService-0000000000000000000000000000000B.pem",
                "https://" + HOST + "/x/SimpleNotificationService-0000000000000000000000000000000b.pem",
                "https://" + HOST + "\\@evil.com" + p,
                "//" + HOST + p,
                "");
        for (String t : tricks) {
            assertThrows(Exception.class, () -> c.certificateFor(env(t)), t);
        }
        assertEquals(0, calls.get(), "no trick reached the network");
    }

    @Test
    void theHostComesFromTheArnPartitionAndNothingElse() {
        assertEquals("sns.us-east-1.amazonaws.com", SnsSigningCerts.expectedHost("arn:aws:sns:us-east-1:123456789012:t"));
        assertEquals("sns.cn-north-1.amazonaws.com.cn", SnsSigningCerts.expectedHost("arn:aws-cn:sns:cn-north-1:123456789012:t"));
        assertEquals("sns.us-gov-west-1.amazonaws.com", SnsSigningCerts.expectedHost("arn:aws-us-gov:sns:us-gov-west-1:123456789012:t"));
        for (String bad : List.of("arn:aws:sns:us-east-1.evil.com:123456789012:t", "arn:aws:sns:s3:123456789012:t",
                "arn:aws:sqs:us-east-1:123456789012:t", "arn:aws-iso:sns:us-iso-east-1:123456789012:t",
                "arn:aws:sns:cn-north-1:123456789012:t", "arn:aws-cn:sns:us-east-1:123456789012:t",
                "arn:aws:sns:us-east-1:123:t", "arn:aws:sns:us-east-1:123456789012:t:extra",
                "arn:aws:sns:us-east-1:123456789012:a/b", "arn:aws:sns::123456789012:t")) {
            assertThrows(SecurityException.class, () -> SnsSigningCerts.expectedHost(bad), bad);
        }
    }

    // ---- T-S12: address classes, and DNS rebinding -----------------------------------------------------------

    @Test
    void internalAddressesAreRefusedAndNothingIsDialled() throws Exception {
        fetcher().get(CERT);   // control
        dialled.clear();
        for (String addr : List.of("127.0.0.1", "169.254.169.254", "::ffff:169.254.169.254", "10.0.0.1",
                "192.168.1.1", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fd00:ec2::254", "64:ff9b::a9fe:a9fe")) {
            InetAddress a = InetAddress.getByName(addr);
            assertThrows(IOException.class, () -> fetcher(new InetAddress[] { a }, false, SnsHttpsFetcher.DEADLINE).get(CERT), addr);
        }
        // one bad answer among good ones refuses the whole name — the platform may connect to any of them
        assertThrows(IOException.class, () -> fetcher(new InetAddress[] { PUBLIC,
                InetAddress.ofLiteral("169.254.169.254") }, false, SnsHttpsFetcher.DEADLINE).get(CERT));
        assertEquals(List.of(), dialled);
    }

    @Test
    void theVpcEndpointOptInLiftsPrivateRangesOnlyNeverMetadataOrLoopback() throws Exception {
        fetcher(new InetAddress[] { InetAddress.ofLiteral("10.0.0.1") }, true, SnsHttpsFetcher.DEADLINE).get(CERT);
        assertEquals(List.of(InetAddress.ofLiteral("10.0.0.1")), dialled, "D3 on: a VPC endpoint's private address");
        dialled.clear();
        for (String addr : List.of("169.254.169.254", "127.0.0.1", "fd00:ec2::254", "::ffff:127.0.0.1")) {
            InetAddress a = InetAddress.getByName(addr);
            assertThrows(IOException.class, () -> fetcher(new InetAddress[] { a }, true, SnsHttpsFetcher.DEADLINE).get(CERT), addr);
        }
        assertEquals(List.of(), dialled);
    }

    @Test
    void dnsRebindingCannotSwapTheCheckedAddress() throws Exception {
        AtomicInteger n = new AtomicInteger();
        SnsHttpsFetcher f = new SnsHttpsFetcher(clientTls, h -> n.getAndIncrement() == 0
                ? new InetAddress[] { PUBLIC } : new InetAddress[] { InetAddress.ofLiteral("127.0.0.1") },
                a -> {
                    dialled.add(a);
                    return new InetSocketAddress("127.0.0.1", server.getAddress().getPort());
                }, false, false, SnsHttpsFetcher.DEADLINE);
        f.get(CERT);
        assertEquals(1, n.get(), "resolved exactly once");
        assertEquals(List.of(PUBLIC), dialled, "and the socket went to that checked answer");
    }

    @Test
    void aServerThatCannotProveTheSnsNameFailsTheHandshake() throws Exception {
        // what a successful rebind reaches: something that answers, but holds no certificate for the SNS host
        SnsTestPki.Leaf other = SnsTestPki.tlsServer("intranet.test");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(SnsTestPki.serverKeyStore(other), SnsTestPki.password());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer impostor = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        impostor.setHttpsConfigurator(new HttpsConfigurator(ctx));
        List<String> got = new CopyOnWriteArrayList<>();
        impostor.createContext("/", ex -> { got.add(ex.getRequestURI().toString()); ex.sendResponseHeaders(200, -1); ex.close(); });
        impostor.start();
        try {
            SnsHttpsFetcher f = new SnsHttpsFetcher(clientTls, h -> new InetAddress[] { PUBLIC },
                    a -> new InetSocketAddress("127.0.0.1", impostor.getAddress().getPort()), false, false,
                    SnsHttpsFetcher.DEADLINE);
            assertThrows(javax.net.ssl.SSLHandshakeException.class, () -> f.get(CERT));
            assertEquals(List.of(), got, "nothing after the ClientHello: no path, no headers");
        } finally {
            impostor.stop(0);
        }
    }

    // ---- T-S13 / T-S14 / T-S15: redirects, size, time --------------------------------------------------------

    @Test
    void aRedirectIsRefusedAndNeverFollowed() throws Exception {
        mode = "redirect";
        IOException e = assertThrows(IOException.class, () -> fetcher().get(CERT));
        assertTrue(e.getMessage().contains("302"), e.getMessage());
        assertEquals(1, hits.size(), "one request, the Location was not followed: " + hits);
    }

    @Test
    void aBodyOverSixteenKibIsRefusedDeclaredOrChunked() throws Exception {
        mode = "big";
        assertThrows(IOException.class, () -> fetcher().get(CERT));
        mode = "big-chunked";
        assertThrows(IOException.class, () -> fetcher().get(CERT));
        mode = "cert";
        fetcher().get(CERT);   // control
    }

    @Test
    void aSlowLorisServerIsCutAtTheDeadline() throws Exception {
        mode = "slow";   // a byte every 500 ms: no single read ever times out
        long t0 = System.nanoTime();
        assertThrows(IOException.class, () -> fetcher().get(CERT));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms >= 4_500 && ms < 8_000, "cut at the 5 s deadline, took " + ms + " ms");
    }

    // ---- T-S16 budget, negative cache, single-flight ---------------------------------------------------------

    @Test
    void theThirteenthDistinctMissInAnHourMakesNoRequest() throws Exception {
        mode = "notfound";
        SnsSigningCerts c = certs(fetcher(), 12);
        for (int i = 1; i <= 13; i++) {
            int n = i;
            assertThrows(Exception.class, () -> c.certificateFor(env(certUrl(n))));
        }
        assertEquals(12, hits.size(), "the budget, not the attacker, bounds outbound traffic");
        clock.addAndGet(3_600_001L);
        assertThrows(Exception.class, () -> c.certificateFor(env(certUrl(14))));
        assertEquals(13, hits.size(), "an hour later the budget refills");
    }

    @Test
    void aFailedUrlIsNotRefetchedForTenMinutes() throws Exception {
        mode = "notfound";
        SnsSigningCerts c = certs(fetcher(), 12);
        assertThrows(Exception.class, () -> c.certificateFor(env(SnsFixtures.CERT_URL)));
        mode = "cert";
        assertThrows(Exception.class, () -> c.certificateFor(env(SnsFixtures.CERT_URL)));
        assertEquals(1, hits.size());
        clock.addAndGet(SnsSigningCerts.NEGATIVE_TTL_MILLIS + 1);
        c.certificateFor(env(SnsFixtures.CERT_URL));
        assertEquals(2, hits.size());
    }

    @Test
    void concurrentMissesShareOneFetch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        SnsSigningCerts c = certs(u -> {
            calls.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return fetcher().get(u);
        }, 12);
        SnsEnvelope e = env(SnsFixtures.CERT_URL);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    c.certificateFor(e);
                } catch (Throwable x) {
                    errors.add(x);
                }
            });
            threads.add(t);
            t.start();
        }
        Thread.sleep(300);
        release.countDown();
        for (Thread t : threads) t.join(10_000);
        assertEquals(List.of(), errors);
        assertEquals(1, calls.get());
    }

    @Test
    void aCertificateThatFailsTrustIsRefusedEvenFromTheRightHost() throws Exception {
        SnsTestPki.Leaf self = SnsTestPki.leaf("self", "CN=sns.amazonaws.com", "dns:sns.amazonaws.com", 2048, null, 3, false);
        SnsSigningCerts c = certs(u -> new SnsSigningCerts.Fetcher.Fetched(self.pem().getBytes(StandardCharsets.US_ASCII),
                List.of()), 12);
        assertThrows(Exception.class, () -> c.certificateFor(env(SnsFixtures.CERT_URL)));
    }

    // ---- T-S19 pinned, T-S7 hostname verification, T-S1 unconfigured -----------------------------------------

    @Test
    void pinnedModeNeverFetchesAndStillValidatesTheUrl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SnsSigningCerts c = new SnsSigningCerts(u -> { calls.incrementAndGet(); throw new IOException("never"); },
                SnsCertTrust.anchoredAt(List.of(SnsTestPki.ca())), List.of(SnsTestPki.signer().cert()),
                new SnsSigningCerts.Budget(12, clock::get), clock::get);
        var a = new SesSnsDeliveryStatusAdapter(Set.of(SnsFixtures.TOPIC), c, e -> {}, 3600, 300);
        assertTrue(a.verify(SnsFixtures.sign(SnsFixtures.notification("delivery")), Map.of()));
        assertThrows(SecurityException.class, () -> c.certificateFor(env("https://sns.s3.amazonaws.com"
                + "/SimpleNotificationService-0000000000000000000000000000000a.pem")));
        assertEquals(0, calls.get());
    }

    @Test
    void hostnameVerificationDisabledStopsTheAdapterArming() {
        System.setProperty("notify.deliverystatus.sns.topicArns", SnsFixtures.TOPIC);
        try {
            System.setProperty(SesSnsDeliveryStatusAdapter.HOSTNAME_VERIFICATION_OFF, "true");
            assertFalse(new SesSnsDeliveryStatusAdapter().configured());
            System.clearProperty(SesSnsDeliveryStatusAdapter.HOSTNAME_VERIFICATION_OFF);
            assertTrue(new SesSnsDeliveryStatusAdapter().configured(), "control: the same config arms without it");
        } finally {
            System.clearProperty(SesSnsDeliveryStatusAdapter.HOSTNAME_VERIFICATION_OFF);
            System.clearProperty("notify.deliverystatus.sns.topicArns");
        }
    }

    @Test
    void theServiceLoaderAdapterIsInertWithoutTopics() {
        var a = new SesSnsDeliveryStatusAdapter();
        assertFalse(a.configured(), "unset topicArns: the callback URL answers 404 and nothing can fetch");
    }

    // ---- T-S18 SubscribeURL ------------------------------------------------------------------------------------

    @Test
    void theSubscribeUrlMustBeExactlyOurTopicsConfirmation() throws Exception {
        mode = "confirm";
        List<Runnable> queued = new ArrayList<>();
        var confirmer = new SnsSubscriptionConfirmer(fetcher(), new SnsSigningCerts.Budget(12, clock::get), true, queued::add);
        JsonObject base = SnsFixtures.envelope("subscription-confirmation");
        String good = base.get("SubscribeURL").getAsString();
        String token = base.get("Token").getAsString();

        confirmer.subscriptionConfirmation(sub(good));   // control
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, hits.size(), "confirmed with one GET");
        assertTrue(hits.get(0).startsWith("/?Action=ConfirmSubscription"), hits.toString());

        String other = "arn:aws:sns:us-east-1:999999999999:theirs";
        for (String bad : List.of(good + "&Extra=1", good + "&Token=" + token, good.replace(SnsFixtures.TOPIC, other),
                good.replace("Action=ConfirmSubscription", "Action=Unsubscribe"), good.replace("Token=" + token, "Token=x"),
                good.replace("https://", "http://"), good.replace(HOST, "sns.us-east-2.amazonaws.com"),
                good.replace(HOST, HOST + ":8443"), good.replace("/?", "/x?"), good + "#f",
                good.replace("https://", "https://u@"), "https://" + HOST + "/")) {
            confirmer.subscriptionConfirmation(sub(bad));
        }
        assertEquals(List.of(), queued, "no refused SubscribeURL was ever queued");
    }

    @Test
    void autoConfirmOffFetchesNothing() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var confirmer = new SnsSubscriptionConfirmer(u -> { calls.incrementAndGet(); throw new IOException(); },
                new SnsSigningCerts.Budget(12, clock::get), false, queued::add);
        confirmer.subscriptionConfirmation(sub(SnsFixtures.envelope("subscription-confirmation")
                .get("SubscribeURL").getAsString()));
        assertEquals(List.of(), queued);
        assertEquals(0, calls.get());
    }

    @Test
    void theConfirmationQueueIsBoundedAtFour() {
        var ex = (java.util.concurrent.ThreadPoolExecutor) SnsSubscriptionConfirmer.boundedExecutor();
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            ex.execute(() -> {
                try {
                    block.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // test teardown
                }
                ran.incrementAndGet();
            });
        }
        assertEquals(4, ex.getQueue().size(), "one running, four queued, five dropped");
        block.countDown();
        ex.shutdown();
    }

    private static SnsEnvelope sub(String subscribeUrl) throws Exception {
        return SnsEnvelope.parse(SnsFixtures.sign(SnsFixtures.envelope("subscription-confirmation"), "2",
                SnsTestPki.signer().key(), e -> e.addProperty("SubscribeURL", subscribeUrl)));
    }
}
