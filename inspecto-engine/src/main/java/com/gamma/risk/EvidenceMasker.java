package com.gamma.risk;

import com.gamma.catalog.PipelineSchemas;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.ColumnClassification;
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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public static final Set<String> SENSITIVE = ColumnClassification.SENSITIVE;
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
        ViewStore views = new ViewStore(configRoot.resolve("views"));
        for (RiskScoreModel.Factor f : model.factors())
            byDataset.computeIfAbsent(f.dataset(), d -> sensitiveColumns(registry, views, d));
        return new EvidenceMasker(configRoot, byDataset);
    }

    /** {@code <config root>.secrets/.risk-score-mask.key}. */
    public static Path keyFile(Path configRoot) {
        return SpaceSecretKeys.keyFile(configRoot, KEY_FILE);
    }

    /**
     * The Dataset's sensitive columns, lower-cased: its own registry {@code columns[].classification}, plus its
     * {@link #lineageClassification} — what sibling Datasets over the same store declare for a same-named column, and
     * what the pipeline schema behind it classifies through the mapping (ASSURE-CLASSIFICATION-PROPAGATION-1, the
     * resolver {@code publish.postgres} uses). Fails closed: the set holds {@link #UNKNOWN_LINEAGE}, and EVERY evidence
     * column is masked, when that lineage cannot be traced, or when a view or virtual Dataset reads a store with
     * classified columns (a rename such as {@code msisdn AS m} cannot be traced statically).
     */
    static Set<String> sensitiveColumns(ComponentStore registry, ViewStore views, String datasetId) {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Object> ds = registry.get("dataset", datasetId).map(ComponentRegistry.Component::content).orElse(Map.of());
        if (ds.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null && c.get("classification") != null
                        && SENSITIVE.contains(String.valueOf(c.get("classification")).trim().toUpperCase(Locale.ROOT)))
                    out.add(String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT));
        Map<String, String> inherited = lineageClassification(datasetId, ds, registry, views);
        out.addAll(inherited.keySet());
        if (!inherited.isEmpty() && (str(ds.get("view")) != null || str(ds.get("sql")) != null)) out.add(UNKNOWN_LINEAGE);
        return out;
    }

    /**
     * The classified columns (lower-cased name to class) other Datasets declare over the stores {@code ds} reads: its
     * {@code physicalRef}, a virtual Dataset's {@code sourceName}, or a view's store and its {@code source_store}
     * lineage, plus what the pipeline schemas behind them classify ({@link #schemaClassification}); several classes
     * on one column resolve strictest-wins ({@link ColumnClassification}). The ONE lineage resolver shared by
     * {@code publish.postgres}, its four-eyes fingerprint, and evidence masking.
     */
    public static Map<String, String> lineageClassification(String datasetId, Map<String, ?> ds, ComponentStore store,
                                                            ViewStore views) {
        Set<String> stores = new java.util.HashSet<>(datasetStores(ds));
        String view = str(ds.get("view"));
        if (view != null) {
            stores.add(view);
            views.get(view).ifPresent(v -> {
                if (v.store() != null) stores.add(v.store());
                if (v.sourceStores() != null) stores.addAll(v.sourceStores());
            });
        }
        Map<String, String> out = new java.util.TreeMap<>();
        for (ComponentRegistry.Component c : store.list("dataset")) {
            if (c.name().equals(datasetId)) continue;
            Map<String, Object> other = c.content();
            String otherRef = str(other.get("physicalRef"));
            if (otherRef == null || !stores.contains(otherRef)) continue;
            if (other.get("columns") instanceof List<?> cols)
                for (Object o : cols)
                    if (o instanceof Map<?, ?> col && col.get("name") != null && col.get("classification") != null) {
                        String cl = String.valueOf(col.get("classification")).trim().toUpperCase(Locale.ROOT);
                        if (SENSITIVE.contains(cl))
                            out.merge(String.valueOf(col.get("name")).toLowerCase(Locale.ROOT), cl, ColumnClassification::stricter);
                    }
        }
        schemaClassification(store.root().getParent(), stores, out);
        return out;
    }

    /** The sensitive evidence columns a model's Datasets declare, as {@code dataset.column}. */
    public List<String> maskedColumns() {
        return sensitiveByDataset.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(c -> e.getKey() + "." + c)).sorted().toList();
    }

    /** {@code value} as it may be stored: the token when the column is sensitive, else unchanged. */
    public Object mask(String dataset, String column, Object value) {
        Set<String> sensitive = sensitiveByDataset.getOrDefault(dataset, Set.of());
        if (value == null || !(sensitive.contains(UNKNOWN_LINEAGE)
                || sensitive.contains(column.trim().toLowerCase(Locale.ROOT)))) return value;
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

    /** Reserved classification-map key: the store's column lineage could not be established (fail closed). */
    public static final String UNKNOWN_LINEAGE = "*";

    /**
     * The origin rule: the stores a Dataset reads directly, its {@code physicalRef} and a virtual Dataset's
     * {@code sourceName} (a {@code store/table} ref resolves by its head). Shared by every lineage consumer.
     */
    public static Set<String> datasetStores(Map<String, ?> ds) {
        Set<String> stores = new LinkedHashSet<>();
        if (str(ds.get("physicalRef")) != null) stores.add(str(ds.get("physicalRef")));
        if (str(ds.get("sourceName")) != null) stores.add(str(ds.get("sourceName")));
        return stores;
    }

    /** {@link #schemaClassification(Path, Set, Predicate)} under {@link #SENSITIVE}, merged into {@code out} strictest-wins. */
    public static void schemaClassification(Path configRoot, Set<String> stores, Map<String, String> out) {
        schemaClassification(configRoot, stores, SENSITIVE::contains)
                .forEach((col, cl) -> out.merge(col, cl, ColumnClassification::stricter));
    }

    /**
     * What the pipeline schemas behind {@code stores} classify, lower-cased stored column to class. Only raw
     * {@code raw.fields[].classification} values {@code masked} accepts count. A class follows the schema's
     * {@code mapping.fields[]} to the stored column: a {@code keep}/rename of a classified raw column, and any rule
     * whose text ({@code from} or {@code args}) names one (a hash, a substring, a concatenation), classify the
     * target; a target named like a classified raw column does too. When several classified inputs feed one column
     * it takes the STRICTEST class ({@link ColumnClassification}, operator 2026-10-04). Fails closed to
     * {@link #UNKNOWN_LINEAGE} when a matching pipeline cannot be loaded, its mapping cannot be read, or it has a
     * step that rewrites the columns (summarize, sql, lookup, join, route) while a raw column is classified. A store
     * no pipeline claims (a Job output, a sidecar) contributes nothing.
     */
    public static Map<String, String> schemaClassification(Path configRoot, Set<String> stores, Predicate<String> masked) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String store : stores) {
            int slash = store.indexOf('/');
            PipelineSchemas.Found f = PipelineSchemas.forStore(configRoot, slash < 0 ? store : store.substring(0, slash));
            if (f.unreadable()) out.put(UNKNOWN_LINEAGE, "UNKNOWN");
            for (PipelineSchemas.Entry e : f.entries()) {
                Map<String, String> sensitiveRaw = new java.util.LinkedHashMap<>();
                if (e.schema().get("raw") instanceof Map<?, ?> raw && raw.get("fields") instanceof List<?> fields)
                    for (Object o : fields)
                        if (o instanceof Map<?, ?> fld && fld.get("name") != null) {
                            String cl = ColumnClassification.normalise(fld.get("classification"));
                            if (cl != null && masked.test(cl))
                                put(sensitiveRaw, String.valueOf(fld.get("name")).trim().toLowerCase(Locale.ROOT), cl, masked);
                        }
                if (sensitiveRaw.isEmpty()) continue;
                if (f.reshaped()) { out.put(UNKNOWN_LINEAGE, "UNKNOWN"); continue; }
                sensitiveRaw.forEach((col, cl) -> put(out, col, cl, masked));   // a stored column named like the raw one
                if (!(e.schema().get("mapping") instanceof Map<?, ?> mapping) || mapping.get("fields") == null) continue;
                if (!(mapping.get("fields") instanceof List<?> rules)) { out.put(UNKNOWN_LINEAGE, "UNKNOWN"); continue; }
                for (Object o : rules) {
                    if (!(o instanceof Map<?, ?> rule) || str(rule.get("name")) == null) {
                        out.put(UNKNOWN_LINEAGE, "UNKNOWN");
                        break;
                    }
                    Matcher m = WORD.matcher(rule.get("from") + " " + rule.get("args"));
                    String strictest = null;   // several classified inputs: the strictest wins
                    while (m.find())
                        strictest = ColumnClassification.stricter(strictest,
                                sensitiveRaw.get(m.group().toLowerCase(Locale.ROOT)), masked);
                    if (strictest != null) put(out, str(rule.get("name")).toLowerCase(Locale.ROOT), strictest, masked);
                }
            }
        }
        return out;
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

    private static void put(Map<String, String> out, String col, String cl, Predicate<String> masked) {
        out.merge(col, cl, (x, y) -> ColumnClassification.stricter(x, y, masked));
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).trim();
    }
}
