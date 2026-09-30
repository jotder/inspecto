package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one four-eyes approval mechanism every data-egress Job type shares ({@link PublicationApproval} for
 * {@code publish.postgres}, {@link AttachApprovals} for an attaching {@code ReportJob}). A Job type supplies ONLY
 * what to hash and where to store its record; this class owns the rest:
 * <ul>
 *   <li>{@link #hash} — SHA-256 of a canonical, key-sorted JSON form (the fingerprint);</li>
 *   <li>{@link #newNonce} and {@link #baseRecord} — the record fields that bind an approval to the MAC'd Pending
 *       Change that produced it;</li>
 *   <li>{@link Verifier} and {@link Gate} — the control plane's check, installed at start, that FAILS CLOSED:
 *       none installed, a {@code false}, or an exception all mean the approval is not honoured.</li>
 * </ul>
 */
public final class ApprovalFingerprint {

    private ApprovalFingerprint() {}

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** SHA-256 (hex) of {@code canonical} serialised as key-sorted JSON. */
    public static String hash(Object canonical) {
        try {
            return sha256(JSON.writeValueAsBytes(canonical));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 (hex) of raw bytes. */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh approval nonce (hex), written into BOTH the MAC'd Pending Change and the approval record. */
    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** The fields every approval record carries: the Pending Change it is bound to, its nonce, who and when. */
    public static Map<String, Object> baseRecord(String pendingChange, String nonce, String approvedBy) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pendingChange", pendingChange);
        m.put("nonce", nonce);
        m.put("approvedBy", approvedBy);
        m.put("approvedAt", Instant.now().toString());
        return m;
    }

    /**
     * Confirms an approval record belongs to a real, still-approved Pending Change: it exists, verifies its MAC, is
     * {@code approved}, is for {@code job}, carries the record's nonce and fixed the record's fingerprint(s).
     */
    @FunctionalInterface
    public interface Verifier {
        boolean verify(Path root, String job, Map<String, Object> record);
    }

    /** Holds the installed {@link Verifier}; {@link #honours} is false unless one is installed and says yes. */
    public static final class Gate<V extends Verifier> {
        private volatile V verifier;

        public void install(V v) { verifier = v; }

        public V installed() { return verifier; }

        public boolean honours(Path root, String job, Map<String, Object> record) {
            V v = verifier;
            if (v == null || record == null) return false;   // no verifier installed: fail closed
            try {
                return v.verify(root, job, record);
            } catch (RuntimeException unverifiable) {
                return false;
            }
        }
    }
}
