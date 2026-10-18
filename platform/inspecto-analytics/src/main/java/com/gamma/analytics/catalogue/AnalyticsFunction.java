package com.gamma.analytics.catalogue;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One entry of the Analytics function catalogue (ANALYTICS-CORE-1 C8): a function the Analytics Kernel offers, described
 * by its stable {@link #id()}, its {@link Param parameters}, a {@link Cost cost hint} and the {@link ResultKind kind of
 * result} it yields — the same four facts LA's {@code Algorithm} enum publishes for the graph algorithms.
 *
 * <p>The catalogue DESCRIBES; it never runs anything. A function that is also a pipeline Step names it in {@link #step()};
 * one that is reachable from Java or SQL only has none. Entries are added by an {@link AnalyticsFunctionProvider}.
 */
public record AnalyticsFunction(String id, String label, Kernel kernel, Cost cost, ResultKind result, Set<Form> forms,
                                String step, String summary, List<Param> params) {

    /** The kernel a function belongs to: the unit a reader of the catalogue groups by. */
    public enum Kernel {
        STATISTICS("Statistics"), PROFILER("Column profiler"), TIME_SERIES("Time series"), FORECAST("Forecasting"),
        SIMILARITY("Similarity"), GROUPING("Entity grouping"), GRAPH("Graph");

        private final String label;

        Kernel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * A HINT of how the work grows with the input rows (or pairs), not a guarantee: there is no per-function ceiling here
     * as there is for graphs, because the kernel builds SQL the caller's own engine runs.
     */
    public enum Cost { CONSTANT, LINEAR, LINEARITHMIC, QUADRATIC }

    /** What a call returns. */
    public enum ResultKind {
        /** One number (or none, when undefined). */
        SCALAR,
        /** One value per input row, added as a column. */
        COLUMN,
        /** A result set. */
        TABLE,
        /** A SQL text for the caller to run. */
        SQL,
        /** A group or cluster assignment per record. */
        GROUPS,
        /** Forecast rows with prediction intervals. */
        FORECAST,
        /** A list of further catalogued functions. */
        CATALOGUE
    }

    /** Where the function can run: on the JVM over arrays, as DuckDB SQL it renders, or both with parity tests. */
    public enum Form { JVM, SQL }

    /** A parameter's value type; {@link #CHOICE} names its values in {@link Param#choices()}. */
    public enum ParamType { INTEGER, DECIMAL, TEXT, CHOICE, BOOLEAN, COLUMN, COLUMNS }

    /** One parameter; {@code defaultValue} is null when the caller must supply it, {@code min} / {@code max} bound a number. */
    public record Param(String name, ParamType type, String defaultValue, Double min, Double max, List<String> choices,
                        String description) {
        public Param {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            choices = List.copyOf(choices);
            if (type == ParamType.CHOICE && choices.isEmpty())
                throw new IllegalArgumentException("choice parameter '" + name + "' names no values");
            if (type != ParamType.CHOICE && !choices.isEmpty())
                throw new IllegalArgumentException("only a choice parameter names values: '" + name + "'");
        }

        public static Param integer(String name, Integer def, int min, int max, String description) {
            return new Param(name, ParamType.INTEGER, def == null ? null : def.toString(), (double) min, (double) max,
                    List.of(), description);
        }

        public static Param decimal(String name, Double def, double min, double max, String description) {
            return new Param(name, ParamType.DECIMAL, def == null ? null : def.toString(), min, max, List.of(), description);
        }

        public static Param choice(String name, String def, List<String> choices, String description) {
            return new Param(name, ParamType.CHOICE, def, null, null, choices, description);
        }

        public static Param column(String name, String description) {
            return new Param(name, ParamType.COLUMN, null, null, null, List.of(), description);
        }

        public static Param columns(String name, String description) {
            return new Param(name, ParamType.COLUMNS, null, null, null, List.of(), description);
        }

        public static Param text(String name, String def, String description) {
            return new Param(name, ParamType.TEXT, def, null, null, List.of(), description);
        }
    }

    public AnalyticsFunction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kernel, "kernel");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(result, "result");
        forms = Set.copyOf(forms);
        if (forms.isEmpty()) throw new IllegalArgumentException("function '" + id + "' runs in no form");
        if (!id.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("function id '" + id + "' is not dotted lower-case (e.g. stats.mean)");
        params = List.copyOf(params);
    }
}
