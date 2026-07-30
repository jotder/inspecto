package com.gamma.asn.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.skybase.decoder.asn2.ASN1Reader;
import com.gamma.skybase.decoder.asn2.ASNConf;
import com.gamma.skybase.transformer2.Transformer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 0 golden-corpus capture (REDESIGN.md §6): drives TODAY'S legacy decoder
 * (asn2 ASN1Reader + transformer2) over every config/data pairing that works, and stores
 * the decoded record maps as JSONL plus flattened transform rows under corpus/.
 *
 * The output is the only objective definition of "current behaviour" — including its bugs.
 * Never "fix" a case here to produce nicer output; deviations are documented per phase.
 *
 * Legacy quirks this harness deliberately honours:
 * - HEADER_LENGTH must be a Long or ASN1Reader silently ignores it.
 * - Transformer.txConfig is STATIC: one case at a time, one Transformer per case, and
 *   never interleave cases.
 * - reader.next() returns an EMPTY map for a skipped record; counted separately.
 * - A broken record makes hasNext() false forever (silent truncation) — recCount and the
 *   per-file byte size are recorded so truncation is visible in the report.
 */
public final class GoldenCapture {

    /**
     * @param eventListTransform GMSC style (TestASNFiles.parseGMSC): don't transform the
     *                           file-level record wholesale (cartesian join OOMs); walk the
     *                           "callEventRecords" map and transform each event by its own
     *                           record-type key
     */
    record CaseSpec(String name, String asn, String root, int skipLines,
                    Long headerLength, Long recordHeaderLength,
                    String txJson, String dataDir, boolean eventListTransform) {
        CaseSpec(String name, String asn, String root, int skipLines,
                 Long headerLength, Long recordHeaderLength, String txJson, String dataDir) {
            this(name, asn, root, skipLines, headerLength, recordHeaderLength, txJson, dataDir, false);
        }
    }

    /**
     * The (grammar, root tag, skipLines, framing, tx) tuples live nowhere in config — only
     * in the legacy test drivers. This manifest is their durable home; sources noted per case.
     */
    static final List<CaseSpec> CASES = List.of(
            // RTDMS_ASN_Test.testMtnaOCC — tx key matches the decoded record's top key
            new CaseSpec("mtna_occ", "config/rtdms/mtna/occ/mtnOCC.asn", "ChargingDataOutputRecord", 11,
                    null, null, "config/rtdms/mtna/occ/mtn_occ_tx.json", "data/rtdms/mtna/INexamples/OCC"),
            // RTDMS_ASN_Test.testMtnaCCN
            new CaseSpec("mtna_ccn", "config/rtdms/mtna/ccn/mtnCCN.asn", "ChargingDataOutputRecord", 11,
                    null, null, "config/rtdms/mtna/ccn/ccn_tx.json", "data/rtdms/mtna/INexamples/CCN"),
            // RTDMS_ASN_Test.testMtnaSDP — decode only, its tx json is a generic stub
            new CaseSpec("mtna_sdp", "config/rtdms/mtna/sdp/sdp.asn", "SDPCallDataRecord", 11,
                    null, null, null, "data/rtdms/mtna/INexamples/SDP"),
            // RTDMS_ASN_Test.testAftelIMS
            new CaseSpec("aftel_ims", "config/rtdms/aftel/ims/aftelIMS.asn", "IMSRecord", 12,
                    null, null, "config/rtdms/aftel/ims/ims_tx_new.json", "data/rtdms/aftel/ims"),
            // RTDMS_ASN_Test.testMTNAhuwIMS — huwIMS.json is 0 bytes, decode only
            new CaseSpec("mtna_huwims", "config/rtdms/mtna/huwIMS/huwIMS.asn", "IMSRecord", 12,
                    null, null, null, "data/rtdms/mtna/huwIMS"),
            // RTDMS_ASN_Test.testMTNAhuwMsc / TestASNFiles.parseGMSC
            new CaseSpec("mtna_huwmsc", "config/rtdms/mtna/huwMsc/2980-gmsc.asn", "CallEventDataFile", 17,
                    null, null, "config/rtdms/mtna/huwMsc/huwMsc.json", "data/rtdms/mtna/huwMsc", true),
            // ZainHuwIMS — the only driver with real framing config (50-byte file header,
            // 4-byte record headers); zain_ims_tx2.json is a v2 sketch the legacy engine
            // cannot consume, so decode only
            new CaseSpec("zain_ims", "config/zain/sudan/ims/huwIMS.asn", "IMSRecord", 12,
                    50L, 4L, null, "data/zain/sudan/ims"),
            // Same Huawei framing assumed for the loose PS-domain file; grammar per
            // RTDMS_ASN_Test.pgwParse (root CallEventRecord, skip 5). Experimental — the
            // report records whatever happens.
            new CaseSpec("zain_pgw", "config/zain/sudan/pgw/huwSgsn.asn", "CallEventRecord", 5,
                    50L, 4L, null, "data/zain/sudan"),
            // SGSN_BVoxtelRoshan had stale paths; nearest present grammar. Experimental.
            new CaseSpec("awcc_sgsn", "config/rtdms/roshan/sgsn_roshan/gsn.asn", "CallEventRecord", 5,
                    null, null, null, "data/rtdms/awcc/sgsn"));

    /**
     * Whole files decode (counters cover full structural parity) but only the first N
     * records per file are written with content + transform rows — CCN/OCC records run
     * ~25 KB each and an uncapped corpus is ~350 MB, uncommittable. REDESIGN.md Phase 0
     * allows trimming samples.
     */
    private static final long MAX_CAPTURED_RECORDS_PER_FILE = 100;

    private final Path base;
    private final Path corpusDir;
    private final ObjectMapper json = new ObjectMapper();

    private GoldenCapture(Path base) {
        this.base = base;
        this.corpusDir = base.resolve("corpus");
    }

    /**
     * Usage: GoldenCapture &lt;baseDir&gt; [caseName]. Run one case per JVM (see run-golden.ps1):
     * the legacy transformer's static state and unbounded cartesian joins can OOM a case,
     * and per-case isolation turns that into a report entry instead of a dead run.
     */
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        if (!Files.isDirectory(base.resolve("config")) || !Files.isDirectory(base.resolve("data"))) {
            System.err.println("base dir must contain config/ and data/: " + base);
            System.exit(2);
        }
        String only = args.length > 1 ? args[1] : null;
        if (args.length > 1 && args[1].equals("--list")) {
            CASES.forEach(c -> System.out.println(c.name()));
            return;
        }
        new GoldenCapture(base).run(only);
    }

    private void run(String onlyCase) throws Exception {
        Files.createDirectories(corpusDir);
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        for (CaseSpec spec : CASES) {
            if (onlyCase != null && !spec.name().equals(onlyCase)) {
                continue;
            }
            // the legacy code narrates on System.out/err (printStackTrace, debug printlns);
            // mute it so harness output stays readable, keep our own channel
            PrintStream devNull = new PrintStream(java.io.OutputStream.nullOutputStream());
            System.setOut(devNull);
            System.setErr(devNull);
            Map<String, Object> report;
            try {
                report = captureCase(spec, realOut);
            } finally {
                System.setOut(realOut);
                System.setErr(realErr);
            }
            Path caseDir = corpusDir.resolve(spec.name());
            Files.createDirectories(caseDir);
            json.writerWithDefaultPrettyPrinter().writeValue(
                    caseDir.resolve("report.json").toFile(), report);
        }
        summarize(realOut);
    }

    /** Rebuilds SUMMARY.json from every per-case report present (cases may run in separate JVMs). */
    private void summarize(PrintStream out) throws IOException {
        List<Object> summary = new ArrayList<>();
        for (CaseSpec spec : CASES) {
            Path report = corpusDir.resolve(spec.name()).resolve("report.json");
            if (Files.exists(report)) {
                summary.add(json.readTree(report.toFile()));
            }
        }
        json.writerWithDefaultPrettyPrinter().writeValue(corpusDir.resolve("SUMMARY.json").toFile(), summary);
        out.println("summary: " + corpusDir.resolve("SUMMARY.json") + " (" + summary.size() + " cases)");
    }

    private Map<String, Object> captureCase(CaseSpec spec, PrintStream out) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("case", spec.name());
        report.put("grammar", spec.asn());
        report.put("rootTag", spec.root());
        report.put("skipLines", spec.skipLines());
        report.put("fileHeaderLength", spec.headerLength());
        report.put("recordHeaderLength", spec.recordHeaderLength());
        report.put("txConfig", spec.txJson());
        report.put("dataDir", spec.dataDir());
        List<Map<String, Object>> fileReports = new ArrayList<>();
        report.put("files", fileReports);
        out.println("--- case " + spec.name() + " ---");
        try {
            Path dataDir = base.resolve(spec.dataDir());
            if (!Files.isDirectory(dataDir)) {
                report.put("error", "data dir missing: " + dataDir);
                return report;
            }
            ASNConf conf = new ASNConf(base.resolve(spec.asn()).toString(), spec.root(), spec.skipLines());
            // Transformer.txConfig is static — construct once per case, cases run sequentially
            Transformer transformer = null;
            List<String> txKeys = List.of();
            if (spec.txJson() != null) {
                Path txPath = base.resolve(spec.txJson());
                transformer = new Transformer(txPath.toString());
                txKeys = topLevelKeys(txPath);
            }
            Path caseDir = corpusDir.resolve(spec.name());
            Files.createDirectories(caseDir);
            List<Path> dataFiles;
            try (var stream = Files.list(dataDir)) {
                dataFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return !n.endsWith(".gz") && !n.endsWith(".rar") && !n.endsWith(".zip");
                        })
                        .sorted()
                        .toList();
            }
            for (Path dataFile : dataFiles) {
                fileReports.add(captureFile(spec, conf, transformer, txKeys, caseDir, dataFile, out));
            }
        } catch (Exception e) {
            report.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return report;
    }

    private Map<String, Object> captureFile(CaseSpec spec, ASNConf conf, Transformer transformer,
                                            List<String> txKeys, Path caseDir, Path dataFile,
                                            PrintStream out) {
        Map<String, Object> fr = new LinkedHashMap<>();
        String fileName = dataFile.getFileName().toString();
        fr.put("file", spec.dataDir() + "/" + fileName);
        long records = 0;
        long emptyRecords = 0;
        long rows = 0;
        List<String> errors = new ArrayList<>();
        try (InputStream in = Files.newInputStream(dataFile);
             BufferedWriter recOut = Files.newBufferedWriter(
                     caseDir.resolve(fileName + ".records.jsonl"), StandardCharsets.UTF_8);
             BufferedWriter rowOut = transformer == null ? null : Files.newBufferedWriter(
                     caseDir.resolve(fileName + ".rows.jsonl"), StandardCharsets.UTF_8)) {

            Map<String, Object> fileStruct = new LinkedHashMap<>();
            if (spec.headerLength() != null) {
                fileStruct.put("HEADER_LENGTH", spec.headerLength()); // must be Long
            }
            if (spec.recordHeaderLength() != null) {
                fileStruct.put("RECORD_HEADER_LENGTH", spec.recordHeaderLength());
            }
            ASN1Reader reader = new ASN1Reader(in, conf, fileStruct);
            long index = -1;
            while (reader.hasNext()) {
                index++;
                LinkedHashMap<String, Object> rec;
                try {
                    rec = reader.next();
                } catch (Exception e) {
                    errors.add("record " + index + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    break; // the legacy reader does not recover reliably past this point
                }
                if (rec == null || rec.isEmpty()) {
                    emptyRecords++;
                    continue;
                }
                records++;
                if (records > MAX_CAPTURED_RECORDS_PER_FILE) {
                    continue; // keep decoding for the counters, stop capturing content
                }
                recOut.write(json.writeValueAsString(rec));
                recOut.newLine();
                if (transformer != null && spec.eventListTransform()) {
                    rows += transformEventLists(rec, index, transformer, txKeys, rowOut, errors);
                } else if (transformer != null) {
                    for (String key : rec.keySet()) {
                        if (!txKeys.contains(key)) {
                            continue;
                        }
                        try {
                            List<Map<String, Object>> txRows = transformer.transform(key, rec);
                            if (txRows != null) {
                                for (Map<String, Object> row : txRows) {
                                    Map<String, Object> line = new LinkedHashMap<>();
                                    line.put("record", index);
                                    line.put("type", key);
                                    line.put("row", row);
                                    rowOut.write(json.writeValueAsString(line));
                                    rowOut.newLine();
                                    rows++;
                                }
                            }
                        } catch (Throwable e) {
                            // Throwable on purpose: the legacy cartesian join can OOM on a
                            // single record; record it and move on (per-case JVM contains it)
                            errors.add("transform record " + index + " key " + key + ": "
                                    + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            fr.put("fileBytes", Files.size(dataFile));
            fr.put("readerRecCount", reader.getRecCount());
        } catch (Exception e) {
            errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        fr.put("records", records);
        fr.put("capturedRecords", Math.min(records, MAX_CAPTURED_RECORDS_PER_FILE));
        fr.put("emptyRecords", emptyRecords);
        fr.put("rows", rows);
        if (!errors.isEmpty()) {
            fr.put("errors", errors);
        }
        out.println("  " + fileName + ": records=" + records + " empty=" + emptyRecords
                + " rows=" + rows + (errors.isEmpty() ? "" : " errors=" + errors.size()));
        return fr;
    }

    /** GMSC style, mirroring TestASNFiles.parseGMSC: header/trailer skipped on purpose. */
    @SuppressWarnings("unchecked")
    private long transformEventLists(Map<String, Object> rec, long recordIndex, Transformer transformer,
                                     List<String> txKeys, BufferedWriter rowOut, List<String> errors) {
        long rows = 0;
        Object events = rec.get("callEventRecords");
        if (!(events instanceof Map<?, ?> byType)) {
            return 0;
        }
        for (Map.Entry<?, ?> e : byType.entrySet()) {
            String recordType = String.valueOf(e.getKey());
            if (!txKeys.contains(recordType)) {
                continue;
            }
            List<Object> list = e.getValue() instanceof List<?> l
                    ? (List<Object>) l : List.of(e.getValue());
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> event)) {
                    continue;
                }
                try {
                    List<Map<String, Object>> txRows =
                            transformer.transform(recordType, (Map<String, Object>) event);
                    if (txRows != null) {
                        for (Map<String, Object> row : txRows) {
                            Map<String, Object> line = new LinkedHashMap<>();
                            line.put("record", recordIndex);
                            line.put("type", recordType);
                            line.put("row", row);
                            rowOut.write(json.writeValueAsString(line));
                            rowOut.newLine();
                            rows++;
                        }
                    }
                } catch (Throwable t) {
                    errors.add("transform record " + recordIndex + " event " + recordType + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
        return rows;
    }

    private List<String> topLevelKeys(Path txJson) throws IOException {
        // the legacy Transformer strips blank lines and -- comments before parsing; mirror that
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(txJson, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        List<String> keys = new ArrayList<>();
        json.readTree(sb.toString()).fieldNames().forEachRemaining(keys::add);
        keys.removeIf(k -> k.startsWith("@"));
        return keys;
    }
}
