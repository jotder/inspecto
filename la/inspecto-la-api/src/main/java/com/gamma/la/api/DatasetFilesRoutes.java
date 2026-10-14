package com.gamma.la.api;

import com.gamma.access.ComponentAccess;
import com.gamma.access.WriteGates;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.la.core.CollectorCoveragePorts;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexStore;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The per-File read (LA-DATA-TRUST-1 S1): {@code GET /inv/datasets/{id}/files?limit&offset&name} lists the Files the
 * Consignment audit trail recorded towards a Dataset ({@link CollectorCoveragePort#files}), each with its event day, rows,
 * <b>ingested-at</b> (the Consignment's commit time, not the arrival time), outcome and <b>index state</b>.
 *
 * <p><b>Index state</b> is matched, never guessed: a File's output partition files are looked up in the live
 * {@link IndexManifest#inputFiles()} (path relative to the data root; the lineage ledger names the written path, so
 * separators and the data-root prefix are normalised first). {@code INDEXED} (all of them are in the live version, which is
 * reported), {@code WAITING} (some are not), {@code UNKNOWN} (the manifest recorded no list - past
 * {@link IndexManifest#MAX_INPUT_FILES} files or an older build - or its list shares nothing with any File, which
 * means the paths cannot be correlated), {@code NO_INDEX} (the Dataset has no published index); {@code null} for a File
 * that wrote no partition file (quarantined, failed, empty). Timestamps are never used to guess.
 *
 * <p>Gated like {@code GET /inv/index}: the geoLink feature, a write root (503), and R3 {@link ComponentAccess#canView}
 * on the Dataset (an unknown and a not-viewable Dataset are the same 404). Without attribution evidence the answer is
 * {@code assessed:false}, never an empty list read as "no Files".
 */
public final class DatasetFilesRoutes implements RouteModule {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1_000;

    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/datasets/([^/]+)/files", (e, m) -> files(api, e, m.group(1)));
    }

    private static Object files(ApiContext api, HttpExchange ex, String id) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dataset files");                      // 503
        Map<String, Object> dataset = DatasetProviders.require().dataset(writeRoot, id)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "'"));
        if (!ComponentAccess.canView(ex, dataset))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + id + "'");
        int limit = intParam(ex, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int offset = intParam(ex, "offset", 0, 0, Integer.MAX_VALUE);
        return read(api, writeRoot, dataset, id, limit, offset, ApiContext.query(ex, "name"));
    }

    /** The per-File read of an already-gated Dataset: the one reading behind this route and the Dossier footer (LA-DATA-TRUST-1 S9). */
    /**
     * The Files table's order (LA-DATA-TRUST-POLISH-1 #7): event day newest first (a File with no day last), then name A-Z.
     * A bulk delivery lands in one second, so ingested-at cannot order it; the tail (Collector, Pipeline, Consignment, ingested-at)
     * makes the order TOTAL, so two equal-looking rows never swap places between one page and the next.
     */
    static final Comparator<CollectorCoveragePort.FileEntry> FILE_ORDER = Comparator
            .comparing((CollectorCoveragePort.FileEntry f) -> f.day() == null ? "" : f.day()).reversed()
            .thenComparing(CollectorCoveragePort.FileEntry::name)
            .thenComparing(f -> f.collector() == null ? "" : f.collector())
            .thenComparing(f -> f.pipeline() == null ? "" : f.pipeline())
            .thenComparing(f -> f.consignmentId() == null ? "" : f.consignmentId())
            .thenComparing(f -> f.ingestedAt() == null ? "" : f.ingestedAt());

    static Map<String, Object> read(ApiContext api, Path writeRoot, Map<String, Object> dataset, String id, int limit, int offset, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("readAt", Instant.now().toString());
        Optional<List<CollectorCoveragePort.FileEntry>> found = CollectorCoveragePorts.active().flatMap(p -> p.files(api, dataset));
        if (found.isEmpty()) {
            out.put("assessed", false);
            out.put("note", "Files are not assessed: no Consignment audit trail attributes this Dataset's stores to a Pipeline "
                    + "(not written by a Pipeline, no audit files, or no attribution bound)");
            out.put("total", 0);
            out.put("offset", offset);
            out.put("limit", limit);
            out.put("items", List.of());
            return out;
        }

        IndexManifest manifest = liveManifest(writeRoot, id);
        Set<String> indexed = manifest == null || manifest.inputFiles() == null ? null
                : paths(manifest.inputFiles());
        List<CollectorCoveragePort.FileEntry> all = new ArrayList<>(found.get());
        all.sort(FILE_ORDER);

        // One pass: the state of every File (the strip's counts are over ALL of them, not the page).
        List<String> states = new ArrayList<>();
        boolean anyMatched = false;
        for (CollectorCoveragePort.FileEntry f : all) {
            String s = state(f, manifest, indexed, api.dataRoot());
            if (matches(f, indexed, api.dataRoot()) > 0) anyMatched = true;
            states.add(s);
        }
        if (indexed != null && !indexed.isEmpty() && !anyMatched) {
            // The list is there but shares nothing with any File: the paths do not correlate, so "waiting" would be a guess.
            for (int i = 0; i < states.size(); i++) if ("WAITING".equals(states.get(i))) states.set(i, "UNKNOWN");
        }

        Map<String, long[]> days = new TreeMap<>();                  // day -> {files, rows}
        Map<String, Long> by = new LinkedHashMap<>();
        long rows = 0;
        for (int i = 0; i < all.size(); i++) {
            CollectorCoveragePort.FileEntry f = all.get(i);
            rows += f.rows();
            if (f.day() != null) {
                long[] d = days.computeIfAbsent(f.day(), k -> new long[2]);
                d[0]++;
                d[1] += f.rows();
            }
            by.merge(states.get(i) == null ? "NONE" : states.get(i), 1L, Long::sum);
            by.merge("outcome:" + f.outcome(), 1L, Long::sum);
        }

        String needle = name == null || name.isBlank() ? null : name.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> page = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < all.size(); i++) {
            CollectorCoveragePort.FileEntry f = all.get(i);
            if (needle != null && !f.name().toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (total >= offset && page.size() < limit) page.add(view(f, states.get(i), manifest));
            total++;
        }

        out.put("assessed", true);
        out.put("total", total);
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("items", page);
        List<Map<String, Object>> dayList = new ArrayList<>();
        days.forEach((d, v) -> dayList.add(Map.of("day", d, "files", v[0], "rows", v[1])));
        out.put("days", dayList);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("files", all.size());
        summary.put("rows", rows);
        summary.put("latestIngestedAt", all.stream().map(CollectorCoveragePort.FileEntry::ingestedAt)
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null));
        summary.put("indexed", by.getOrDefault("INDEXED", 0L));
        summary.put("waiting", by.getOrDefault("WAITING", 0L));
        summary.put("unknown", by.getOrDefault("UNKNOWN", 0L));
        summary.put("problem", all.stream().filter(f -> !"SUCCESS".equals(f.outcome())).count());
        out.put("summary", summary);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("present", manifest != null);
        index.put("version", manifest == null ? null : manifest.version());
        index.put("builtAt", manifest == null ? null : manifest.builtAt());
        index.put("inputsRecorded", indexed == null ? null : indexed.size());
        out.put("index", index);
        out.put("note", "ingested-at is the Consignment's commit time, not the arrival time; an unchanged duplicate delivery is "
                + "skipped silently and is not listed; a File still in the inbox is not listed (see GET /runs/{pipeline}/pending); "
                + "index state is matched from the live index's recorded input files, never guessed from timestamps");
        return out;
    }

    private static Map<String, Object> view(CollectorCoveragePort.FileEntry f, String state, IndexManifest manifest) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.name());
        m.put("day", f.day());
        m.put("rows", f.rows());
        m.put("errorRows", f.errorRows() < 0 ? null : f.errorRows());      // -1 = not measured: blank, never 0
        m.put("ingestedAt", f.ingestedAt());
        m.put("outcome", f.outcome());
        m.put("collector", f.collector());
        m.put("pipeline", f.pipeline());
        m.put("consignmentId", f.consignmentId());
        m.put("indexState", state);
        m.put("indexVersion", "INDEXED".equals(state) && manifest != null ? manifest.version() : null);
        return m;
    }

    /** INDEXED / WAITING / UNKNOWN / NO_INDEX, or null for a File that wrote no partition file. Pure: the unit-test seam. */
    static String state(CollectorCoveragePort.FileEntry f, IndexManifest manifest, Set<String> indexed, Path dataRoot) {
        if (f.outputFiles().isEmpty()) return null;
        if (manifest == null) return "NO_INDEX";
        if (indexed == null) return "UNKNOWN";
        return matches(f, indexed, dataRoot) == f.outputFiles().size() ? "INDEXED" : "WAITING";
    }

    private static int matches(CollectorCoveragePort.FileEntry f, Set<String> indexed, Path dataRoot) {
        if (indexed == null) return 0;
        int n = 0;
        for (String o : f.outputFiles()) if (covered(o, indexed, dataRoot)) n++;
        return n;
    }

    /**
     * Whether the index covers the partition file the lineage ledger names. The index lists paths relative to the data root with
     * forward slashes; the ledger names the path as written (often absolute, maybe backslashes). With the data-root prefix present it is
     * stripped and matched exactly; without it (a relocated root) a path SUFFIX is accepted, which is the only correlation left.
     */
    static boolean covered(String outputFile, Set<String> indexed, Path dataRoot) {
        if (outputFile == null || outputFile.isBlank()) return false;
        String n = outputFile.replace('\\', '/');
        if (dataRoot != null) {
            String root = dataRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!root.endsWith("/")) root = root + "/";
            if (n.regionMatches(true, 0, root, 0, root.length())) return indexed.contains(n.substring(root.length()));
        }
        if (indexed.contains(n)) return true;
        for (int i = n.indexOf('/'); i >= 0; i = n.indexOf('/', i + 1)) if (indexed.contains(n.substring(i + 1))) return true;
        return false;
    }

    private static Set<String> paths(List<IndexManifest.InputFile> files) {
        Set<String> out = new HashSet<>();
        for (IndexManifest.InputFile f : files) out.add(f.path().replace('\\', '/'));
        return out;
    }

    /** The newest live manifest over any mapping of the Dataset, or null when it has no readable published index. */
    private static IndexManifest liveManifest(Path writeRoot, String dataset) {
        Path root = IndexRoutes.indexRoot(writeRoot);
        List<String> hashes;
        try {
            hashes = IndexStore.mappingHashes(root, dataset);
        } catch (IllegalArgumentException cannotNameADirectory) {
            return null;
        }
        IndexManifest best = null;
        for (String hash : hashes) {
            Optional<Path> current = new IndexStore(root, dataset, hash).current();
            if (current.isEmpty()) continue;
            try {
                IndexManifest m = IndexManifest.read(current.get());
                if (dataset.equals(m.dataset()) && (best == null || m.builtAt().compareTo(best.builtAt()) > 0)) best = m;
            } catch (java.io.IOException | IllegalArgumentException unreadable) {
                // an unreadable manifest is no index
            }
        }
        return best;
    }

    private static int intParam(HttpExchange ex, String key, int dflt, int min, int max) {
        String v = ApiContext.query(ex, key);
        if (v == null || v.isBlank()) return dflt;
        try {
            int n = Integer.parseInt(v.trim());
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {
            // falls through to the refusal
        }
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be an integer from " + min + (max == Integer.MAX_VALUE ? "" : " to " + max));
    }
}
