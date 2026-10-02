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
 */
public record IndexManifest(long version, String builtAt, Builder builder, String duckdbVersion, String bucketFn,
                            int buckets, int rowGroupSize, IndexMapping mapping, String mappingHash, String dataset,
                            String relationSqlHash, String baseFingerprint, String timeColZone,
                            Map<String, TableStats> tables, long droppedNull, List<Delta> deltas, String parent) {

    public static final int FORMAT_VERSION = 1;
    public static final String FILE_NAME = "manifest.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public enum Builder { FULL, APPEND }

    public record TableStats(long rows, long files, long bytes) { }

    /** An append delta layered on the base: its directory name and size. */
    public record Delta(String dir, long rows, long bytes) { }

    public IndexManifest {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(dataset, "dataset");
        if (!mapping.hash().equals(mappingHash)) throw new IllegalArgumentException("mappingHash does not match the mapping");
        tables = tables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        deltas = deltas == null ? List.of() : List.copyOf(deltas);
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
        Builder b;
        try {
            b = Builder.valueOf(req(n, "builder").asText().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("manifest builder is not full|append: " + n.get("builder").asText());
        }
        return new IndexManifest(req(n, "version").asLong(), text(n, "builtAt"), b, text(req(n, "duckdb"), "version"),
                text(n, "bucketFn"), n.path("buckets").asInt(), n.path("rowGroupSize").asInt(), mapping,
                req(n, "mappingHash").asText(), req(n, "dataset").asText(), text(n, "relationSqlHash"),
                text(n, "baseFingerprint"), text(n, "timeColZone"), tables, n.path("droppedNull").asLong(), deltas,
                text(n, "parent"));
    }

    public void write(Path dir) throws IOException {
        Files.writeString(dir.resolve(FILE_NAME), toJson(), StandardCharsets.UTF_8);
    }

    public static IndexManifest read(Path dir) throws IOException {
        return fromJson(Files.readString(dir.resolve(FILE_NAME), StandardCharsets.UTF_8));
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
