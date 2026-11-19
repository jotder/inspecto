package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.exec.ComponentPreview;
import com.gamma.pipeline.exec.SchemaSuggest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} BE-3: the column types the three type inferences answer for one file — the parse
 * preview ({@link ComponentPreview#parsing}, its {@code columnTypes}), schema suggest ({@link SchemaSuggest#infer} over
 * the rows that preview parsed, the way the two routes chain) and Add data ({@link OnboardDraft#analyse}, its proposed
 * column types) — pinned over the repo's sample files and inline fixtures.
 *
 * <p>Each answer is {@code name:TYPE} per column, with {@code /format} when a date or a date with a time is read with a
 * strptime format, and Add data's {@code mark} when a number needs its decimal mark. Since the operator's rulings of
 * 2026-11-19 the three give ONE answer (the engine's {@code com.gamma.parse.SampleProfiler}): every test here is an
 * agreement test. The cases that disagreed before the rulings are kept, renamed, under "were disagreements".
 */
class TypeInferenceGoldenTest {

    /** The parse preview over {@code text} under a schema-less draft that splits on {@code delimiter}. */
    private static ComponentPreview.GrammarResult preview(String text, String delimiter, String decimalMark) throws Exception {
        Map<String, Object> csv = new java.util.LinkedHashMap<>();
        csv.put("delimiter", delimiter);
        if (decimalMark != null) csv.put("decimal_separator", decimalMark);
        PipelineConfig cfg = PipelineConfig.fromMap(Map.of("name", "PREVIEW",
                "dirs", Map.of("poll", "in", "database", "out"),
                "processing", Map.of("threads", 1, "csv_settings", csv)));
        return ComponentPreview.parsing(cfg, text);
    }

    /**
     * {@code preview=[…] suggest=[…] addData=[…]}: the three answers for one delimited file. {@code decimalMark} is the
     * Grammar's {@code decimal_separator} the preview and suggest read (Add data detects its own), or {@code null}.
     */
    private static String answers(Path dir, String name, byte[] bytes, String delimiter, String decimalMark) throws Exception {
        ComponentPreview.GrammarResult p = preview(new String(bytes, StandardCharsets.UTF_8), delimiter, decimalMark);
        List<String> preview = new ArrayList<>();
        for (Map<String, String> c : p.columnTypes())
            preview.add(c.get("name") + ":" + c.get("type") + (c.get("format") == null ? "" : "/" + c.get("format")));
        List<String> suggest = new ArrayList<>();
        for (SchemaSuggest.Field f : SchemaSuggest.infer(p.rows(), decimalMark))
            suggest.add(f.name() + ":" + f.type() + (f.format() == null ? "" : "/" + f.format()));
        Path f = dir.resolve(name);
        Files.write(f, bytes);
        List<String> addData = new ArrayList<>();
        for (OnboardDraft.Col c : OnboardDraft.analyse(dir, f, name, OnboardDraft.fileType(f, name), Map.of()).cols())
            addData.add(c.name() + ":" + c.type() + (c.format() == null ? "" : "/" + c.format()) + (c.decimal() == null ? "" : " mark " + c.decimal()));
        return "preview=" + preview + " suggest=" + suggest + " addData=" + addData;
    }

    private static String answers(Path dir, String name, String text) throws Exception {
        return answers(dir, name, text.getBytes(StandardCharsets.UTF_8), ",", null);
    }

    /** The three answers, one column list said three times. */
    private static String all(String columns) {
        return "preview=" + columns + " suggest=" + columns + " addData=" + columns;
    }

    private static String sample(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static String column(String name, int rows, java.util.function.IntFunction<String> value) {
        StringBuilder sb = new StringBuilder(name).append('\n');
        for (int i = 1; i <= rows; i++) sb.append(value.apply(i)).append('\n');
        return sb.toString();
    }


    // ── the three agree ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void wholeNumbers(@TempDir Path dir) throws Exception {
        assertEquals(all("[n:BIGINT]"), answers(dir, "n.csv", "n\n1\n2\n30\n"));
    }

    @Test
    void decimalNumbers(@TempDir Path dir) throws Exception {
        assertEquals(all("[x:DOUBLE]"), answers(dir, "x.csv", "x\n1.5\n2.25\n-3.125\n"));
    }

    /** DECIMAL-TEXT-NOT-BIGINT-1: a decimal point makes a decimal, even with a zero fraction - in all three. */
    @Test
    void zeroFractionDecimals(@TempDir Path dir) throws Exception {
        assertEquals(all("[x:DOUBLE]"), answers(dir, "z.csv", "x\n47.00\n12.00\n"));
    }

    @Test
    void textValues(@TempDir Path dir) throws Exception {
        assertEquals(all("[name:VARCHAR]"), answers(dir, "t.csv", "name\nAnn\nBob\n"));
    }

    @Test
    void trueAndFalse(@TempDir Path dir) throws Exception {
        assertEquals(all("[b:BOOLEAN]"), answers(dir, "b.csv", "b\ntrue\nfalse\ntrue\n"));
    }

    @Test
    void zeroAndOneStayNumbers(@TempDir Path dir) throws Exception {
        assertEquals(all("[flag:BIGINT]"), answers(dir, "f.csv", "flag\n0\n1\n0\n"));
    }

    @Test
    void anEmptyColumnIsText(@TempDir Path dir) throws Exception {
        assertEquals(all("[a:BIGINT, b:VARCHAR]"), answers(dir, "e.csv", "a,b\n1,\n2,\n"));
    }

    /** The same type and format in all three (the preview named no format before the rulings). */
    @Test
    void isoDates(@TempDir Path dir) throws Exception {
        assertEquals(all("[d:DATE/%Y-%m-%d]"), answers(dir, "d.csv", "d\n2026-01-02\n2026-03-04\n"));
    }

    /** A day above 12 settles day-first for all three. */
    @Test
    void dayFirstDates(@TempDir Path dir) throws Exception {
        assertEquals(all("[d:DATE/%d/%m/%Y]"), answers(dir, "dmy.csv", "d\n13/04/2025\n03/04/2025\n"));
    }

    /** Day or month first undecided: no format is guessed, so the column stays text in all three. */
    @Test
    void ambiguousDatesStayText(@TempDir Path dir) throws Exception {
        assertEquals(all("[d:VARCHAR]"), answers(dir, "amb.csv", "d\n03/04/2025\n05/06/2025\n"));
    }

    /** A date with a time names the format it is read with in all three (only Add data did before the rulings). */
    @Test
    void datesWithATime(@TempDir Path dir) throws Exception {
        assertEquals(all("[t:TIMESTAMP/%Y-%m-%d %H:%M:%S]"),
                answers(dir, "ts.csv", "t\n2026-01-02 10:11:12\n2026-03-04 05:06:07\n"));
    }

    /** Ruling 1: ISO dates with a time written with a {@code T} - a TIMESTAMP with the {@code T} format in all three. */
    @Test
    void isoTimestampsWithAT(@TempDir Path dir) throws Exception {
        assertEquals(all("[t:TIMESTAMP/%Y-%m-%dT%H:%M:%S]"),
                answers(dir, "t.csv", "t\n2025-03-22T09:30:00\n2025-04-01T17:05:00\n"));
    }

    /** Almost all dates (19 of 20, one {@code n/a}): a date in all three, the odd value left blank (finding 3). */
    @Test
    void almostAllDates(@TempDir Path dir) throws Exception {
        assertEquals(all("[d:DATE/%Y-%m-%d]"),
                answers(dir, "d.csv", column("d", 20, i -> i == 7 ? "n/a" : "2025-03-" + (10 + i))));
    }

    /** Ruling 4: almost all whole numbers under a name that says an amount - text in all three (Add data said BIGINT). */
    @Test
    void almostAllWholeAmountsAreText(@TempDir Path dir) throws Exception {
        assertEquals(all("[amount:VARCHAR]"),
                answers(dir, "a.csv", column("amount", 20, i -> i == 7 ? "n/a" : String.valueOf(i * 10))));
    }

    /** Ruling 2: a Parquet file that declares a TIME column is a TIME column in Add data (it was text). */
    @Test
    void parquetTimeColumn(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.parquet");
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT TIME '09:30:00' AS t UNION ALL SELECT TIME '17:05:00') TO '"
                    + f.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        List<String> addData = new ArrayList<>();
        for (OnboardDraft.Col c : OnboardDraft.analyse(dir, f, "t.parquet", OnboardDraft.fileType(f, "t.parquet"), Map.of()).cols())
            addData.add(c.name() + ":" + c.type());
        assertEquals("[t:TIME]", addData.toString());
    }

    // ── the repo's sample files ──────────────────────────────────────────────────────────────────────────────

    @Test
    void helloSample(@TempDir Path dir) throws Exception {
        assertEquals(all("[id:BIGINT, customer:VARCHAR, event_date:DATE/%Y-%m-%d, amount:DOUBLE]"),
                answers(dir, "hello.csv", sample("examples/01-ingest/hello-csv/samples/hello.csv")));
    }

    @Test
    void fullCycleAccounts(@TempDir Path dir) throws Exception {
        assertEquals(all("[account_id:VARCHAR, holder_name:VARCHAR, city:VARCHAR, opened_on:DATE/%Y-%m-%d, type:VARCHAR]"),
                answers(dir, "accounts.csv", sample("src/test/resources/onboard/full-cycle/accounts.csv")));
    }

    // ── were disagreements before the operator's rulings (2026-11-19); now one answer ─────────────────────────

    /** Ruling 1: dates written in words ({@code March 22, 2025}) - a date with its format in all three. */
    @Test
    void datesWrittenInWordsAreDatesWithTheirFormatEverywhere(@TempDir Path dir) throws Exception {
        assertEquals(all("[d:DATE/%B %d, %Y]"),
                answers(dir, "words.csv", "d\n\"March 22, 2025\"\n\"April 1, 2025\"\n"));
    }

    /** Ruling 2: times of day - TIME in all three (suggest and Add data had no TIME). */
    @Test
    void timesOfDayAreTimeEverywhere(@TempDir Path dir) throws Exception {
        assertEquals(all("[t:TIME]"), answers(dir, "time.csv", "t\n09:30:00\n17:05:00\n"));
    }

    /**
     * Ruling 3: numbers with a decimal comma - a number in all three once the Grammar says {@code decimal_separator: ","}
     * (the preview and suggest read the key; Add data detects the mark and carries it).
     */
    @Test
    void decimalCommaNumbersAreNumbersEverywhereUnderTheGrammarKey(@TempDir Path dir) throws Exception {
        assertEquals("preview=[id:BIGINT, amount:DOUBLE] suggest=[id:BIGINT, amount:DOUBLE] addData=[id:BIGINT, amount:DOUBLE mark ,]",
                answers(dir, "semi.csv", "id;amount\n1;3,50\n2;4,25\n".getBytes(StandardCharsets.UTF_8), ";", ","));
    }

    /** Without the key the preview and suggest read {@code 3,50} as text, as the engine would read it. */
    @Test
    void decimalCommaNumbersWithoutTheKeyAreTextInThePreviewAndSuggest(@TempDir Path dir) throws Exception {
        assertEquals("preview=[id:BIGINT, amount:VARCHAR] suggest=[id:BIGINT, amount:VARCHAR] addData=[id:BIGINT, amount:DOUBLE mark ,]",
                answers(dir, "semi.csv", "id;amount\n1;3,50\n2;4,25\n".getBytes(StandardCharsets.UTF_8), ";", null));
    }

    /** Ruling 4: a column that is almost all numbers (19 of 20, one {@code n/a}) - text everywhere. */
    @Test
    void almostAllNumbersAreTextEverywhere(@TempDir Path dir) throws Exception {
        assertEquals(all("[amount:VARCHAR]"),
                answers(dir, "amount.csv", column("amount", 20, i -> i == 7 ? "n/a" : i + ".5")));
    }

    /**
     * The full-cycle transfers fixture carries one {@code abc} amount and one {@code not-a-date} booking time among 461
     * rows: the amount is text everywhere (ruling 4: one value would not survive a number), the booking time a date with
     * a time read with the {@code T} format everywhere (ruling 1; almost all of its values are, finding 3).
     */
    @Test
    void theFullCycleTransfersFixture(@TempDir Path dir) throws Exception {
        assertEquals(all("[transfer_id:VARCHAR, from_account:VARCHAR, to_account:VARCHAR, amount:VARCHAR, "
                        + "booked_at:TIMESTAMP/%Y-%m-%dT%H:%M:%S, channel:VARCHAR]"),
                answers(dir, "transfers.csv", sample("src/test/resources/onboard/full-cycle/transfers.csv")));
    }
}
