package com.gamma.pipeline.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>The one reader of a sink's {@code partitions[]} declaration.</b> {@link PartitionSinkWriter} acts on it and
 * {@link ComponentPreview} predicts what that act will be, so the two must agree by construction rather than by
 * inspection — they drifted twice while the rules lived in both files, and each drift cost an author their
 * event-time bounds with no warning anywhere.
 *
 * <p>The declaration is a list whose entries are either a bare column name or a map carrying {@code column} (the
 * partition directory segment) and optionally {@code source} (the raw column that segment was derived from). It
 * takes the raw {@code partitions} value rather than a {@code PipelineNode} because the preview holds only a
 * decoded config map — {@code node.cfg("partitions")} on one side, {@code content.get("partitions")} on the other.
 *
 * <p>Deliberately <em>not</em> unified with {@code PartitionDef.fromSchema} on the ingest side: that reader
 * requires a {@code type} per entry, hard-fails on a non-list, and carries the legacy {@code partitionKey}
 * fallback. Same config word, genuinely different contract — folding them together would import a hard failure
 * into a write path whose posture is to degrade (D3).
 */
final class SinkPartitions {

    private SinkPartitions() {}

    /** A partition {@code source} is embedded in {@code min()}/{@code max()} SQL, so it must be a plain
     *  identifier — the same fail-closed check {@code DatasetRelation.temporalColumn} applies. */
    static final Pattern SAFE_COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The declared partition columns in declaration order ({@code []} when absent or not a list). */
    static List<String> columns(Object partitions) {
        List<String> cols = new ArrayList<>();
        if (partitions instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("column") != null) cols.add(m.get("column").toString());
                else if (o != null && !o.toString().isBlank()) cols.add(o.toString());
            }
        }
        return cols;
    }

    /**
     * The distinct {@code source} values declared across the entries, trimmed, in declaration order. A
     * present-but-blank {@code source} is kept as {@code ""}: it voids the event time at the writer, so it must
     * stay visible to the preview rather than being filtered out as noise. Entries that are bare strings, or that
     * carry no {@code source}, contribute nothing.
     */
    static List<String> declaredSources(Object partitions) {
        Set<String> out = new LinkedHashSet<>();
        if (partitions instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || m.get("source") == null) continue;
                out.add(m.get("source").toString().trim());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * The single {@code source} column the entries agree on and that is safe to embed in SQL, or {@code null}
     * when the sink identifies no one event time — mirroring {@code PartitionDef.eventTimeDef} on the ingest
     * side, where two different sources mean no single event time is identified.
     *
     * <p>Null for four distinct declarations, and the preview names each one separately: none declared, two that
     * disagree, one that is blank, and one that is not a plain identifier. Derived from {@link #declaredSources}
     * rather than parsing again, so "what the writer will do" and "what the preview warns about" cannot diverge.
     */
    static String eventTimeSource(Object partitions) {
        List<String> declared = declaredSources(partitions);
        if (declared.size() != 1) return null;                  // none declared, or they disagree
        String source = declared.get(0);
        return SAFE_COLUMN.matcher(source).matches() ? source : null;   // blank or not an identifier
    }
}
