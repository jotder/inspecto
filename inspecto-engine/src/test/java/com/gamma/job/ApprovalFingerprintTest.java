package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The shared approval mechanism: every fail-closed branch of {@link ApprovalFingerprint}. */
class ApprovalFingerprintTest {

    private static final Path ROOT = Path.of("root");

    @Test
    void hashIsKeyOrderIndependentAndContentSensitive() {
        Map<String, Object> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put("x", "1"); a.put("y", "2");
        b.put("y", "2"); b.put("x", "1");
        assertEquals(ApprovalFingerprint.hash(a), ApprovalFingerprint.hash(b), "key order is not content");
        b.put("y", "3");
        assertNotEquals(ApprovalFingerprint.hash(a), ApprovalFingerprint.hash(b), "a changed value moves the fingerprint");
        assertEquals(64, ApprovalFingerprint.hash(a).length());
    }

    @Test
    void nonceIsFreshEveryTime() {
        Set<String> seen = new TreeSet<>();
        for (int i = 0; i < 50; i++) assertTrue(seen.add(ApprovalFingerprint.newNonce()), "a nonce repeated");
        assertEquals(32, seen.iterator().next().length());
    }

    @Test
    void baseRecordBindsThePendingChangeAndNonce() {
        Map<String, Object> r = ApprovalFingerprint.baseRecord("pc-1", "n-1", "checker");
        assertEquals("pc-1", r.get("pendingChange"));
        assertEquals("n-1", r.get("nonce"));
        assertEquals("checker", r.get("approvedBy"));
        assertNotNull(r.get("approvedAt"));
    }

    @Test
    void noVerifierInstalledRefuses() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        assertFalse(gate.honours(ROOT, "job", Map.of("nonce", "n")), "no verifier installed means refuse");
        assertNull(gate.installed());
    }

    @Test
    void uninstallingRefusesAgain() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        gate.install((r, j, rec) -> true);
        assertTrue(gate.honours(ROOT, "job", Map.of()));
        gate.install(null);
        assertFalse(gate.honours(ROOT, "job", Map.of()), "an uninstalled verifier refuses");
    }

    @Test
    void verifierSaysNoRefuses() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        gate.install((r, j, rec) -> false);
        assertFalse(gate.honours(ROOT, "job", Map.of("nonce", "n")));
    }

    @Test
    void verifierThrowingRefuses() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        gate.install((r, j, rec) -> { throw new IllegalStateException("unreadable"); });
        assertFalse(gate.honours(ROOT, "job", Map.of("nonce", "n")), "an exception is never an approval");
    }

    @Test
    void nullRecordRefusesWithoutAskingTheVerifier() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        AtomicInteger asked = new AtomicInteger();
        gate.install((r, j, rec) -> { asked.incrementAndGet(); return true; });
        assertFalse(gate.honours(ROOT, "job", null));
        assertEquals(0, asked.get());
    }

    @Test
    void verifierGetsTheRootJobAndRecordAsGiven() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        Map<String, Object> rec = Map.of("nonce", "n");
        gate.install((r, j, x) -> ROOT.equals(r) && "job".equals(j) && x == rec);
        assertTrue(gate.honours(ROOT, "job", rec));
        assertFalse(gate.honours(ROOT, "other", rec), "bound to that Job");
    }

    @Test
    void publishVerifierKeepsItsTwoArgumentShape() {
        PostgresPublishJobType.ApprovalVerifier v = (root, rec) -> "n".equals(rec.get("nonce"));
        var gate = new ApprovalFingerprint.Gate<PostgresPublishJobType.ApprovalVerifier>();
        gate.install(v);
        assertTrue(gate.honours(ROOT, "job", Map.of("nonce", "n")));
        assertFalse(gate.honours(ROOT, "job", Map.of("nonce", "forged")));
    }

    @Test
    void aThrowingVerifierIsLoggedByClassNeverByMessage() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApprovalFingerprint.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
            gate.install((r, j, rec) -> { throw new IllegalStateException("SECRET-MSISDN-0712345678"); });
            assertFalse(gate.honours(ROOT, "job", Map.of("nonce", "n")));
            assertEquals(1, appender.list.size());
            var e = appender.list.get(0);
            assertEquals(ch.qos.logback.classic.Level.WARN, e.getLevel());
            assertTrue(e.getFormattedMessage().contains("java.lang.IllegalStateException"), e.getFormattedMessage());
            assertFalse(e.getFormattedMessage().contains("SECRET-MSISDN"), "the exception message is never logged");
            assertNull(e.getThrowableProxy(), "no stack trace either: it carries the message");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** The Gate screens only "no verifier" and "no record"; a blank nonce or pending change is the TYPE's verifier's call. */
    @Test
    void aBlankOrMissingNonceOrPendingChangeStillReachesTheVerifier() {
        var gate = new ApprovalFingerprint.Gate<ApprovalFingerprint.Verifier>();
        AtomicInteger asked = new AtomicInteger();
        gate.install((r, j, rec) -> { asked.incrementAndGet(); return false; });
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("nonce", "");
        blank.put("pendingChange", "  ");
        assertFalse(gate.honours(ROOT, "job", blank), "the verifier's false is honoured");
        assertFalse(gate.honours(ROOT, "job", Map.of()), "a record with neither field");
        assertFalse(gate.honours(ROOT, "job", Map.of("nonce", "n")), "no pendingChange");
        assertEquals(3, asked.get(), "the Gate asked the verifier every time; the verifier decides");
        gate.install((r, j, rec) -> true);
        assertTrue(gate.honours(ROOT, "job", Map.of()), "and a verifier that says yes is not second-guessed");
    }
}
