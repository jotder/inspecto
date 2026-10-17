package com.gamma.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.analytics.score.ExplanationPart;
import com.gamma.analytics.score.ScoreColumns;
import com.gamma.analytics.score.ScoreKind;
import com.gamma.analytics.score.ScoreRunFiles;
import com.gamma.job.ResultSetMeta;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes one score run through the score output contract ({@code com.gamma.analytics.score}, ANALYTICS-CORE-1 M7,
 * D-AN6): the I/O half, kept in the engine beside {@link ScoreOutputDirs} because it needs DuckDB, Jackson and the
 * ownership marker, none of which the Analytics Kernel may depend on. Every score kind (Risk Score, Anomaly Score)
 * writes through this one implementation.
 */
public final class ScoreOutputWriter {
    private ScoreOutputWriter() {}

    private static final ObjectMapper SORTED = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** One entity's row: its key and the values of the kind's own columns, in their order. */
    public record Row(String entityKey, List<Object> values) {}

    /**
     * Append this run to the history Dataset {@code kind.scores(modelId)} and swap {@code kind.latest(modelId)} to it.
     * Both directories must be this model's (created with its marker, or already carrying it); both files land as
     * {@code *.tmp} and are revealed by an atomic move, so a reader never sees a half-written file. Retention then
     * prunes older history runs, never {@code _latest} or this run.
     *
     * <p>Kind column values bind by type: {@code VARCHAR} a String, {@code DOUBLE} / {@code INTEGER} a Number,
     * {@code BOOLEAN} a Boolean, {@code TIMESTAMP} a {@link LocalDateTime}.
     *
     * @return the history directory written
     */
    public static Path write(Path dataDir, ScoreKind kind, String modelId, String entityType,
                             List<ScoreColumns.Column> kindColumns, List<Row> rows, String modelVersion, String runId,
                             Instant scoredAt, ScoreRunFiles.Retention retention) throws SQLException, IOException {
        Path history = dataDir.resolve(kind.scores(modelId)).normalize();
        Path latest = dataDir.resolve(kind.latest(modelId)).normalize();
        if (!history.startsWith(dataDir.normalize()) || !latest.startsWith(dataDir.normalize()))
            throw new IllegalArgumentException("scores Dataset escapes the data root");
        ScoreOutputDirs.claim(history, kind.ownerMarker(), kind.kind(), modelId);
        ScoreOutputDirs.claim(latest, kind.ownerMarker(), kind.kind(), modelId);
        String name = ScoreRunFiles.name(scoredAt, runId);
        Path histTmp = history.resolve(name + ".tmp");
        Path latestTmp = latest.resolve(name + ".tmp");
        List<ScoreColumns.Column> columns = ScoreColumns.table(kindColumns);
        List<String> ddl = new ArrayList<>(columns.size());
        for (ScoreColumns.Column c : columns) ddl.add(c.name() + " " + c.type());
        try (Connection c = DuckDbUtil.openInMemory(DuckDbUtil.spillDirUnder(dataDir), List.of(history, latest));
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE s (" + String.join(", ", ddl) + ")");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO s VALUES ("
                    + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")")) {
                Timestamp ts = Timestamp.from(scoredAt);
                for (Row r : rows) {
                    int i = 1;
                    ps.setString(i++, modelId);
                    ps.setString(i++, entityType);
                    ps.setString(i++, r.entityKey());
                    for (int k = 0; k < kindColumns.size(); k++) bind(ps, i++, kindColumns.get(k), r.values().get(k));
                    ps.setString(i++, modelVersion);
                    ps.setString(i++, runId);
                    ps.setTimestamp(i, ts);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("COPY s TO " + parquetPath(histTmp) + " (FORMAT PARQUET)");
            st.execute("COPY s TO " + parquetPath(latestTmp) + " (FORMAT PARQUET)");
        }
        Files.move(histTmp, history.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        ScoreOutputDirs.swapIn(latest, latestTmp, name, ScoreRunFiles.GLOB);
        prune(history, retention, scoredAt, name);
        return history;
    }

    private static void bind(PreparedStatement ps, int i, ScoreColumns.Column c, Object v) throws SQLException {
        switch (c.type()) {
            case "VARCHAR" -> ps.setString(i, (String) v);
            case "DOUBLE" -> ps.setDouble(i, ((Number) v).doubleValue());
            case "INTEGER" -> ps.setInt(i, ((Number) v).intValue());
            case "BOOLEAN" -> ps.setBoolean(i, (Boolean) v);
            case "TIMESTAMP" -> ps.setTimestamp(i, Timestamp.valueOf((LocalDateTime) v));
            default -> throw new IllegalArgumentException("score column '" + c.name() + "' has unsupported type " + c.type());
        }
    }

    /** Delete the history runs {@code retention} expires; a no-op when it is off. */
    static void prune(Path history, ScoreRunFiles.Retention retention, Instant now, String keep) throws IOException {
        if (retention.days() == null && retention.runs() == null) return;
        List<String> runs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(history, ScoreRunFiles.GLOB)) {
            for (Path p : ds) runs.add(p.getFileName().toString());
        }
        for (String f : retention.expired(runs, now, keep)) Files.deleteIfExists(history.resolve(f));
    }

    private static String parquetPath(Path p) {
        return SqlIdent.sqlStr(p.toString().replace('\\', '/'));
    }

    /**
     * A short, stable content hash of a model as stored: the {@code model_version} every row carries. The component
     * envelope ({@code envelopeKeys}: name, owner, shares) is left out, so re-sharing a model does not change it.
     *
     * @param what names the model in the failure ({@code risk-score model})
     */
    public static String version(Map<String, Object> modelContent, Set<String> envelopeKeys, String what) {
        try {
            Map<String, Object> scored = new TreeMap<>(modelContent);
            scored.keySet().removeAll(envelopeKeys);
            byte[] canonical = SORTED.writeValueAsBytes(scored);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)).substring(0, 12);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash " + what, e);
        }
    }

    /**
     * The explanation column's JSON: the array of each part's {@link ExplanationPart#toMap()}, in order.
     *
     * @param what names the parts in the failure ({@code factors}, {@code features})
     */
    public static String explanationJson(List<? extends ExplanationPart> parts, String what) {
        try {
            List<Map<String, Object>> out = new ArrayList<>(parts.size());
            for (ExplanationPart p : parts) out.add(p.toMap());
            return new ObjectMapper().writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + what, e);
        }
    }

    /** A score Dataset's result shape for {@code kindColumns}, as a Job declares its artifact. */
    public static ResultSetMeta meta(List<ScoreColumns.Column> kindColumns) {
        List<ResultSetMeta.Column> out = new ArrayList<>();
        for (ScoreColumns.Column c : ScoreColumns.table(kindColumns))
            out.add(new ResultSetMeta.Column(c.name(), c.type(), ResultSetMeta.Role.valueOf(c.role().name())));
        return new ResultSetMeta(out);
    }
}
