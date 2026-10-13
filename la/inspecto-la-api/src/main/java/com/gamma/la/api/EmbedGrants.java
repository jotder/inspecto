package com.gamma.la.api;

import com.gamma.la.core.InvestigationStore;
import com.gamma.spi.http.ApiContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The embed grants of an Investigation (LA-EMBED-VIEW-1): the records behind a read-only public link, held in the
 * {@link InvestigationStore} as SIDECARS - never a log entry, so the sealed hashes, the Dossier root and the op-log hash do not move
 * when a link is minted, used or revoked.
 *
 * <p><b>Token.</b> {@code <grantId>.<secret>}: the grant id (16 URL-safe characters, random) is the public half and names the record; the
 * secret is 256 random bits. Only the SHA-256 of the WHOLE token is stored, and it is compared in constant time; the token itself is
 * returned once at mint and exists nowhere else. A forged or tampered token cannot verify, and there is no signing secret to leak.
 * A record is revoked by a flag, immediately, and its hash is purged when it expires or is revoked (the row stays, for the audit).
 *
 * <p><b>One answer.</b> {@link #resolve} reports WHY a token failed only to its caller (for the audit row); the HTTP answer to every
 * failure is the same 404, so a prober learns nothing about which part was wrong.
 */
final class EmbedGrants {

    private EmbedGrants() {}

    /** The public path prefix; the token is the segment after it. */
    static final String PREFIX = "/public/investigations/";

    /** The kill switch (fail-closed, like BI sharing's secret): with it off a mint is 503 and every public request is the uniform 404. */
    static final String ENABLED_PROPERTY = "la.embed.enabled";

    /** The most links one Investigation holds live at once. */
    static final int MAX_ACTIVE = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /** What {@link #mint} produced: the token (shown ONCE) and the stored record. */
    record Minted(String token, Map<String, Object> record) {}

    /** Why a token did not resolve. Known to the caller for the audit row only - never to the HTTP client. */
    enum Denied { MALFORMED, UNKNOWN, MISMATCH, REVOKED, EXPIRED, PURGED }

    /** The outcome of {@link #resolve}: a live record, or the reason it is not one. */
    record Resolution(Map<String, Object> record, Denied denied) {
        boolean ok() {
            return denied == null;
        }
    }

    // -- minting -------------------------------------------------------------------------------------

    /** Mint a grant for {@code investigationId}: store the record (hash only) and return the token once. */
    static Minted mint(InvestigationStore store, String investigationId, String issuedBy, Instant now, long ttlHours,
                       String label, String maskingModeAtMint) throws IOException {
        return mint(store, investigationId, issuedBy, now, ttlHours, label, maskingModeAtMint, SCOPE_FULL, maskingModeAtMint);
    }

    /** What a link shows beyond the Working Set (entities + links, always): {@code working_set} only, {@code with_notes}, or {@code full} (notes + references). */
    static final String SCOPE_FULL = "full";
    static final List<String> SCOPES = List.of("working_set", "with_notes", SCOPE_FULL);

    /**
     * {@code scope}: one of {@link #SCOPES}. {@code linkMasking}: the masking mode THIS link applies, never looser than the Space's
     * ({@code maskingModeAtMint}); the view masks at the stricter of the two, and the liveness check still tracks only the Space.
     */
    static Minted mint(InvestigationStore store, String investigationId, String issuedBy, Instant now, long ttlHours,
                       String label, String maskingModeAtMint, String scope, String linkMasking) throws IOException {
        purgeExpired(store, investigationId, now);
        long active = store.embedGrants(investigationId).stream().filter(j -> "active".equals(status(parse(j), now))).count();
        if (active >= MAX_ACTIVE)
            throw new com.gamma.spi.auth.ApiException(409, com.gamma.spi.auth.ErrorCodes.CONFLICT,
                    "an investigation holds at most " + MAX_ACTIVE + " live embed links - revoke one first");
        for (int attempt = 0; attempt < 5; attempt++) {
            String grantId = random(12);
            String token = grantId + "." + random(32);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("grantId", grantId);
            rec.put("investigationId", investigationId);
            rec.put("issuedBy", issuedBy);
            rec.put("issuedAt", now.toString());
            rec.put("expiresAt", now.plusSeconds(ttlHours * 3600).toString());
            rec.put("label", label);
            rec.put("tokenSha256", sha256(token));
            rec.put("maskingModeAtMint", maskingModeAtMint);
            rec.put("scope", scope);
            rec.put("maskingMode", linkMasking);
            rec.put("viewCount", 0L);
            if (store.createEmbedGrant(investigationId, grantId, write(rec))) {
                rec.remove("tokenSha256");   // the caller of mint never needs, and a response must never carry, the hash
                return new Minted(token, rec);
            }
        }
        throw new IOException("could not allocate an embed grant id");
    }

    // -- resolving -----------------------------------------------------------------------------------

    /** Look a presented token up: constant-time hash compare, then revoked / purged / expired against {@code now}. */
    static Resolution resolve(InvestigationStore store, String token, Instant now) throws IOException {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot < 0 || token.length() > 128 || !InvestigationStore.EMBED_GRANT_ID.matcher(token.substring(0, dot)).matches())
            return new Resolution(null, Denied.MALFORMED);
        Optional<String> raw = store.embedGrant(token.substring(0, dot));
        byte[] presented = sha256(token).getBytes(StandardCharsets.UTF_8);
        if (raw.isEmpty()) {
            MessageDigest.isEqual(presented, presented);   // the same work as a hit: an unknown id must not answer faster
            return new Resolution(null, Denied.UNKNOWN);
        }
        Map<String, Object> rec = parse(raw.get());
        Object stored = rec.get("tokenSha256");
        if (stored == null) return new Resolution(rec, rec.get("revokedAt") != null ? Denied.REVOKED : Denied.PURGED);
        if (!MessageDigest.isEqual(presented, String.valueOf(stored).getBytes(StandardCharsets.UTF_8)))
            return new Resolution(null, Denied.MISMATCH);
        if (rec.get("revokedAt") != null) return new Resolution(rec, Denied.REVOKED);
        if (!Instant.parse(String.valueOf(rec.get("expiresAt"))).isAfter(now)) return new Resolution(rec, Denied.EXPIRED);
        return new Resolution(rec, null);
    }

    // -- revoking ------------------------------------------------------------------------------------

    /** Revoke one grant now (idempotent: an already revoked one keeps its first revocation). The hash is dropped; the row stays. */
    static Map<String, Object> revoke(InvestigationStore store, String grantId, String by, String reason, Instant now)
            throws IOException {
        Optional<String> raw = store.embedGrant(grantId);
        if (raw.isEmpty()) return null;
        Map<String, Object> rec = parse(raw.get());
        if (rec.get("revokedAt") != null) return rec;
        rec.put("revokedAt", now.toString());
        rec.put("revokedBy", by);
        rec.put("revokedReason", reason);
        rec.remove("tokenSha256");
        store.replaceEmbedGrant(grantId, write(rec));
        return rec;
    }

    /** Revoke every live grant of an Investigation (closed, deleted, masking loosened). Returns how many it revoked. */
    static int revokeAll(InvestigationStore store, String investigationId, String by, String reason, Instant now) throws IOException {
        int n = 0;
        for (String json : store.embedGrants(investigationId)) {
            Map<String, Object> rec = parse(json);
            if (rec.get("revokedAt") == null && rec.get("tokenSha256") != null) {
                revoke(store, String.valueOf(rec.get("grantId")), by, reason, now);
                n++;
            }
        }
        return n;
    }

    /** Revoke the live grants {@code issuer} minted on an Investigation (the minter lost their lead role). Returns how many. */
    static int revokeIssuedBy(InvestigationStore store, String investigationId, String issuer, String by, String reason, Instant now)
            throws IOException {
        int n = 0;
        for (String json : store.embedGrants(investigationId)) {
            Map<String, Object> rec = parse(json);
            if (issuer.equals(rec.get("issuedBy")) && rec.get("revokedAt") == null && rec.get("tokenSha256") != null) {
                revoke(store, String.valueOf(rec.get("grantId")), by, reason, now);
                n++;
            }
        }
        return n;
    }

    /** The sweep: drop the hash of every EXPIRED grant of this Investigation (the row stays, for the audit). Run on mint and on list. */
    static void purgeExpired(InvestigationStore store, String investigationId, Instant now) throws IOException {
        for (String json : store.embedGrants(investigationId)) {
            Map<String, Object> rec = parse(json);
            if (rec.get("tokenSha256") != null && !Instant.parse(String.valueOf(rec.get("expiresAt"))).isAfter(now)) {
                rec.remove("tokenSha256");
                rec.put("purgedAt", now.toString());
                store.replaceEmbedGrant(String.valueOf(rec.get("grantId")), write(rec));
            }
        }
    }

    // -- use counters --------------------------------------------------------------------------------

    /** Roll one successful view into the grant's counters (not one audit row per view's worth of file growth). */
    static synchronized void viewed(InvestigationStore store, String grantId, Instant now) {
        try {
            Optional<String> raw = store.embedGrant(grantId);
            if (raw.isEmpty()) return;
            Map<String, Object> rec = parse(raw.get());
            rec.put("lastViewAt", now.toString());
            rec.put("viewCount", (rec.get("viewCount") instanceof Number n ? n.longValue() : 0L) + 1);
            store.replaceEmbedGrant(grantId, write(rec));
        } catch (IOException | RuntimeException ignored) {
            // a counter that cannot be written never fails the view
        }
    }

    // -- the list shape ------------------------------------------------------------------------------

    /** {@code active | expired | revoked} at {@code now}. */
    static String status(Map<String, Object> rec, Instant now) {
        if (rec.get("revokedAt") != null) return "revoked";
        return Instant.parse(String.valueOf(rec.get("expiresAt"))).isAfter(now) ? "active" : "expired";
    }

    /** The grants of one Investigation as the list route shows them: no hash, no token, ever. */
    static List<Map<String, Object>> summaries(InvestigationStore store, String investigationId, Instant now) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String json : store.embedGrants(investigationId)) out.add(summary(parse(json), now));
        return out;
    }

    static Map<String, Object> summary(Map<String, Object> rec, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("grantId", "label", "issuedBy", "issuedAt", "expiresAt"))
            m.put(k, rec.get(k));
        m.put("status", status(rec, now));
        m.put("scope", rec.get("scope") == null ? SCOPE_FULL : rec.get("scope"));   // a grant minted before scopes existed shows everything
        m.put("maskingMode", rec.get("maskingMode") == null ? rec.get("maskingModeAtMint") : rec.get("maskingMode"));
        for (String k : List.of("revokedAt", "revokedBy", "revokedReason", "lastViewAt"))
            if (rec.get(k) != null) m.put(k, rec.get(k));
        m.put("viewCount", rec.get("viewCount") instanceof Number n ? n.longValue() : 0L);
        return m;
    }

    // -- helpers -------------------------------------------------------------------------------------

    /** A short, non-reversible handle of a token for an audit row of a REFUSED lookup (8 hex digits of its hash: enough to correlate, useless to a guesser). */
    static String hashPrefix(String token) {
        return sha256(token == null ? "" : token).substring(0, 8);
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String json) {
        try {
            return new LinkedHashMap<>(ApiContext.JSON.readValue(json, Map.class));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String write(Map<String, Object> rec) throws IOException {
        return ApiContext.JSON.writeValueAsString(rec);
    }
}
