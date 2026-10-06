package com.gamma.pipeline.exec;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.ViewStore;
import com.gamma.risk.EvidenceMasker;
import com.gamma.util.ColumnClassification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code transform.mask} config (catalog {@code quality.pii.mask}, SEC-08 Enterprise-only, operator 2026-10-06):
 * which columns are masked, and how. One place for the shape rules, so the compiler, the save gate and the run
 * cannot disagree.
 *
 * <p><b>By classification.</b> {@code classifications: [PII, MSISDN]} plus {@code dataset: <registry id>} masks every
 * inbound column whose class is selected. A column's class is the Dataset's own {@code columns[].classification}
 * merged with {@link EvidenceMasker#lineageClassification} (sibling Datasets over the same store and the pipeline
 * schemas behind it), several classes on one column resolving <b>strictest-wins</b>
 * ({@link ColumnClassification#stricter} with the selected set as the masked predicate — a selected class
 * outranks one that is not). ⛔ Lineage that cannot be traced ({@link EvidenceMasker#UNKNOWN_LINEAGE}) is REFUSED,
 * not guessed: a mask that silently missed a renamed MSISDN would ship it in the clear.
 *
 * <p><b>By name.</b> {@code columns: […]} adds named columns; the two selections are unioned. A mask that resolves
 * to NO inbound column is refused — a pipeline that believes it masks PII and masks nothing is the worst outcome.
 */
public record MaskSpec(String mode, int keepLast, List<String> classifications, String dataset) {

    public static final List<String> MODES = List.of("full", "partial", "hash");
    public static final int DEFAULT_KEEP_LAST = 4;
    private static final Set<String> KEYS = Set.of("columns", "classifications", "dataset", "mode", "keep_last");

    /** Every shape problem in a raw {@code mask} config, worded for an author; empty = well-formed. Never a value. */
    public static List<String> problems(Map<?, ?> cfg) {
        List<String> out = new ArrayList<>();
        for (Object k : cfg.keySet())
            if (!KEYS.contains(String.valueOf(k)))
                out.add("mask does not take '" + k + "' - only " + new java.util.TreeSet<>(KEYS)
                        + " (masking is one-way: there is no reversible token vault)");
        boolean byName = cfg.get("columns") instanceof List<?> l && l.stream().anyMatch(o -> o != null && !o.toString().isBlank());
        boolean byClass = cfg.get("classifications") instanceof List<?> c && !c.isEmpty();
        if (cfg.get("columns") != null && !(cfg.get("columns") instanceof List<?>))
            out.add("mask columns: must be a list of column names");
        if (cfg.get("classifications") != null && !(cfg.get("classifications") instanceof List<?>))
            out.add("mask classifications: must be a list of classes");
        if (!byName && !byClass)
            out.add("mask needs columns: or classifications: - something to mask");
        if (byClass) {
            for (Object o : (List<?>) cfg.get("classifications")) {
                String cl = ColumnClassification.normalise(o);
                if (cl == null || !ColumnClassification.SENSITIVE.contains(cl))
                    out.add("mask classification '" + o + "' is not one of " + ColumnClassification.RESTRICTIVENESS);
            }
            if (cfg.get("dataset") == null || String.valueOf(cfg.get("dataset")).isBlank())
                out.add("mask classifications: need a dataset: - the registry Dataset whose column classifications choose the columns");
        }
        Object mode = cfg.get("mode");
        if (mode != null && !MODES.contains(String.valueOf(mode).trim().toLowerCase(Locale.ROOT)))
            out.add("mask mode '" + mode + "' is not one of " + MODES);
        Object keep = cfg.get("keep_last");
        if (keep != null) {
            int n;
            try { n = Integer.parseInt(String.valueOf(keep).trim()); } catch (NumberFormatException e) { n = 0; }
            if (n < 1) out.add("mask keep_last must be a whole number of at least 1");
        }
        return out;
    }

    /** The node's spec; a malformed config is refused with every {@link #problems problem} at once. */
    public static MaskSpec of(PipelineNode node) {
        List<String> p = problems(node.config());
        if (!p.isEmpty())
            throw new IllegalArgumentException("transform.mask node '" + node.id() + "': " + String.join("; ", p));
        String mode = node.cfg("mode") == null ? "full" : String.valueOf(node.cfg("mode")).trim().toLowerCase(Locale.ROOT);
        int keep = node.cfg("keep_last") == null ? DEFAULT_KEEP_LAST : Integer.parseInt(String.valueOf(node.cfg("keep_last")).trim());
        List<String> classes = new ArrayList<>();
        if (node.cfg("classifications") instanceof List<?> l)
            for (Object o : l) classes.add(ColumnClassification.normalise(o));
        String ds = node.cfg("dataset") == null ? null : String.valueOf(node.cfg("dataset")).trim();
        return new MaskSpec(mode, keep, List.copyOf(classes), ds);
    }

    /**
     * The inbound columns to mask: {@code named} (already resolved against {@code available}) unioned with every
     * available column whose strictest class is selected. {@code configRoot} is the Space's; classification needs it.
     */
    public List<String> resolve(PipelineNode node, List<String> available, List<String> named, Path configRoot) {
        Set<String> out = new LinkedHashSet<>(named);
        if (!classifications.isEmpty()) {
            if (configRoot == null)
                throw new IllegalStateException("transform.mask node '" + node.id()
                        + "': no Space config root is bound to this run, so the Dataset's classifications cannot be read");
            Map<String, String> classes = classesOf(configRoot, dataset);
            if (classes.containsKey(EvidenceMasker.UNKNOWN_LINEAGE))
                throw new IllegalStateException("transform.mask node '" + node.id() + "': the column lineage of Dataset '"
                        + dataset + "' cannot be traced, so which columns are classified is unknown - mask them by name");
            for (String c : available) {
                String cl = classes.get(c.toLowerCase(Locale.ROOT));
                if (cl != null && classifications.contains(cl)) out.add(c);
            }
        }
        if (out.isEmpty())
            throw new IllegalArgumentException("transform.mask node '" + node.id() + "': no inbound column is classified "
                    + classifications + " in Dataset '" + dataset + "' - a mask that masks nothing is refused");
        return List.copyOf(out);
    }

    /** Lower-cased column → class for {@code datasetId}: its own classes and its lineage, strictest-wins (selected first). */
    private Map<String, String> classesOf(Path configRoot, String datasetId) {
        ComponentStore registry = new ComponentStore(configRoot.resolve("registry"));
        Map<String, Object> ds = registry.get("dataset", datasetId).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("transform.mask: no registry Dataset '" + datasetId + "'"));
        Map<String, String> out = new TreeMap<>();
        java.util.function.Predicate<String> selected = classifications::contains;
        if (ds.get("columns") instanceof List<?> cols)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && c.get("name") != null) {
                    String cl = ColumnClassification.normalise(c.get("classification"));
                    if (cl != null)
                        out.merge(String.valueOf(c.get("name")).trim().toLowerCase(Locale.ROOT), cl,
                                (a, b) -> ColumnClassification.stricter(a, b, selected));
                }
        EvidenceMasker.lineageClassification(datasetId, ds, registry, new ViewStore(configRoot.resolve("views")))
                .forEach((col, cl) -> out.merge(col, cl, (a, b) -> ColumnClassification.stricter(a, b, selected)));
        return out;
    }
}
