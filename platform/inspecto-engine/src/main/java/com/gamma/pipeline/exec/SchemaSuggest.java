package com.gamma.pipeline.exec;

import com.gamma.parse.SampleProfiler;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>G1 ({@code consignment-chain-plan.md} S4) — draft-schema inference over already-parsed sample
 * rows.</b> TRY_CAST voting on a throwaway DuckDB (the {@link ComponentPreview} scratch idiom): per
 * column, the most specific candidate type every non-blank value accepts wins — {@code BIGINT} →
 * {@code DOUBLE} → {@code TIMESTAMP} (demoted to {@code DATE} when every value is midnight, i.e. the
 * strings carried no time part) → {@code BOOLEAN} — else {@code VARCHAR}. Text written with a decimal
 * point or an exponent is never {@code BIGINT}, even {@code 47.00}. {@code BIGINT} is checked
 * before {@code BOOLEAN} so a {@code 0/1} column stays numeric. A column no bare cast accepts is then
 * tried against {@link SampleProfiler#DATE_FORMATS} (human spellings such as {@code March 22,2025}); a
 * unique fit votes {@code DATE} <b>with that strptime format</b>, which the draft carries as
 * {@code raw.fields[].format} so the engine parses the column exactly that way at ingest. The vote itself
 * lives in the engine's sample profiler ({@link SampleProfiler#types}, {@code BASIC-ELT-PROFILE-1} BE-3
 * slice 2), which Add data's draft and the parse preview also call.
 *
 * <p><b>A draft, never applied.</b> The caller returns it for a human to edit (the
 * {@code ParserPlugin.suggest} posture: "never auto-applied"), and real ingest keeps
 * {@code auto_detect=false} ({@code DuckDbCsvIngester}) — inference belongs at authoring time,
 * determinism at run time. An all-null/blank column is {@code VARCHAR}: unknown is not evidence.
 */
public final class SchemaSuggest {

    private SchemaSuggest() {}

    /**
     * One inferred field of the draft {@code raw.fields} list. {@code format} is the strptime pattern a
     * {@code DATE} or {@code TIMESTAMP} is read with ({@code raw.fields[].format}, see
     * {@code SchemaFieldTypes.formatsOf}), else {@code null}.
     */
    public record Field(String name, String type, String format) {
        public Field(String name, String type) {
            this(name, type, null);
        }
    }

    /** A field whose declared type no longer matches what the current sample votes for. */
    public record TypeChange(String name, String declared, String suggested) {}

    /**
     * How a schema draft has drifted from what the CURRENT sample suggests (B3) — the backing for §5.2's
     * drift indicator and its non-clobbering re-sync. Entries are keyed by the draft's field <b>name</b>,
     * the same anchor {@link com.gamma.config.safety.SchemaCompatibility} uses, so a grid can mark cells.
     */
    public record Drift(List<Field> added, List<Field> missing, List<TypeChange> typeChanged) {
        public boolean isEmpty() {
            return added.isEmpty() && missing.isEmpty() && typeChanged.isEmpty();
        }
    }

    /**
     * Infer a draft field list from {@code sampleRows} (the parsing preview's own output shape:
     * string-valued maps). Throws {@link IllegalArgumentException} on an empty or column-less sample.
     */
    public static List<Field> infer(List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        return infer(sampleRows, null);
    }

    /**
     * {@link #infer(List)} reading number columns through {@code decimalMark} ({@code "."} or {@code ","}): the
     * Grammar's {@code decimal_separator}, so {@code 3,50} under {@code ","} is a {@code DOUBLE} as in the parse preview
     * and Add data ({@code BASIC-ELT-PROFILE-1} U3). {@code null} = no mark.
     */
    public static List<Field> infer(List<Map<String, Object>> sampleRows, String decimalMark)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");

        File db = DuckDbUtil.tempDbFile("suggest_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, "suggest_input", columns, sampleRows);
            // the vote is the sample profiler's (BASIC-ELT-PROFILE-1 BE-3 slice 2), the one Add data proposes from too
            List<Field> out = new ArrayList<>(columns.size());
            for (SampleProfiler.ColumnType c : SampleProfiler.types(conn, "suggest_input", 0, decimalMark))
                out.add(new Field(c.name(), c.type(), c.format()));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);   // throwaway scratch DB
        }
    }

    /**
     * Diff a schema {@code draft} against what {@code inferred} (this sample's vote) now says — B3.
     * Pure: no I/O, so the caller runs {@link #infer} once and diffs against it.
     *
     * <p>The join key is the draft field's <b>selector</b> (its name when blank): the selector is what
     * points into the parsed sample, while {@code name} is the output column an author is free to rename
     * deliberately. Keying on name would report every intentional rename as drift.
     *
     * <p>⛔ <b>There is no {@code renamed} category, and cannot be one from these two inputs.</b> A renamed
     * source column is indistinguishable from one removed and another added — the same fact
     * {@link com.gamma.config.safety.SchemaCompatibility} states for the save gate ("a rename reads as
     * remove+add"). It surfaces here as a {@code missing} + {@code added} pair; presenting that pair as a
     * likely rename is a UI affordance over the two facts, never a claim this server makes.
     *
     * <p>A field with no declared type contributes no {@code typeChanged}: nothing to have changed FROM
     * (again matching the compatibility gate's treatment of an absent old type).
     *
     * <p>Name-addressed: every selector names a sample column. See {@link #drift(Map, List, boolean)}.
     */
    public static Drift drift(Map<String, Object> draft, List<Field> inferred) {
        return drift(draft, inferred, false);
    }

    /**
     * {@link #drift(Map, List)} for a frontend whose selectors may be zero-based column <b>positions</b> — delimited and
     * fixed-width ({@code byIndex}, {@code PipelineConfig.selectsByColumnIndex}): a selector of digits there names the
     * sample column at that position in {@code inferred}'s order (the order {@link #infer} returns, the sample's own),
     * not a column called {@code "0"}. Keying it on the name instead reported every field of a registrable delimited
     * schema missing and every sample column added ({@code LA-ONBOARD-DRIFT-1}). Any other selector is still a name.
     */
    public static Drift drift(Map<String, Object> draft, List<Field> inferred, boolean byIndex) {
        Map<String, Field> bySampleColumn = new LinkedHashMap<>();
        for (Field f : inferred) bySampleColumn.put(f.name(), f);

        List<Field> missing = new ArrayList<>();
        List<TypeChange> typeChanged = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();

        for (Map<String, Object> df : ComponentPreview.schemaFields(draft)) {
            String name = text(df.get("name"));
            if (name.isEmpty()) continue;
            String selector = text(df.get("selector"));
            String key = selector.isEmpty() ? name : selector;
            String declared = text(df.get("type"));

            Field match = byIndex && key.matches("\\d{1,9}")
                    ? (Integer.parseInt(key) < inferred.size() ? inferred.get(Integer.parseInt(key)) : null)
                    : bySampleColumn.get(key);
            if (match == null) {
                missing.add(new Field(name, declared));
                continue;
            }
            claimed.add(match.name());
            if (!declared.isEmpty() && !declared.equalsIgnoreCase(match.type()))
                typeChanged.add(new TypeChange(name, declared, match.type()));
        }

        List<Field> added = new ArrayList<>();
        for (Field f : inferred) if (!claimed.contains(f.name())) added.add(f);

        return new Drift(List.copyOf(added), List.copyOf(missing), List.copyOf(typeChanged));
    }

    /** Trimmed text, never null — the draft is caller-supplied JSON. */
    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
