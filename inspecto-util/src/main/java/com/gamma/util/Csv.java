package com.gamma.util;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single home for <b>RFC4180 CSV reading</b>. Every CSV read in the platform must go
 * through here so the parser choice is made exactly once: the RFC4180 parser treats
 * backslashes as literal characters, whereas opencsv's default {@code CSVParser} treats
 * {@code '\'} as an escape character and silently strips it from Windows paths
 * ({@code "C:\db\out.csv"} → {@code "C:dbout.csv"}). That bug previously had to be fixed
 * in four separate copies of this code (audit readers, status store, pre-ETL utilities).
 *
 * <p>Matches the writers' quoting convention (fields wrapped in double quotes, embedded
 * quotes replaced with single quotes — see {@link CsvLedger#q}).
 */
public final class Csv {

    private Csv() {}

    /** An RFC4180 {@link CSVReader} over {@code reader} (caller closes; backslashes literal). */
    public static CSVReader reader(Reader reader) {
        return new CSVReaderBuilder(reader)
                .withCSVParser(new RFC4180ParserBuilder().build()).build();
    }

    /**
     * Legacy header spellings mapped to their canonical name as rows are read — <b>the accept-both-on-read half
     * of the {@code batch_id} → {@code consignment_id} rename</b> (consignment-ELT plan §11.3, decision 2).
     *
     * <p><b>Why the rename needs this, and why here.</b> {@link CsvLedger} writes a header only when the file
     * does not yet exist, so existing audit ledgers keep {@code batch_id} forever while new ones get
     * {@code consignment_id} — and {@code FileStatusStore.readRuns} globs <em>many</em> run-timestamped files
     * into one row list, so both spellings legitimately coexist <b>in the same result</b>. Fixing that at each
     * consumer would mean every one of them checking two keys forever, and any consumer missed would fail
     * <em>silently</em> (a wrong key reads as an absent column, not an error). Normalising once, here at the
     * only header-keyed reader, means every consumer downstream sees exactly one spelling.
     *
     * <p>This intentionally does <b>not</b> keep the legacy key alongside the canonical one: a row carrying both
     * would re-introduce the ambiguity, and would surface the extra column to
     * {@code OperationalTables}' drift warning as un-queryable ledger drift.
     */
    private static final Map<String, String> LEGACY_HEADERS = Map.of("batch_id", "consignment_id");

    /**
     * Append each data row of a header-bearing CSV to {@code out} as an ordered
     * header→value map (short rows pad with {@code ""}). Rows read before a mid-file
     * parse error remain in {@code out} — the caller decides how to report the failure.
     *
     * <p>Header names are canonicalised on the way in; see {@link #LEGACY_HEADERS}.
     */
    public static void readInto(Path file, List<Map<String, String>> out)
            throws IOException, CsvValidationException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVReader csv = reader(r)) {
            String[] header = csv.readNext();
            if (header == null) return;
            String[] row;
            while ((row = csv.readNext()) != null) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < header.length; i++)
                    m.put(LEGACY_HEADERS.getOrDefault(header[i], header[i]),
                            i < row.length ? row[i] : "");
                out.add(m);
            }
        }
    }
}
