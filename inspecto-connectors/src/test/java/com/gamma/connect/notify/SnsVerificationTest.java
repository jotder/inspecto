package com.gamma.connect.notify;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D8-SES-SNS-1 slice S1 — the envelope, the signature and the certificate trust, with NO network: the
 * certificate source is a counting fake over the test PKI. Every refusal test runs its control first (the same
 * message untampered verifies), so a test cannot pass by a verifier that rejects everything.
 */
class SnsVerificationTest {

    private static final String HOST = "sns.us-east-1.amazonaws.com";
    private final AtomicInteger lookups = new AtomicInteger();

    private SesSnsDeliveryStatusAdapter adapter() throws Exception {
        X509Certificate cert = SnsTestPki.signer().cert();
        SnsCertTrust trust = SnsCertTrust.anchoredAt(List.of(SnsTestPki.ca()));
        return new SesSnsDeliveryStatusAdapter(Set.of(SnsFixtures.TOPIC), env -> {
            lookups.incrementAndGet();
            trust.check(cert, List.of(), HOST, System.currentTimeMillis());
            return cert;
        }, env -> {}, 3600, 300);
    }

    private static byte[] signed(String fixture, Consumer<JsonObject> tweak) throws Exception {
        JsonObject env = "notification".equals(fixture) ? SnsFixtures.notification("bounce-permanent")
                : SnsFixtures.envelope(fixture);
        return SnsFixtures.sign(env, "2", SnsTestPki.signer().key(), tweak);
    }

    // ---- T-S2 / T-S4: valid verifies; any signed key changed does not -------------------------------------

    @Test
    void aValidVersion2MessageOfEachTypeVerifies() throws Exception {
        var a = adapter();
        for (String f : List.of("notification", "subscription-confirmation", "unsubscribe-confirmation")) {
            assertTrue(a.verify(signed(f, e -> {}), Map.of()), f);
        }
    }

    @Test
    void changingAnySignedKeyAfterSigningIsRefused() throws Exception {
        var a = adapter();
        assertTrue(a.verify(signed("notification", e -> {}), Map.of()), "control");
        for (String key : List.of("Message", "MessageId", "Subject", "Timestamp", "Type")) {
            byte[] raw = signed("notification", e -> {});
            JsonObject env = com.google.gson.JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            String v = env.get(key).getAsString();
            env.addProperty(key, key.equals("Timestamp") ? Instant.now().minusSeconds(1).toString()
                    : key.equals("Type") ? "UnsubscribeConfirmation" : v + "x");
            assertFalse(a.verify(env.toString().getBytes(StandardCharsets.UTF_8), Map.of()), key);
        }
        for (String key : List.of("SubscribeURL", "Token")) {
            assertTrue(a.verify(signed("subscription-confirmation", e -> {}), Map.of()), "control " + key);
            byte[] raw = signed("subscription-confirmation", e -> {});
            JsonObject env = com.google.gson.JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            env.addProperty(key, env.get(key).getAsString() + "0");
            assertFalse(a.verify(env.toString().getBytes(StandardCharsets.UTF_8), Map.of()), key);
        }
    }

    @Test
    void aSignatureByAnotherKeyIsRefused() throws Exception {
        var a = adapter();
        var gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        byte[] forged = SnsFixtures.sign(SnsFixtures.notification("delivery"), "2", gen.generateKeyPair().getPrivate(), e -> {});
        assertFalse(a.verify(forged, Map.of()));
    }

    // ---- T-S3: SignatureVersion 1 ---------------------------------------------------------------------------

    @Test
    void version1IsRefusedEvenWhenItsSha1SignatureIsGenuine() throws Exception {
        var a = adapter();
        byte[] v1 = SnsFixtures.sign(SnsFixtures.notification("delivery"), "1", SnsTestPki.signer().key(), e -> {});
        assertFalse(a.verify(v1, Map.of()));
        byte[] unknown = SnsFixtures.sign(SnsFixtures.notification("delivery"), "3", SnsTestPki.signer().key(), e -> {});
        assertFalse(a.verify(unknown, Map.of()));
        assertEquals(0, lookups.get(), "a refused version never reaches the certificate source");
    }

    // ---- T-S5: freshness, both directions -------------------------------------------------------------------

    @Test
    void theFreshnessWindowIs3600PastAnd300Future() throws Exception {
        var a = adapter();
        assertTrue(a.verify(signed("notification", e -> e.addProperty("Timestamp",
                Instant.now().minusSeconds(3500).toString())), Map.of()), "control: an SNS retry 58 min later");
        assertFalse(a.verify(signed("notification", e -> e.addProperty("Timestamp",
                Instant.now().minusSeconds(3700).toString())), Map.of()));
        assertTrue(a.verify(signed("notification", e -> e.addProperty("Timestamp",
                Instant.now().plusSeconds(200).toString())), Map.of()), "control: small future skew");
        assertFalse(a.verify(signed("notification", e -> e.addProperty("Timestamp",
                Instant.now().plusSeconds(400).toString())), Map.of()));
    }

    // ---- the allowlist precedes any certificate lookup ------------------------------------------------------

    @Test
    void aTopicNotOnTheAllowlistNeverReachesTheCertificateSource() throws Exception {
        var a = adapter();
        assertTrue(a.verify(signed("notification", e -> {}), Map.of()), "control");
        int before = lookups.get();
        assertFalse(a.verify(signed("notification", e -> e.addProperty("TopicArn",
                "arn:aws:sns:us-east-1:999999999999:someone-else")), Map.of()));
        assertEquals(before, lookups.get());
    }

    @Test
    void anUnconfiguredAdapterVerifiesNothing() throws Exception {
        var a = new SesSnsDeliveryStatusAdapter(Set.of(), env -> { throw new AssertionError("never"); }, env -> {}, 3600, 300);
        assertFalse(a.configured());
        assertFalse(a.verify(signed("notification", e -> {}), Map.of()));
    }

    // ---- the envelope parser --------------------------------------------------------------------------------

    @Test
    void theParserRefusesNestingDuplicatesNonStringsAndUnknownTypes() throws Exception {
        String ok = new String(signed("notification", e -> {}), StandardCharsets.UTF_8);
        assertNotNull(SnsEnvelope.parse(ok.getBytes(StandardCharsets.UTF_8)), "control");
        assertNull(SnsEnvelope.parse(ok.replaceFirst("\\{", "{\"x\":{\"y\":[1]},").getBytes(StandardCharsets.UTF_8)));
        assertNull(SnsEnvelope.parse(ok.replaceFirst("\\{", "{\"Type\":\"Notification\",").getBytes(StandardCharsets.UTF_8)));
        assertNull(SnsEnvelope.parse(ok.replaceFirst("\\{", "{\"n\":1,").getBytes(StandardCharsets.UTF_8)));
        assertNull(SnsEnvelope.parse(ok.replace("\"Notification\"", "\"Other\"").getBytes(StandardCharsets.UTF_8)));
        assertNull(SnsEnvelope.parse((ok + "{}").getBytes(StandardCharsets.UTF_8)));
        assertNull(SnsEnvelope.parse("[]".getBytes(StandardCharsets.UTF_8)));
        assertNull(SnsEnvelope.parse(null));
    }

    @Test
    void theStringToSignMatchesTheIndependentFixtureImplementation() throws Exception {
        for (String f : List.of("notification", "subscription-confirmation", "unsubscribe-confirmation")) {
            byte[] raw = signed(f, e -> {});
            JsonObject env = com.google.gson.JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(SnsFixtures.stringToSign(env), SnsEnvelope.parse(raw).stringToSign(), f);
        }
        // Subject is optional on a Notification, and absent means absent from the string
        byte[] noSubject = signed("notification", e -> e.remove("Subject"));
        assertFalse(SnsEnvelope.parse(noSubject).stringToSign().contains("Subject\nAmazon SES"));
        assertTrue(adapter().verify(noSubject, Map.of()));
    }

    // ---- T-S17: certificate trust ---------------------------------------------------------------------------

    @Test
    void certificateTrustRefusesUnchainedExpiredMisnamedAndWeakCertificates() throws Exception {
        SnsCertTrust trust = SnsCertTrust.anchoredAt(List.of(SnsTestPki.ca()));
        var selfSigned = SnsTestPki.leaf("self", "CN=sns.amazonaws.com", "dns:sns.amazonaws.com", 2048, null, 3, false);
        var expired = SnsTestPki.leaf("expired", "CN=sns.amazonaws.com", "dns:sns.amazonaws.com", 2048, "-10d", 2, true);
        var misnamed = SnsTestPki.leaf("misnamed", "CN=sns.amazonaws.com", "dns:sns.s3.amazonaws.com", 2048, null, 3, true);
        var weak = SnsTestPki.leaf("weak", "CN=sns.amazonaws.com", "dns:sns.amazonaws.com", 1024, null, 3, true);
        var regional = SnsTestPki.leaf("regional", "CN=" + HOST, "dns:" + HOST, 2048, null, 3, true);
        long now = System.currentTimeMillis();   // AFTER issuing: a leaf is valid from the second keytool made it
        trust.check(SnsTestPki.signer().cert(), List.of(), HOST, now);   // control
        assertThrows(java.security.cert.CertPathBuilderException.class,
                () -> trust.check(selfSigned.cert(), List.of(), HOST, now), "does not chain");
        assertThrows(java.security.cert.CertificateExpiredException.class,
                () -> trust.check(expired.cert(), List.of(), HOST, now), "expired");
        assertThrows(SecurityException.class, () -> trust.check(misnamed.cert(), List.of(), HOST, now),
                "a DNS SAN that is not SNS wins over a CN that is");
        assertThrows(SecurityException.class, () -> trust.check(weak.cert(), List.of(), HOST, now), "1024-bit");
        trust.check(regional.cert(), List.of(), HOST, now);   // the ARN-derived regional name is accepted
        assertThrows(SecurityException.class, () -> trust.check(regional.cert(), List.of(),
                "sns.eu-west-1.amazonaws.com", now), "but only for the topic's own region");
    }
}
