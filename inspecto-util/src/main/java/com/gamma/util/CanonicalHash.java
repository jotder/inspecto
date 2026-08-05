package com.gamma.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;

/**
 * Content hash over a decoded config structure — SHA-256 of a canonical rendering in which map keys are
 * sorted, list order is preserved, and every scalar is length-prefixed (so no delimiter collision). Two
 * structurally equal configs hash identically regardless of key order or which file layout they came from.
 *
 * <p>First use: the ELT amendment §3.4.3 <b>schema fingerprint</b> pinned per Consignment in the manifest
 * and the {@code consignment_outputs} registry. ({@code com.gamma.control.ContentHash} is the same idea for
 * ETags, but package-private in the control plane — this is the shared home reachable from etl/engine.)
 */
public final class CanonicalHash {

    private CanonicalHash() {}

    /** SHA-256 (lowercase hex) of the canonical rendering of {@code value}; {@code null} hashes too. */
    public static String sha256(Object value) {
        StringBuilder sb = new StringBuilder();
        render(value, sb);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest)
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void render(Object v, StringBuilder sb) {
        switch (v) {
            case null -> sb.append('~');
            case Map<?, ?> m -> {
                sb.append("M{");
                m.entrySet().stream()
                        .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                        .forEach(e -> {
                            scalar(String.valueOf(e.getKey()), sb);
                            sb.append('=');
                            render(e.getValue(), sb);
                            sb.append(';');
                        });
                sb.append('}');
            }
            case Iterable<?> it -> {
                sb.append("L[");
                for (Object o : it) {
                    render(o, sb);
                    sb.append(';');
                }
                sb.append(']');
            }
            default -> scalar(String.valueOf(v), sb);
        }
    }

    /** Length-prefixed scalar ({@code 5:hello}) — unambiguous without escaping. */
    private static void scalar(String s, StringBuilder sb) {
        sb.append(s.length()).append(':').append(s);
    }
}
