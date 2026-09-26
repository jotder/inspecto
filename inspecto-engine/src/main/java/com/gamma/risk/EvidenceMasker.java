package com.gamma.risk;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.SpaceSecretKeys;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WRITE-time masking of Risk Score evidence (ASSURE-RISK-SCORE-1, operator decision 2026-09-27). The
 * {@code risk.score} Job stores the token, never the raw value, for any evidence column whose Dataset registry
 * {@code columns[].classification} is one of {@link #SENSITIVE}. So no reader sees it raw: not the read route,
 * not an Alert or Incident, not a Widget, and not the DB browser over the scores Dataset.
 *
 * <p>The ENTITY KEY is deliberately NOT masked — it stays raw like every per-entity Alert and Incident key in the
 * platform, governed by {@code canWorkIncidents} and data scopes; platform-wide key masking is deferred decision
 * D-P8.
 *
 * <p>A token is {@code masked:<16 hex>}: an HMAC-SHA256 under the Space's key in
 * {@code <config root>.secrets/.risk-score-mask.key} ({@link SpaceSecretKeys}). ⚠ It is deterministic per Space,
 * ACROSS models: the same value masks to the same token in every model, so tokens link records. And a leak of
 * that one key exposes every masked value by enumeration.
 */
public final class EvidenceMasker {

    /** Column classifications whose evidence values are masked. */
    public static final Set<String> SENSITIVE = Set.of("MSISDN", "IMSI", "ACCOUNT", "PII");
    public static final String TOKEN_PREFIX = "masked:";
    public static final String KEY_FILE = ".risk-score-mask.key";

    private final Path configRoot;
    private final Map<String, Set<String>> sensitiveByDataset;
    private byte[] key;

    private EvidenceMasker(Path configRoot, Map<String, Set<String>> sensitiveByDataset) {
        this.configRoot = configRoot;
        this.sensitiveByDataset = sensitiveByDataset;
    }

    /** The masker for one model: the sensitive columns of each Dataset its factors read, from the registry. */
    public static EvidenceMasker of(ComponentStore registry, Path configRoot, RiskScoreModel model) {
        Map<String, Set<String>> byDataset = new HashMap<>();
        for (RiskScoreModel.Factor f : model.factors())
            byDataset.computeIfAbsent(f.dataset(), d -> sensitiveColumns(registry, d));
        return new EvidenceMasker(configRoot, byDataset);
    }

    /** {@code <config root>.secrets/.risk-score-mask.key}. */
    public static Path keyFile(Path configRoot) {
        return SpaceSecretKeys.keyFile(configRoot, KEY_FILE);
    }

    static Set<String> sensitiveColumns(ComponentStore registry, String datasetId) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Object> ds = registry.get("dataset", datasetId).map(ComponentRegistry.Component::content).orElse(Map.of());
        if (ds.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null && c.get("classification") != null
                        && SENSITIVE.contains(String.valueOf(c.get("classification")).trim().toUpperCase(Locale.ROOT)))
                    out.add(String.valueOf(c.get("name")));
        return out;
    }

    /** The sensitive evidence columns a model's Datasets declare, as {@code dataset.column}. */
    public List<String> maskedColumns() {
        return sensitiveByDataset.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(c -> e.getKey() + "." + c)).sorted().toList();
    }

    /** {@code value} as it may be stored: the token when the column is sensitive, else unchanged. */
    public Object mask(String dataset, String column, Object value) {
        if (value == null || !sensitiveByDataset.getOrDefault(dataset, Set.of()).contains(column)) return value;
        return token(String.valueOf(value));
    }

    private synchronized String token(String raw) {
        try {
            if (key == null) key = SpaceSecretKeys.readOrCreate(keyFile(configRoot), "Risk Score mask key");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return TOKEN_PREFIX + HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the Risk Score mask key", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
