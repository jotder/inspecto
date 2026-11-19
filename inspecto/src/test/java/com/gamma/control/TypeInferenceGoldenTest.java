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
 * {@code BASIC-ELT-PROFILE-1} BE-3 slice 2: the column types the three type inferences answer for one file — the parse
 * preview ({@link ComponentPreview#parsing}, its {@code columnTypes}: DuckDB's own {@code read_csv} auto-detect), schema
 * suggest ({@link SchemaSuggest#infer} over the rows that preview parsed, the way the two routes chain) and Add data
 * ({@link OnboardDraft#analyse}, its proposed column types) — pinned over the repo's sample files and inline fixtures, so
 * moving the inference into one engine service ({@code com.gamma.parse.SampleProfiler}) cannot change any answer.
 *
 * <p>Each answer is {@code name:TYPE} per column, with {@code /format} when a date was proven with a strptime format.
 * Where the three agree a test says so; where they answer the same column differently a test named
 * {@code disagreement…} keeps each caller's own answer — the operator decides which one wins.
 */
class TypeInferenceGoldenTest {

    /** The parse preview over {@code text} under a schema-less draft that splits on {@code delimiter}. */
    private static ComponentPreview.GrammarResult preview(String text, String delimiter) throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(Map.of("name", "PREVIEW",
                "dirs", Map.of("poll", "in", "database", "out"),
                "processing", Map.of("threads", 1, "csv_settings", Map.of("delimiter", delimiter))));
        return ComponentPreview.parsing(cfg, text);
    }

    /** {@code preview=[…] suggest=[…] addData=[…]}: the three answers for one delimited file. */
    private static String answers(Path dir, String name, byte[] bytes, String delimiter) throws Exception {
        ComponentPreview.GrammarResult p = preview(new String(bytes, StandardCharsets.UTF_8), delimiter);
        List<String> preview = new ArrayList<>();
        for (Map<String, String> c : p.columnTypes()) preview.add(c.get("name") + ":" + c.get("type"));
        List<String> suggest = new ArrayList<>();
        for (SchemaSuggest.Field f : SchemaSuggest.infer(p.rows()))
            suggest.add(f.name() + ":" + f.type() + (f.format() == null ? "" : "/" + f.format()));
        Path f = dir.resolve(name);
        Files.write(f, bytes);
        List<String> addData = new ArrayList<>();
        for (OnboardDraft.Col c : OnboardDraft.analyse(dir, f, name, OnboardDraft.fileType(f, name), Map.of()).cols())
            addData.add(c.name() + ":" + c.type() + (c.format() == null ? "" : "/" + c.format()) + (c.decimal() == null ? "" : " mark " + c.decimal()));
        return "preview=" + preview + " suggest=" + suggest + " addData=" + addData;
    }

    private static String answers(Path dir, String name, String text) throws Exception {
        return answers(dir, name, text.getBytes(StandardCharsets.UTF_8), ",");
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

    /**
     * The same type in all three; the formats differ only in what each reports: the preview reports none (DuckDB's
     * {@code columnTypes} has a name and a type), schema suggest and Add data the strptime format the date was proven with.
     */
    @Test
    void isoDates(@TempDir Path dir) throws Exception {
        assertEquals("preview=[d:DATE] suggest=[d:DATE/%Y-%m-%d] addData=[d:DATE/%Y-%m-%d]",
                answers(dir, "d.csv", "d\n2026-01-02\n2026-03-04\n"));
    }

    /** A day above 12 settles day-first for all three (DuckDB's own sniff included). */
    @Test
    void dayFirstDates(@TempDir Path dir) throws Exception {
        assertEquals("preview=[d:DATE] suggest=[d:DATE/%d/%m/%Y] addData=[d:DATE/%d/%m/%Y]",
                answers(dir, "dmy.csv", "d\n13/04/2025\n03/04/2025\n"));
    }

    /** The same type; only Add data names the format a date with a time is read with. */
    @Test
    void datesWithATime(@TempDir Path dir) throws Exception {
        assertEquals("preview=[t:TIMESTAMP] suggest=[t:TIMESTAMP] addData=[t:TIMESTAMP/%Y-%m-%d %H:%M:%S]",
                answers(dir, "ts.csv", "t\n2026-01-02 10:11:12\n2026-03-04 05:06:07\n"));
    }

    // ── the repo's sample files ──────────────────────────────────────────────────────────────────────────────

    @Test
    void helloSample(@TempDir Path dir) throws Exception {
        assertEquals("preview=[id:BIGINT, customer:VARCHAR, event_date:DATE, amount:DOUBLE] "
                        + "suggest=[id:BIGINT, customer:VARCHAR, event_date:DATE/%Y-%m-%d, amount:DOUBLE] "
                        + "addData=[id:BIGINT, customer:VARCHAR, event_date:DATE/%Y-%m-%d, amount:DOUBLE]",
                answers(dir, "hello.csv", sample("examples/01-ingest/hello-csv/samples/hello.csv")));
    }

    @Test
    void fullCycleAccounts(@TempDir Path dir) throws Exception {
        assertEquals("preview=[account_id:VARCHAR, holder_name:VARCHAR, city:VARCHAR, opened_on:DATE, type:VARCHAR] "
                        + "suggest=[account_id:VARCHAR, holder_name:VARCHAR, city:VARCHAR, opened_on:DATE/%Y-%m-%d, type:VARCHAR] "
                        + "addData=[account_id:VARCHAR, holder_name:VARCHAR, city:VARCHAR, opened_on:DATE/%Y-%m-%d, type:VARCHAR]",
                answers(dir, "accounts.csv", sample("src/test/resources/onboard/full-cycle/accounts.csv")));
    }

    // ── where they answer differently (each caller's own answer kept; the operator decides) ───────────────────

    /** Dates written in words ({@code March 22, 2025}): DuckDB's sniff leaves them text; the vote reads them as dates. */
    @Test
    void disagreementDatesWrittenInWordsAreTextInThePreviewButDatesInSuggestAndAddData(@TempDir Path dir) throws Exception {
        assertEquals("preview=[d:VARCHAR] suggest=[d:DATE/%B %d, %Y] addData=[d:DATE/%B %d, %Y]",
                answers(dir, "words.csv", "d\n\"March 22, 2025\"\n\"April 1, 2025\"\n"));
    }

    /** Times of day: DuckDB's sniff has a TIME type; the vote has no such candidate (and the schema no such type). */
    @Test
    void disagreementTimesOfDayAreTimeInThePreviewButTextInSuggestAndAddData(@TempDir Path dir) throws Exception {
        assertEquals("preview=[t:TIME] suggest=[t:VARCHAR] addData=[t:VARCHAR]",
                answers(dir, "time.csv", "t\n09:30:00\n17:05:00\n"));
    }

    /** Numbers written with a decimal comma: only Add data detects the decimal mark (C1) and reads them as numbers. */
    @Test
    void disagreementDecimalCommaNumbersAreNumbersOnlyInAddData(@TempDir Path dir) throws Exception {
        assertEquals("preview=[id:BIGINT, amount:VARCHAR] suggest=[id:BIGINT, amount:VARCHAR] addData=[id:BIGINT, amount:DOUBLE mark ,]",
                answers(dir, "semi.csv", "id;amount\n1;3,50\n2;4,25\n".getBytes(StandardCharsets.UTF_8), ";"));
    }

    /**
     * A column that is almost all numbers (19 of 20, one {@code n/a}): every value must fit for the preview and the vote,
     * so it stays text; Add data proposes the number at low confidence and blanks the odd one (finding 3).
     */
    @Test
    void disagreementAlmostAllNumbersAreNumbersOnlyInAddData(@TempDir Path dir) throws Exception {
        assertEquals("preview=[amount:VARCHAR] suggest=[amount:VARCHAR] addData=[amount:DOUBLE]",
                answers(dir, "amount.csv", column("amount", 20, i -> i == 7 ? "n/a" : i + ".5")));
    }

    /**
     * The full-cycle transfers fixture carries one {@code abc} amount and one {@code not-a-date} booking time among 461
     * rows: text in the preview and schema suggest, a number and a date with a time (with the {@code T} format) in Add
     * data — the two Add data layers above (finding 3 and the timestamp format) on a real sample.
     */
    @Test
    void disagreementTheFullCycleTransfersFixture(@TempDir Path dir) throws Exception {
        assertEquals("preview=[transfer_id:VARCHAR, from_account:VARCHAR, to_account:VARCHAR, amount:VARCHAR, booked_at:VARCHAR, channel:VARCHAR] "
                        + "suggest=[transfer_id:VARCHAR, from_account:VARCHAR, to_account:VARCHAR, amount:VARCHAR, booked_at:VARCHAR, channel:VARCHAR] "
                        + "addData=[transfer_id:VARCHAR, from_account:VARCHAR, to_account:VARCHAR, amount:DOUBLE, "
                        + "booked_at:TIMESTAMP/%Y-%m-%dT%H:%M:%S, channel:VARCHAR]",
                answers(dir, "transfers.csv", sample("src/test/resources/onboard/full-cycle/transfers.csv")));
    }
}
