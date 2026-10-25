package com.gamma.la.api;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.access.ComponentAccess;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InputFingerprint;
import com.gamma.la.storage.IndexStore;
import com.gamma.spi.auth.RequestAttrs;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * PERF-P4-2 - the conditional read of {@code POST /inv/projection} and {@code POST /inv/projection/neighbors}: a bounded,
 * short-lived cache of whole response bodies, and an {@code ETag} so a client holding the body gets {@code 304} without a
 * Dataset read.
 *
 * <p><b>Order (binding).</b> {@link InvRoutes} consults this only AFTER every gate of the read has run for THIS request: the
 * write root, the body validation, the Dataset view gate (R3 404), the filter render, the four-eyes refusals and the alias
 * resolution. A 304 is therefore never an answer to a viewer the read would refuse.
 *
 * <p><b>Key.</b> A SHA-256 over everything that changes the answer: the route, the Space (write root), the viewer (Subject id,
 * capabilities, data scopes, attributes, the token-time held roles), the canonical (sorted-key) body, the resolved limit, the
 * Dataset's registry entry and relation SQL, its input fingerprint, the LA settings in force (masking mode, Entity Types,
 * four-eyes thresholds, index settings, caps), the Space's masking key, and for a neighbours read the published index
 * version of every mapping of the Dataset. The ETag is derived from the key, so it changes whenever any input does.
 *
 * <p><b>Not cached.</b> A Dataset whose fingerprint is not {@code files:} (no enumerable files, too many, a listing that
 * timed out): nothing cheap tells a change apart, so it is read every time. Failures are never stored (only a body the read
 * returned is). Projection and neighbours read no Entity List, and the relation of a Dataset is one relation (no join), so
 * no list version or joined Dataset enters the key.
 */
public final class ProjectionCache {

    static final int MAX_ENTRIES = 64;
    static final long DEFAULT_TTL_MS = 60_000L;

    /** Dataset reads run by the two routes (test seam: a cache hit and a 304 must not move it). */
    static final LongAdder READS = new LongAdder();

    private static final ObjectWriter CANONICAL = ApiContext.JSON.writer()
            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * Per-entry caps. 5,000 rows is ten times the explorer's default projection cap (500), so an ordinary graph read
     * is cached. 2 MB is about 5,000 rows at the measured ~450 B per row. Above either cap the read is served but not
     * stored. 32 MB in total holds at most 16 such entries. ⚠ The bound counts SERIALISED bytes, not heap: the retained
     * heap measured about 5.8x the counted bytes unmasked and 4.7x masked, so at the bound the real footprint is roughly
     * 130-190 MB (verified 2026-10-25). Budget the heap for that, not for 32 MB.
     */
    static final int MAX_ENTRY_ROWS = 5_000;
    static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    private static volatile int maxEntryRows = MAX_ENTRY_ROWS;
    private static volatile long maxTotalBytes = MAX_TOTAL_BYTES;
    private static long bytes;                                                                   // guarded by ENTRIES
    private static final LongAdder SKIPPED = new LongAdder();

    private record Cached(Map<String, Object> body, String etag, int rows, boolean truncated, Object auditSource, long bytes,
                          long expiresAt) { }

    /** The lookup handle of one request: {@code etag} null = not cacheable. */
    record Key(String hash, String etag) {
        boolean cacheable() {
            return hash != null;
        }
    }

    /** A cached answer: the body and what the audit needs. */
    record Hit(Map<String, Object> body, String etag, int rows, boolean truncated, Object auditSource) { }

    private static final Map<String, Cached> ENTRIES = new LinkedHashMap<>(16, 0.75f, true);   // bounds enforced in put
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile long ttlMs = DEFAULT_TTL_MS;

    private ProjectionCache() { }

    static final Key NONE = new Key(null, null);

    /**
     * The key of this read, or {@link #NONE} when it must not be cached. Call only after every gate has passed.
     */
    static Key key(HttpExchange ex, String route, Path writeRoot, Path dataRoot, String datasetId, String relationSql,
                   Map<String, Object> body, int limit, ExplorationMasking mask) {
        // Limit (stated, not fixed): the fingerprint sees path, size and mtime in milliseconds only, so a file rewritten in
        // place with BOTH size and mtime preserved (an mtime-preserving copy) is not noticed; its cached answer is served
        // until the TTL ends. Also: an expired entry is never served but stays in memory until the next store sweeps it.
        InputFingerprint fp;
        try {
            fp = IndexRoutes.currentInput(dataRoot, writeRoot, datasetId);                     // fresh: never the 30 s fingerprint cache
        } catch (RuntimeException unavailable) {
            return NONE;
        }
        if (fp == null || fp.value() == null || !fp.value().startsWith(InputFingerprint.FILES)) return NONE;
        try {
            Map<String, Object> parts = new LinkedHashMap<>();
            parts.put("route", route);
            parts.put("space", writeRoot.toAbsolutePath().normalize().toString());
            parts.put("viewer", viewer(ex));
            parts.put("body", body);
            parts.put("limit", limit);
            parts.put("dataset", DatasetProviders.require().dataset(writeRoot, datasetId).orElse(Map.of()));
            parts.put("relation", relationSql);
            parts.put("fingerprint", fp.value());
            LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(writeRoot);
            parts.put("settings", s.toString());
            parts.put("maskingMode", s.effectiveMaskingMode());
            parts.put("entityTypes", String.valueOf(s.effectiveEntityTypes()));
            parts.put("indexEnabled", s.effectiveIndex().enabledInForce());
            parts.put("masking", mask.describe());
            parts.put("maskKey", mask.active() ? sha256(MaskTokens.spaceKey(writeRoot)) : "");
            parts.put("index", indexVersions(writeRoot, datasetId));                            // both routes: the route alone tells them apart
            String hash = sha256(CANONICAL.writeValueAsBytes(parts));
            return new Key(hash, "\"" + hash.substring(0, 40) + "\"");
        } catch (IOException | RuntimeException unkeyable) {
            return NONE;                                                                         // an input that cannot be read is not keyed
        }
    }

    /** The cached answer of {@code key}, or null. */
    static Hit get(Key key) {
        if (!key.cacheable()) return null;
        long now = clock.getAsLong();
        synchronized (ENTRIES) {
            Cached e = ENTRIES.get(key.hash());
            if (e == null) return null;
            if (now >= e.expiresAt()) {
                ENTRIES.remove(key.hash());
                bytes -= e.bytes();
                return null;
            }
            return new Hit(e.body(), e.etag(), e.rows(), e.truncated(), e.auditSource());
        }
    }

    /**
     * Store a successful answer (the caller hands over a body it no longer changes) with the audit detail a hit replays.
     * A result over {@link #MAX_ENTRY_ROWS} rows or {@link #MAX_ENTRY_BYTES} serialised bytes is not stored (counted in
     * {@link #skippedStores()}): such a read is expensive to hold and rarely repeated byte-for-byte. Every put first drops
     * the expired entries (at most {@link #MAX_ENTRIES}, so the sweep is bounded), then evicts least-recently-used entries
     * until both the count and the {@link #MAX_TOTAL_BYTES} bound hold.
     */
    static boolean put(Key key, Map<String, Object> body, int rows, boolean truncated, Object auditSource) {
        if (!key.cacheable()) return false;
        if (rows > maxEntryRows) {
            SKIPPED.increment();
            return false;
        }
        long size;
        try {
            size = ApiContext.JSON.writeValueAsBytes(body).length;
        } catch (IOException | RuntimeException unsized) {
            SKIPPED.increment();
            return false;
        }
        if (size > MAX_ENTRY_BYTES || size > maxTotalBytes) {
            SKIPPED.increment();
            return false;
        }
        long now = clock.getAsLong();
        synchronized (ENTRIES) {
            var it = ENTRIES.entrySet().iterator();
            while (it.hasNext()) {
                Cached e = it.next().getValue();
                if (now >= e.expiresAt()) {
                    bytes -= e.bytes();
                    it.remove();
                }
            }
            Cached old = ENTRIES.remove(key.hash());
            if (old != null) bytes -= old.bytes();
            it = ENTRIES.entrySet().iterator();                                                  // access order: eldest first
            while (it.hasNext() && (ENTRIES.size() >= MAX_ENTRIES || bytes + size > maxTotalBytes)) {
                bytes -= it.next().getValue().bytes();
                it.remove();
            }
            ENTRIES.put(key.hash(), new Cached(body, key.etag(), rows, truncated, auditSource, size, now + ttlMs));
            bytes += size;
            return true;
        }
    }

    /** Test seam: entries held now. */
    public static int size() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }

    /** Test seam: serialised bytes held now. */
    public static long bytes() {
        synchronized (ENTRIES) {
            return bytes;
        }
    }

    /** Test seam: results not stored for being over a per-entry cap. */
    public static long skippedStores() {
        return SKIPPED.sum();
    }

    /** Test seam: the per-entry row cap and the total byte bound (0 = the defaults). Clears the cache. */
    public static void forTestCaps(int entryRows, long totalBytes) {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            bytes = 0;
        }
        maxEntryRows = entryRows <= 0 ? MAX_ENTRY_ROWS : entryRows;
        maxTotalBytes = totalBytes <= 0 ? MAX_TOTAL_BYTES : totalBytes;
    }

    /** True when the request's {@code If-None-Match} names {@code etag} (or {@code *}). */
    static boolean notModified(HttpExchange ex, String etag) {
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm == null || etag == null) return false;
        for (String t : inm.split(",")) {
            String v = t.trim();
            if (v.startsWith("W/")) v = v.substring(2);
            if (v.equals(etag) || v.equals("*")) return true;
        }
        return false;
    }

    /** Answer {@code 304} with the ETag and no body; returns {@link ApiContext#HANDLED}. */
    static Object respond304(HttpExchange ex, String etag) throws IOException {
        ex.getResponseHeaders().set("ETag", etag);
        ex.getResponseHeaders().set("Cache-Control", "private, no-cache");
        ex.sendResponseHeaders(304, -1);
        return ApiContext.HANDLED;
    }

    /** Mark a 200 answer with its ETag ({@code private}: the body is this viewer's). */
    static void tag(HttpExchange ex, Key key) {
        if (!key.cacheable()) return;
        ex.getResponseHeaders().set("ETag", key.etag());
        ex.getResponseHeaders().set("Cache-Control", "private, no-cache");
    }

    private static Map<String, Object> viewer(HttpExchange ex) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (RequestAttrs.attr(ex, RequestAttrs.ATTR_SUBJECT) instanceof Subject s) {
            v.put("id", s.id());
            v.put("capabilities", s.capabilities() == null ? null : new TreeSet<>(s.capabilities()));
            v.put("dataScopes", s.dataScopes() == null ? null : new TreeSet<>(s.dataScopes()));
            v.put("attributes", new TreeMap<>(s.attributes()).toString());
        } else {
            v.put("id", null);                                                                   // no Subject: the fail-open Basic caller
        }
        Object roles = RequestAttrs.attr(ex, ComponentAccess.ATTR_HELD_ROLES);
        v.put("heldRoles", roles instanceof java.util.Set<?> set ? new TreeSet<>(set.stream().map(String::valueOf).toList()) : null);
        return v;
    }

    /** Each published mapping of the Dataset with its CURRENT version directory: a rebuild moves it. */
    private static Map<String, String> indexVersions(Path writeRoot, String datasetId) {
        Map<String, String> out = new TreeMap<>();
        Path root = writeRoot.resolve(IndexRoutes.INDEX_DIR);
        List<String> hashes;
        try {
            hashes = IndexStore.mappingHashes(root, datasetId);
        } catch (IllegalArgumentException cannotNameADirectory) {
            return out;
        }
        for (String h : hashes) {
            Optional<Path> cur = new IndexStore(root, datasetId, h).current();
            out.put(h, cur.map(p -> p.getFileName().toString()).orElse(""));
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test seam: a fake clock and TTL (null/0 = the real ones). Always clears the cache. */
    public static void forTest(LongSupplier fakeClock, long ttl) {
        synchronized (ENTRIES) {
            ENTRIES.clear();
            bytes = 0;
        }
        clock =fakeClock == null ? System::currentTimeMillis : fakeClock;
        ttlMs = ttl <= 0 ? DEFAULT_TTL_MS : ttl;
    }

    /** Test seam: Dataset reads the two routes have run since start. */
    public static long reads() {
        return READS.sum();
    }
}
