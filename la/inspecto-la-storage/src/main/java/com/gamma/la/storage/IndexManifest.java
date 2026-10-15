package com.gamma.la.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The manifest.json of one index version (D-3 design 2.6).
 *
 * <p><b>Unknown-field policy:</b> reading IGNORES fields it does not know (a newer writer may add fields; an older reader
 * still serves the index) but REFUSES a {@code formatVersion} above {@link #FORMAT_VERSION} - a newer layout is not
 * guessed at. Missing required fields fail with {@link IllegalArgumentException}. {@code parent} and {@code deltas}
 * default to null / empty.
 *
 * @param version         the index version number (the NNNNNN of vNNNNNN)
 * @param builtAt         ISO-8601 instant text
 * @param builder         how it was produced
 * @param duckdbVersion   the DuckDB version that wrote the files
 * @param rowGroupSize    parquet row group size
 * @param mappingHash     must equal {@code mapping.hash()}
 * @param relationSqlHash hash of the dataset relation SQL the build read
 * @param baseFingerprint fingerprint of the base data the full build covered
 * @param timeColZone     the EFFECTIVE zone the time column was interpreted in (UTC when the mapping gave none; null when
 *                        there is no time column or it is a TIMESTAMPTZ)
 * @param tables          per-table stats, keyed by table name (insertion order kept)
 * @param droppedNull     rows dropped because src or dst was null
 * @param parent          the version this one was appended to (null for a full build)
 * @param inputFiles      the input files (path relative to the data root, size, mtime) the full build covered, sorted, or
 *                        null when not recorded (an older manifest, an unenumerable Dataset, or more than
 *                        {@link #MAX_INPUT_FILES} files - then only {@code baseFingerprint} remains). Optional and additive:
 *                        {@link #FORMAT_VERSION} stays 1, an older reader ignores it, a missing field reads as null
 * @param bucketSizing    the {@link BucketFunction.Sizing#signature() bucket sizing} that chose {@code buckets} (an append or
 *                        compact keeps its parent's), or null when not recorded (a manifest written before the sizing was
 *                        configurable: it was {@link BucketFunction.Sizing#DEFAULT}, see {@link #bucketSizingInForce()}).
 *                        Optional and additive like {@code inputFiles}
 * @param kindEdges       link statistics (Entity profile D-EP4; every one optional and additive like {@code inputFiles}, null =
 *                        not recorded, an older manifest). They are counted over LINKS as the Entity profile counts them:
 *                        an edge's ends are their Entity keys (the endpoint type's normalised key, the raw id when the column is
 *                        untyped - see {@code endpointTypes}), folded to distinct {@code (a, b, kind)}, with self-links and
 *                        ends that normalise to nothing left out. {@code kindEdges}: links per kind, most first; a NULL kind
 *                        is not counted (no kind column = an empty map)
 * @param typeNodes       distinct keys per Entity Type of the endpoint columns (empty when neither is typed): the source
 *                        column's type counts the keys ever a source, the target's the keys ever a target - one count when both
 *                        are the same type
 * @param degree          quantiles of degree = a key's distinct neighbours over the links (either direction)
 * @param timeSpan        earliest and latest edge time (the mapped time column as UTC instants); null when there is no time
 *                        column, every time is NULL, or it was not recorded
 * @param degreeHistogram degree to the number of keys with it, ascending: the whole distribution {@code degree} summarises,
 *                        so hubs over any threshold, single-link keys and one key's percentile are read without a scan
 * @param endpointTypes   the Entity Type (id + normaliser) each endpoint column was keyed by at build, null for an untyped end
 */
public record IndexManifest(long version, String builtAt, Builder builder, String duckdbVersion, String bucketFn,
                            int buckets, int rowGroupSize, IndexMapping mapping, String mappingHash, String dataset,
                            String relationSqlHash, String baseFingerprint, String timeColZone,
                            Map<String, TableStats> tables, long droppedNull, List<Delta> deltas, String parent,
                            List<InputFile> inputFiles, String bucketSizing, Map<String, Long> kindEdges,
                            Map<String, Long> typeNodes, Degree degree, TimeSpan timeSpan, Map<Long, Long> degreeHistogram,
                            EndpointTypes endpointTypes) {

    public static final int FORMAT_VERSION = 1;
    public static final String FILE_NAME = "manifest.json";
    /** Most input files a manifest lists; above this only the fingerprint hash is kept. */
    public static final int MAX_INPUT_FILES = 5_000;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public enum Builder { FULL, APPEND, COMPACT }

    public record TableStats(long rows, long files, long bytes) { }

    /** An append delta layered on the base: its directory name and size. */
    public record Delta(String dir, long rows, long bytes) { }

    /** One input file the build read: path relative to the data root, size in bytes, mtime in epoch millis. */
    public record InputFile(String path, long size, long mtimeMillis) { }

    /** Degree quantiles over every node (nearest-rank, {@code quantile_disc}). */
    public record Degree(long p50, long p90, long p99, long max) { }

    /** Earliest and latest edge time, ISO-8601 instant text. */
    public record TimeSpan(String min, String max) { }

    /** An Entity Type an endpoint column was keyed by: its id and normaliser. */
    public record EndpointType(String type, String normaliser) { }

    /** The types of the two endpoint columns at build; either is null when that column was untyped. */
    public record EndpointTypes(EndpointType source, EndpointType target) { }

    /** A manifest with no recorded link statistics (what every manifest written before they existed reads as). */
    public IndexManifest(long version, String builtAt, Builder builder, String duckdbVersion, String bucketFn, int buckets,
                         int rowGroupSize, IndexMapping mapping, String mappingHash, String dataset, String relationSqlHash,
                         String baseFingerprint, String timeColZone, Map<String, TableStats> tables, long droppedNull,
                         List<Delta> deltas, String parent, List<InputFile> inputFiles, String bucketSizing) {
        this(version, builtAt, builder, duckdbVersion, bucketFn, buckets, rowGroupSize, mapping, mappingHash, dataset,
                relationSqlHash, baseFingerprint, timeColZone, tables, droppedNull, deltas, parent, inputFiles, bucketSizing,
                null, null, null, null, null, null);
    }

    /** A manifest with no recorded bucket sizing (what every manifest written before the sizing was configurable reads as). */
    public IndexManifest(long version, String builtAt, Builder builder, String duckdbVersion, String bucketFn, int buckets,
                         int rowGroupSize, IndexMapping mapping, String mappingHash, String dataset, String relationSqlHash,
                         String baseFingerprint, String timeColZone, Map<String, TableStats> tables, long droppedNull,
                         List<Delta> deltas, String parent, List<InputFile> inputFiles) {
        this(version, builtAt, builder, duckdbVersion, bucketFn, buckets, rowGroupSize, mapping, mappingHash, dataset,
                relationSqlHash, baseFingerprint, timeColZone, tables, droppedNull, deltas, parent, inputFiles, null);
    }

    /** A manifest with no recorded input-file list (what every manifest written before the list existed reads as). */
    public IndexManifest(long version, String builtAt, Builder builder, String duckdbVersion, String bucketFn, int buckets,
                         int rowGroupSize, IndexMapping mapping, String mappingHash, String dataset, String relationSqlHash,
                         String baseFingerprint, String timeColZone, Map<String, TableStats> tables, long droppedNull,
                         List<Delta> deltas, String parent) {
        this(version, builtAt, builder, duckdbVersion, bucketFn, buckets, rowGroupSize, mapping, mappingHash, dataset,
                relationSqlHash, baseFingerprint, timeColZone, tables, droppedNull, deltas, parent, null);
    }

    public IndexManifest {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(dataset, "dataset");
        if (!mapping.hash().equals(mappingHash)) throw new IllegalArgumentException("mappingHash does not match the mapping");
        tables = tables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        deltas = deltas == null ? List.of() : List.copyOf(deltas);
        inputFiles = inputFiles == null ? null : List.copyOf(inputFiles);
        kindEdges = kindEdges == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(kindEdges));
        typeNodes = typeNodes == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(typeNodes));
        degreeHistogram = degreeHistogram == null ? null : Collections.unmodifiableMap(new java.util.TreeMap<>(degreeHistogram));
    }

    /** This manifest with the given link statistics (the four optional fields) in place of its own. */
    public IndexManifest withLinkStats(Map<String, Long> kindEdges, Map<String, Long> typeNodes, Degree degree, TimeSpan timeSpan,
                                       Map<Long, Long> degreeHistogram, EndpointTypes endpointTypes) {
        return new IndexManifest(version, builtAt, builder, duckdbVersion, bucketFn, buckets, rowGroupSize, mapping, mappingHash,
                dataset, relationSqlHash, baseFingerprint, timeColZone, tables, droppedNull, deltas, parent, inputFiles,
                bucketSizing, kindEdges, typeNodes, degree, timeSpan, degreeHistogram, endpointTypes);
    }

    /** The sizing signature that chose {@code buckets}: the recorded one, else the shipped {@link BucketFunction.Sizing#DEFAULT}. */
    public String bucketSizingInForce() {
        return bucketSizing != null ? bucketSizing : BucketFunction.Sizing.DEFAULT.signature();
    }

    public String toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("formatVersion", FORMAT_VERSION);
        n.put("version", version);
        n.put("builtAt", builtAt);
        n.put("builder", builder.name().toLowerCase(Locale.ROOT));
        n.putObject("duckdb").put("version", duckdbVersion);
        n.put("bucketFn", bucketFn);
        n.put("buckets", buckets);
        if (bucketSizing != null) n.put("bucketSizing", bucketSizing);
        n.put("rowGroupSize", rowGroupSize);
        ObjectNode m = n.putObject("mapping");
        m.put("srcColumn", mapping.srcColumn());
        m.put("dstColumn", mapping.dstColumn());
        putNullable(m, "kindColumn", mapping.kindColumn());
        putNullable(m, "timeColumn", mapping.timeColumn());
        putNullable(m, "timeColZone", mapping.timeColZone());
        putNullable(m, "weightColumn", mapping.weightColumn());
        ArrayNode attrs = m.putArray("attributeColumns");
        mapping.attributeColumns().forEach(attrs::add);
        n.put("mappingHash", mappingHash);
        n.put("dataset", dataset);
        n.put("relationSqlHash", relationSqlHash);
        n.put("baseFingerprint", baseFingerprint);
        n.put("timeColZone", timeColZone);
        ObjectNode t = n.putObject("tables");
        tables.forEach((k, v) -> t.putObject(k).put("rows", v.rows()).put("files", v.files()).put("bytes", v.bytes()));
        n.put("droppedNull", droppedNull);
        ArrayNode d = n.putArray("deltas");
        deltas.forEach(x -> d.addObject().put("dir", x.dir()).put("rows", x.rows()).put("bytes", x.bytes()));
        if (parent == null) n.putNull("parent"); else n.put("parent", parent);
        if (inputFiles != null) {
            ArrayNode f = n.putArray("inputFiles");
            inputFiles.forEach(x -> f.addObject().put("path", x.path()).put("size", x.size()).put("mtime", x.mtimeMillis()));
        }
        if (kindEdges != null) kindEdges.forEach(n.putObject("kindEdges")::put);
        if (typeNodes != null) typeNodes.forEach(n.putObject("typeNodes")::put);
        if (degree != null)
            n.putObject("degree").put("p50", degree.p50()).put("p90", degree.p90()).put("p99", degree.p99()).put("max", degree.max());
        if (timeSpan != null) n.putObject("timeSpan").put("min", timeSpan.min()).put("max", timeSpan.max());
        if (degreeHistogram != null) {
            ObjectNode h = n.putObject("degreeHistogram");
            degreeHistogram.forEach((k, v) -> h.put(Long.toString(k), v));
        }
        if (endpointTypes != null) {
            ObjectNode e = n.putObject("endpointTypes");
            endpoint(e, "source", endpointTypes.source());
            endpoint(e, "target", endpointTypes.target());
        }
        try {
            return JSON.writeValueAsString(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static IndexManifest fromJson(String json) {
        JsonNode n;
        try {
            n = JSON.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("manifest is not valid JSON: " + e.getMessage(), e);
        }
        if (n == null || !n.isObject()) throw new IllegalArgumentException("manifest is not a JSON object");
        int fv = req(n, "formatVersion").asInt();
        if (fv > FORMAT_VERSION)
            throw new IllegalArgumentException("manifest formatVersion " + fv + " is newer than supported " + FORMAT_VERSION);
        JsonNode m = req(n, "mapping");
        List<String> attrs = new ArrayList<>();
        m.path("attributeColumns").forEach(a -> attrs.add(a.asText()));
        IndexMapping mapping = new IndexMapping(req(m, "srcColumn").asText(), req(m, "dstColumn").asText(),
                text(m, "kindColumn"), text(m, "timeColumn"), text(m, "timeColZone"), text(m, "weightColumn"), attrs);
        Map<String, TableStats> tables = new LinkedHashMap<>();
        req(n, "tables").fields().forEachRemaining(e -> tables.put(e.getKey(), new TableStats(
                e.getValue().path("rows").asLong(), e.getValue().path("files").asLong(), e.getValue().path("bytes").asLong())));
        List<Delta> deltas = new ArrayList<>();
        n.path("deltas").forEach(x -> deltas.add(new Delta(x.path("dir").asText(), x.path("rows").asLong(), x.path("bytes").asLong())));
        List<InputFile> inputFiles = null;                                    // absent in an older manifest: not recorded
        if (n.path("inputFiles").isArray()) {
            inputFiles = new ArrayList<>();
            for (JsonNode x : n.get("inputFiles"))
                inputFiles.add(new InputFile(x.path("path").asText(), x.path("size").asLong(), x.path("mtime").asLong()));
        }
        JsonNode d = n.path("degree"), ts = n.path("timeSpan");        // the link statistics: absent in an older manifest
        Degree degree = d.isObject() ? new Degree(d.path("p50").asLong(), d.path("p90").asLong(), d.path("p99").asLong(),
                d.path("max").asLong()) : null;
        TimeSpan timeSpan = ts.isObject() ? new TimeSpan(text(ts, "min"), text(ts, "max")) : null;
        Map<Long, Long> histogram = null;
        if (n.path("degreeHistogram").isObject()) {
            Map<Long, Long> h = new LinkedHashMap<>();
            n.get("degreeHistogram").fields().forEachRemaining(e -> h.put(Long.parseLong(e.getKey()), e.getValue().asLong()));
            histogram = h;
        }
        JsonNode ep = n.path("endpointTypes");
        EndpointTypes endpoints = ep.isObject() ? new EndpointTypes(endpoint(ep.path("source")), endpoint(ep.path("target"))) : null;
        Builder b;
        try {
            b = Builder.valueOf(req(n, "builder").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("manifest builder is not full|append|compact: " + n.get("builder").asText());
        }
        return new IndexManifest(req(n, "version").asLong(), text(n, "builtAt"), b, text(req(n, "duckdb"), "version"),
                text(n, "bucketFn"), n.path("buckets").asInt(), n.path("rowGroupSize").asInt(), mapping,
                req(n, "mappingHash").asText(), req(n, "dataset").asText(), text(n, "relationSqlHash"),
                text(n, "baseFingerprint"), text(n, "timeColZone"), tables, n.path("droppedNull").asLong(), deltas,
                text(n, "parent"), inputFiles, text(n, "bucketSizing"), counts(n, "kindEdges"), counts(n, "typeNodes"),
                degree, timeSpan, histogram, endpoints);
    }

    public void write(Path dir) throws IOException {
        Files.writeString(dir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static IndexManifest read(Path dir) throws IOException {
        return fromJson(Files.readString(dir.resolve(FILE_NAME), StandardCharsets.UTF_8));
    }

    private static void endpoint(ObjectNode n, String f, EndpointType t) {
        if (t == null) n.putNull(f);
        else n.putObject(f).put("type", t.type()).put("normaliser", t.normaliser());
    }

    private static EndpointType endpoint(JsonNode n) {
        return n.isObject() ? new EndpointType(text(n, "type"), text(n, "normaliser")) : null;
    }

    /** A {@code {name: count}} object in its written order, or null when absent. */
    private static Map<String, Long> counts(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || !v.isObject()) return null;
        Map<String, Long> out = new LinkedHashMap<>();
        v.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asLong()));
        return out;
    }

    private static void putNullable(ObjectNode n, String f, String v) {
        if (v == null) n.putNull(f); else n.put(f, v);
    }

    private static JsonNode req(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) throw new IllegalArgumentException("manifest is missing required field '" + f + "'");
        return v;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }
}
