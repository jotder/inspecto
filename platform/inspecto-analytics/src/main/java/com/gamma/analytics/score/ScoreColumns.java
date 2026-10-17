package com.gamma.analytics.score;

import java.util.ArrayList;
import java.util.List;

/**
 * The column schema of a score kind's history and {@code _latest} Datasets: {@link #HEAD}, then the kind's own columns
 * (score, band or high flag, explanation...), then {@link #TAIL}. Both Datasets have the same columns.
 */
public final class ScoreColumns {
    private ScoreColumns() {}

    /** How a column reads in a Dataset's result shape. */
    public enum Role { DIMENSION, MEASURE, TEMPORAL }

    /**
     * One column: its name, DuckDB type ({@code VARCHAR}, {@code DOUBLE}, {@code BOOLEAN}, {@code INTEGER} or
     * {@code TIMESTAMP}) and role.
     */
    public record Column(String name, String type, Role role) {
        public static Column dimension(String name, String type) { return new Column(name, type, Role.DIMENSION); }
        public static Column measure(String name, String type) { return new Column(name, type, Role.MEASURE); }
        public static Column temporal(String name, String type) { return new Column(name, type, Role.TEMPORAL); }
    }

    /** Every score row starts with: which model, which entity type, which entity. */
    public static final List<Column> HEAD = List.of(
            Column.dimension("model", "VARCHAR"),
            Column.dimension("entity_type", "VARCHAR"),
            Column.dimension("entity_key", "VARCHAR"));

    /** Every score row ends with: the model's content hash, the run, and when it was scored. */
    public static final List<Column> TAIL = List.of(
            Column.dimension("model_version", "VARCHAR"),
            Column.dimension("run_id", "VARCHAR"),
            Column.temporal("scored_at", "TIMESTAMP"));

    /** The full schema of a kind whose own columns are {@code kindColumns}. */
    public static List<Column> table(List<Column> kindColumns) {
        List<Column> all = new ArrayList<>(HEAD);
        all.addAll(kindColumns);
        all.addAll(TAIL);
        return List.copyOf(all);
    }
}
