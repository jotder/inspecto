package com.gamma.control;

import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.exec.ComponentPreview;
import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} BE-3 slice 1: the dialect (delimiter, quote, header) the two callers of DuckDB's
 * {@code sniff_csv} answer today — Add data's draft ({@link OnboardDraft#analyse}) and the parse preview
 * ({@link ComponentPreview#parsing}, its {@code resolved} line) — pinned over the repo's sample files and inline fixtures,
 * so moving the sniff into one engine service ({@code com.gamma.parse.SampleProfiler}) cannot change either answer.
 *
 * <p>Slice 2 settled the three places they differed (operator ruling 2026-11-19, "follow the sniff"): Add data reads a
 * quote not found as no quote, so a stray quote is text, and skips the lines the sniff skips; the tests at the end pin
 * that. The parse preview takes pasted TEXT, not bytes, so the encoding fixtures (Latin-1, UTF-16) pin Add data alone.
 */
class DialectSniffGoldenTest {

    private static final String DATE = ", date_format=%Y-%m-%d";

    /** The parse preview's {@code resolved} dialect for {@code bytes} read as UTF-8 text, under a schema-less draft. */
    private static String preview(byte[] bytes) throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(Map.of("name", "PREVIEW",
                "dirs", Map.of("poll", "in", "database", "out"), "processing", Map.of("threads", 1)));
        return ComponentPreview.parsing(cfg, new String(bytes, StandardCharsets.UTF_8)).resolved().toString();
    }

    private static OnboardDraft.Analysis draft(Path dir, String name, byte[] bytes, Map<String, String> overrides) throws Exception {
        Path f = dir.resolve(name);
        Files.write(f, bytes);
        return OnboardDraft.analyse(dir, f, name, OnboardDraft.fileType(f, name), overrides);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> choice(OnboardDraft.Analysis a, String key) {
        return (Map<String, Object>) a.format().get(key);
    }

    /** Add data's dialect, as {@code type=… delimiter=… quote=… header=… cols=[…]}, each with its confidence. */
    private static String addData(OnboardDraft.Analysis a) {
        StringBuilder b = new StringBuilder();
        for (String k : new String[] {"type", "delimiter", "quote", "header"}) {
            Map<String, Object> c = choice(a, k);
            b.append(k).append('=').append(c.get("value")).append('(').append(c.get("confidence")).append(") ");
        }
        return b.append("cols=").append(a.cols().stream().map(OnboardDraft.Col::name).toList()).toString();
    }

    private static void golden(Path dir, String name, byte[] bytes, String previewDialect, String addDataDialect) throws Exception {
        assertEquals(previewDialect, preview(bytes), "parse preview: " + name);
        assertEquals(addDataDialect, addData(draft(dir, name, bytes, Map.of())), "Add data: " + name);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sample(String path) throws Exception {
        return Files.readAllBytes(Path.of(path));
    }

    private static String sniffed(String delim, String quote, String escape, int skip, boolean header, String more) {
        return "{delimiter=" + delim + ", quote=" + quote + ", escape=" + escape + ", comment=(empty), skip_header_lines=" + skip
                + ", has_header=" + header + more + "}";
    }

    // ── the repo's sample files ──────────────────────────────────────────────────────────────────────────────

    @Test
    void commaWithAHeaderLine(@TempDir Path dir) throws Exception {
        golden(dir, "hello.csv", sample("examples/01-ingest/hello-csv/samples/hello.csv"),
                sniffed(",", "(empty)", "(empty)", 0, true, DATE),
                "type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[id, customer, event_date, amount]");
    }

    @Test
    void commaWithNoHeaderLine(@TempDir Path dir) throws Exception {
        golden(dir, "txn.csv", sample("examples/02-parsing/no-header/samples/txn.csv"),
                sniffed(",", "(empty)", "(empty)", 0, false, DATE),
                "type=csv(low) delimiter=,(low) quote=(low) header=false(low) cols=[col_1, col_2, col_3, col_4]");
    }

    @Test
    void pipeDelimited(@TempDir Path dir) throws Exception {
        // Add data reads .csv / .tsv / .txt names: the .psv sample is added under a .csv name
        golden(dir, "txn.csv", sample("examples/02-parsing/pipe-delimited/samples/txn.psv"),
                sniffed("|", "(empty)", "(empty)", 0, true, DATE),
                "type=csv(low) delimiter=|(low) quote=(low) header=true(low) cols=[id, region, event_date, amount]");
    }

    @Test
    void theFullCycleAccountsFixture(@TempDir Path dir) throws Exception {
        golden(dir, "accounts.csv", sample("src/test/resources/onboard/full-cycle/accounts.csv"),
                sniffed(",", "(empty)", "(empty)", 0, true, DATE),
                "type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[account_id, holder_name, city, opened_on, type]");
    }

    @Test
    void theFullCycleTransfersFixtureIsLargeEnoughForHighConfidence(@TempDir Path dir) throws Exception {
        byte[] bytes = sample("src/test/resources/onboard/full-cycle/transfers.csv");
        golden(dir, "transfers.csv", bytes, sniffed(",", "(empty)", "(empty)", 0, true, ""),
                "type=csv(high) delimiter=,(high) quote=(high) header=true(high) "
                        + "cols=[transfer_id, from_account, to_account, amount, booked_at, channel]");
        assertEquals("every one of 461 sampled lines splits into 6 fields on ','",
                choice(draft(dir, "transfers.csv", bytes, Map.of()), "delimiter").get("evidence"));
    }

    /** A ragged file (one line carries an extra field): DuckDB 2.0 settles on '|', a character the file never holds. */
    @Test
    void aRaggedFileSniffsAsPipeInBoth(@TempDir Path dir) throws Exception {
        golden(dir, "readings.csv", sample("examples/03-schema-transform/reject-routing/samples/readings.csv"),
                sniffed("|", "(empty)", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=|(low) quote=(low) header=true(low) cols=[meter_id_event_date_reading]");
    }

    // ── inline fixtures ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void semicolon(@TempDir Path dir) throws Exception {
        golden(dir, "semi.csv", utf8("id;name;amount\n1;Ann;3,50\n2;Bob;4,25\n"),
                sniffed(";", "(empty)", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=;(low) quote=(low) header=true(low) cols=[id, name, amount]");
    }

    @Test
    void tabInATsvAndInACsvName(@TempDir Path dir) throws Exception {
        byte[] tab = utf8("id\tname\n1\tAnn\n2\tBob\n");
        golden(dir, "tab.tsv", tab, sniffed("\t", "(empty)", "(empty)", 0, true, ""),
                "type=tsv(low) delimiter=\t(low) quote=(low) header=true(low) cols=[id, name]");
        golden(dir, "tab.csv", tab, sniffed("\t", "(empty)", "(empty)", 0, true, ""),
                "type=tsv(low) delimiter=\t(low) quote=(low) header=true(low) cols=[id, name]");
    }

    @Test
    void doubleQuotedFieldsWithTheDelimiterAndDoubledQuotesInside(@TempDir Path dir) throws Exception {
        golden(dir, "quoted.csv", utf8("id,name,note\n1,\"Smith, Ann\",\"said \"\"hi\"\"\"\n2,\"Lee, Bob\",plain\n"),
                sniffed(",", "\"", "\"", 0, true, ""),
                "type=csv(low) delimiter=,(low) quote=\"(low) header=true(low) cols=[id, name, note]");
    }

    @Test
    void singleQuotedFields(@TempDir Path dir) throws Exception {
        golden(dir, "squote.csv", utf8("id,name\n1,'Smith, Ann'\n2,'Lee, Bob'\n"),
                sniffed(",", "'", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=,(low) quote='(low) header=true(low) cols=[id, name]");
    }

    @Test
    void aUtf8ByteOrderMarkIsNotPartOfTheFirstName(@TempDir Path dir) throws Exception {
        golden(dir, "bom.csv", utf8("﻿id,name\n1,Ann\n2,Bob\n"),
                sniffed(",", "(empty)", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[id, name]");
    }

    @Test
    void oneColumnKeepsTheCommaDefault(@TempDir Path dir) throws Exception {
        golden(dir, "onecol.csv", utf8("name\nAnn\nBob\n"),
                sniffed(",", "(empty)", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[name]");
    }

    @Test
    void allTextLinesAreReadAsAHeader(@TempDir Path dir) throws Exception {
        golden(dir, "alltext.csv", utf8("Ann,Bob\nCid,Dan\nEve,Fay\n"),
                sniffed(",", "(empty)", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[Ann, Bob]");
    }

    @Test
    void crlfLineEndings(@TempDir Path dir) throws Exception {
        golden(dir, "crlf.csv", utf8("a,b\r\n1,2\r\n3,4\r\n"),
                sniffed(",", "(empty)", "(empty)", 0, true, ""),
                "type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[a, b]");
    }

    /**
     * Mixed line endings: {@code sniff_csv} cannot settle the dialect. The preview fails; Add data falls back to its own
     * delimiter guess ({@code guessDelimiter}) but its read of the file then fails the same way, so the file is refused
     * with the reader's reason. (Add data's fallback is reached, never seen to succeed on DuckDB 2.0.)
     */
    @Test
    void mixedLineEndingsAreUnreadableInBoth(@TempDir Path dir) throws Exception {
        byte[] bytes = utf8("a,b\r\n1,2\n3,4\r5,6\n");
        SQLException p = assertThrows(SQLException.class, () -> preview(bytes));
        assertTrue(p.getMessage().contains("Error when sniffing file"), p.getMessage());
        ApiException a = assertThrows(ApiException.class, () -> draft(dir, "mixed.csv", bytes, Map.of()));
        assertEquals(422, a.status);
        assertTrue(a.getMessage().startsWith("'mixed.csv' could not be read as CSV: Invalid Input Error: Error when sniffing file"),
                a.getMessage());
    }

    // ── encodings (Add data alone: the parse preview takes text) ─────────────────────────────────────────────

    @Test
    void latin1IsRefusedUntilChosenThenSniffedInLatin1(@TempDir Path dir) throws Exception {
        byte[] bytes = "id,name\n1,José\n2,Renée\n".getBytes(StandardCharsets.ISO_8859_1);
        Exception refused = assertThrows(Exception.class, () -> draft(dir, "latin1.csv", bytes, Map.of()));
        assertTrue(refused.getMessage().contains("is not UTF-8 text; read it as Latin-1"), refused.getMessage());
        assertEquals("type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[id, name]",
                addData(draft(dir, "latin1.csv", bytes, Map.of("encoding", "latin-1"))));
        // the sniff itself reads the chosen encoding: only a settled sniff over 200+ lines is high confidence (the
        // fallback guess, taken when the sniff throws, never is)
        byte[] big = ("id,name\n" + "1,José\n".repeat(210)).getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("high", choice(draft(dir, "latin1big.csv", big, Map.of("encoding", "latin-1")), "delimiter").get("confidence"));
    }

    @Test
    void utf16IsRefusedUntilChosenThenSniffedInUtf16(@TempDir Path dir) throws Exception {
        byte[] bytes = "﻿id,name\n1,Ann\n2,Bob\n".getBytes(StandardCharsets.UTF_16LE);
        Exception refused = assertThrows(Exception.class, () -> draft(dir, "utf16.csv", bytes, Map.of()));
        assertTrue(refused.getMessage().contains("is not UTF-8 text; read it as UTF-16"), refused.getMessage());
        assertEquals("type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[id, name]",
                addData(draft(dir, "utf16.csv", bytes, Map.of("encoding", "utf-16"))));
        byte[] big = ("﻿id,name\n" + "1,Ann\n".repeat(210)).getBytes(StandardCharsets.UTF_16LE);
        assertEquals("high", choice(draft(dir, "utf16big.csv", big, Map.of("encoding", "utf-16")), "delimiter").get("confidence"));
    }

    // ── once differences, now one rule: Add data follows the sniff (operator ruling 2026-11-19) ─────────────────

    /**
     * No quote found: the preview passes DuckDB's spelling {@code (empty)} through (escape and comment too); Add data
     * reads it as no quote (it kept {@code "} until BE-3 slice 2).
     */
    @Test
    void noQuoteFoundIsNoQuoteInBoth(@TempDir Path dir) throws Exception {
        byte[] bytes = utf8("id,name\n1,Ann\n2,Bob\n");
        String p = preview(bytes);
        assertTrue(p.contains("quote=(empty), escape=(empty), comment=(empty)"), p);
        Map<String, Object> quote = choice(draft(dir, "plain.csv", bytes, Map.of()), "quote");
        assertEquals("", quote.get("value"));
        assertEquals("no field is quoted", quote.get("evidence"));
    }

    /**
     * A stray quote the sniff reads as "no quote": both read the file, the quote kept in its value (Add data refused the
     * file until BE-3 slice 2: it read it with {@code "} and met an unterminated quote).
     */
    @Test
    void aStrayQuoteIsReadAsTextInBoth(@TempDir Path dir) throws Exception {
        byte[] bytes = utf8("a,b\n1,\"x\n2,3\n4,5\n");
        assertEquals(sniffed(",", "(empty)", "(empty)", 0, true, ""), preview(bytes));
        OnboardDraft.Analysis a = draft(dir, "stray.csv", bytes, Map.of());
        assertEquals("type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[a, b]", addData(a));
        assertEquals(3, a.rows());
        assertEquals("\"x", a.preview().get(0).get("b"));
    }

    /**
     * Lines before the header: the sniff skips them ({@code skip_header_lines}) and so does Add data, which reads the real
     * header and says so ({@code format.skipLines}; it read the first line as its header until BE-3 slice 2).
     */
    @Test
    void rowsTheSniffSkipsAreSkippedInBoth(@TempDir Path dir) throws Exception {
        byte[] bytes = utf8("# exported\na,b\n1,2\n3,4\n");
        assertEquals(sniffed(",", "(empty)", "(empty)", 1, true, ""), preview(bytes));
        OnboardDraft.Analysis a = draft(dir, "skip.csv", bytes, Map.of());
        assertEquals("type=csv(low) delimiter=,(low) quote=(low) header=true(low) cols=[a, b]", addData(a));
        assertEquals(2, a.rows());
        assertEquals(1, choice(a, "skipLines").get("value"));
        assertEquals("the first line comes before the header line and is not read", choice(a, "skipLines").get("evidence"));
        assertNull(draft(dir, "noskip.csv", utf8("a,b\n1,2\n3,4\n"), Map.of()).format().get("skipLines"),
                "a file the sniff skips nothing of has no skipLines choice");
    }

    /** The person's own choice still wins over the sniff: a quote, a delimiter and the header line. */
    @Test
    void theUsersChoicesWinOverTheSniff(@TempDir Path dir) throws Exception {
        byte[] bytes = utf8("id;name\n1;Ann\n2;Bob\n");
        OnboardDraft.Analysis a = draft(dir, "chosen.csv", bytes, Map.of("quote", "\"", "delimiter", ",", "header", "false"));
        assertEquals("type=csv(low) delimiter=,(high) quote=\"(high) header=false(high) cols=[col_1]", addData(a));
    }
}
