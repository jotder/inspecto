package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.risk.RiskScoreModel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Render-time masking of a Risk Score response (ASSURE-RISK-SCORE-1). The stored scores Dataset stays raw — a
 * score must stay recomputable — and masking happens on the way out, as Link Analysis does it.
 *
 * <p><b>What is masked.</b> A column is sensitive when its Dataset's registry {@code columns[].classification}
 * is one of {@link #SENSITIVE} (case-insensitive, trimmed) — the source Link Analysis's typed masking reads too.
 * The {@code entityKey} is masked when ANY factor's key column is sensitive (a key does not record which
 * Dataset it came from). An evidence value is masked when its column is sensitive in that factor's Dataset.
 *
 * <p><b>How.</b> {@code masked:<16 hex>} — an HMAC-SHA256 under a random per-Space key
 * ({@code <dataDir>/.risk-score-mask.key}, created on first use, never served). Keyed, because a plain hash of
 * a phone number is reversible by enumerating the number space; stable, so one entity reads as one pseudonym.
 *
 * <p>⛔ <b>No reveal.</b> Link Analysis's audited reveal ({@code EntityMasking}, {@code canRevealLinkEntities})
 * is bound to an Investigation's sealed log in the optional {@code inspecto-geo-link} module; the core cannot
 * reach it, and a Risk Score has no Investigation to bind a reveal to. So a masked value is simply masked.
 */
final class RiskScoreMasking {

    /** Column classifications that are masked. */
    static final Set<String> SENSITIVE = Set.of("MSISDN", "IMSI", "ACCOUNT", "PII");
    static final String TOKEN_PREFIX = "masked:";
    private static final String KEY_FILE = ".risk-score-mask.key";

    private final byte[] key;
    private final boolean maskKey;
    private final Map<String, Set<String>> sensitiveByFactor;   // factor id → sensitive evidence columns
    private final List<String> maskedColumns;

    private RiskScoreMasking(byte[] key, boolean maskKey, Map<String, Set<String>> sensitiveByFactor,
                             List<String> maskedColumns) {
        this.key = key;
        this.maskKey = maskKey;
        this.sensitiveByFactor = sensitiveByFactor;
        this.maskedColumns = maskedColumns;
    }

    static RiskScoreMasking of(ComponentStore registry, Path dataRoot, RiskScoreModel model) throws IOException {
        Map<String, Set<String>> byDataset = new HashMap<>();
        boolean maskKey = false;
        Map<String, Set<String>> byFactor = new LinkedHashMap<>();
        Set<String> masked = new LinkedHashSet<>();
        for (RiskScoreModel.Factor f : model.factors()) {
            Set<String> sensitive = byDataset.computeIfAbsent(f.dataset(), d -> sensitiveColumns(registry, d));
            if (sensitive.contains(f.key())) {
                maskKey = true;
                masked.add(f.dataset() + "." + f.key());
            }
            Set<String> ev = new LinkedHashSet<>();
            for (String c : f.evidence())
                if (sensitive.contains(c)) {
                    ev.add(c);
                    masked.add(f.dataset() + "." + c);
                }
            byFactor.put(f.id(), ev);
        }
        byte[] key = maskKey || !masked.isEmpty() ? keyFor(dataRoot) : null;
        return new RiskScoreMasking(key, maskKey, byFactor, List.copyOf(masked));
    }

    /** The Dataset's columns whose registry classification is sensitive. */
    private static Set<String> sensitiveColumns(ComponentStore registry, String datasetId) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Object> ds = registry.get("dataset", datasetId).map(ComponentRegistry.Component::content).orElse(Map.of());
        if (ds.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null && c.get("classification") != null
                        && SENSITIVE.contains(String.valueOf(c.get("classification")).trim().toUpperCase(Locale.ROOT)))
                    out.add(String.valueOf(c.get("name")));
        return out;
    }

    String entityKey(String raw) {
        return maskKey ? token(raw) : raw;
    }

    /** The factors with every sensitive evidence value replaced; nothing else changes (the score stays recomputable). */
    List<Map<String, Object>> factors(List<Map<String, Object>> factors) {
        List<Map<String, Object>> out = new ArrayList<>(factors.size());
        for (Map<String, Object> f : factors) {
            Map<String, Object> copy = new LinkedHashMap<>(f);
            Set<String> sensitive = sensitiveByFactor.getOrDefault(String.valueOf(f.get("indicator")), Set.of());
            if (!sensitive.isEmpty() && f.get("evidence") instanceof List<?> rows) {
                List<Map<String, Object>> masked = new ArrayList<>();
                for (Object r : rows) {
                    if (!(r instanceof Map<?, ?> row)) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    row.forEach((k, v) -> m.put(String.valueOf(k),
                            v != null && sensitive.contains(String.valueOf(k)) ? token(String.valueOf(v)) : v));
                    masked.add(m);
                }
                copy.put("evidence", masked);
            }
            out.add(copy);
        }
        return out;
    }

    /** What was masked and why — so a masked value never reads as the data itself. */
    Map<String, Object> basis() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("maskedColumns", maskedColumns);
        b.put("entityKeyMasked", maskKey);
        b.put("rule", "columns classified " + SENSITIVE.stream().sorted().toList()
                + " are masked; no reveal exists for Risk Scores");
        return b;
    }

    private String token(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return TOKEN_PREFIX + HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot mask", e);
        }
    }

    private static synchronized byte[] keyFor(Path dataRoot) throws IOException {
        Path file = dataRoot.resolve(KEY_FILE);
        if (Files.isRegularFile(file)) return Files.readAllBytes(file);
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        Files.createDirectories(dataRoot);
        try {
            Files.write(file, k, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return k;
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            return Files.readAllBytes(file);
        }
    }
}
