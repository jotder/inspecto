package com.gamma.demoauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Demo session tokens: {@code base64url(kind|userId|expiryEpochSeconds).base64url(HMAC-SHA256)} under a random
 * secret minted once per JVM, so a restart signs every Demo User out (decision D-5). {@code kind} is {@code a}
 * (access) or {@code r} (refresh) and is part of the signed text, so a refresh token is never accepted as a
 * Bearer and vice versa.
 */
final class DemoTokens {

    static final long ACCESS_SECONDS = 15 * 60;
    static final long REFRESH_SECONDS = 8 * 60 * 60;

    private static final byte[] SECRET = new byte[32];
    static { new SecureRandom().nextBytes(SECRET); }

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private DemoTokens() {}

    static String mint(char kind, String userId, long nowSeconds) {
        long ttl = kind == 'r' ? REFRESH_SECONDS : ACCESS_SECONDS;
        String payload = kind + "|" + userId + "|" + (nowSeconds + ttl);
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        return ENC.encodeToString(p) + "." + ENC.encodeToString(hmac(p));
    }

    /** The user id a valid, unexpired token of {@code kind} names; empty for anything else. */
    static Optional<String> verify(char kind, String token, long nowSeconds) {
        if (token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        try {
            byte[] p = DEC.decode(token.substring(0, dot));
            if (!MessageDigest.isEqual(hmac(p), DEC.decode(token.substring(dot + 1)))) return Optional.empty();
            String[] parts = new String(p, StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || parts[0].length() != 1 || parts[0].charAt(0) != kind) return Optional.empty();
            if (Long.parseLong(parts[2]) <= nowSeconds) return Optional.empty();
            return parts[1].isBlank() ? Optional.empty() : Optional.of(parts[1]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
