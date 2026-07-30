package com.gamma.asn.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.RecordReader;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.schema.Asn1Parser;
import com.gamma.asn.schema.CompiledSchema;
import com.gamma.asn.schema.DecoderRegistry;
import com.gamma.asn.schema.NamedNode;
import com.gamma.asn.schema.SchemaBinder;
import com.gamma.asn.schema.SchemaCompiler;
import com.gamma.asn.schema.ast.ModuleAst;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parity harness (REDESIGN.md §6, Phase 1/2 exit criteria): drives the NEW stack
 * (asn-core framing/BER + asn-schema grammar/binder) over every golden-corpus case and
 * compares against the legacy capture:
 *
 * - structural parity: new record count vs the legacy full-file counters in report.json
 *   (legacy "records" excludes the records it silently skipped as empty; those must show
 *   up here as records whose tag does not match the compiled root);
 * - content parity: the first capturedRecords records are bound to named trees and their
 *   decoded leaves compared, as a (leaf name, value) multiset per record, against the
 *   captured records.jsonl head. Leaf-name multiset comparison deliberately ignores the
 *   two stacks' structural naming differences (element-type layers, array indexing) and
 *   measures what Phase 2 owes: same fields decoded to same values.
 *
 * Output: corpus/&lt;case&gt;/parity.json per case + corpus/PARITY.md summary. This harness
 * never fails — it measures; ParityTest pins the levels that must not regress.
 */
public final class ParityCheck {

    private static final int MAX_EXAMPLES = 25;
    private static final int MAX_NAME_ROWS = 20;

    private final Path base;
    private final Path corpusDir;
    private final ObjectMapper json = new ObjectMapper();

    ParityCheck(Path base) {
        this.base = base;
        this.corpusDir = base.resolve("corpus");
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        if (!Files.isDirectory(base.resolve("corpus"))) {
            System.err.println("base dir must contain corpus/ (run GoldenCapture first): " + base);
            System.exit(2);
        }
        String only = args.length > 1 ? args[1] : null;
        // the bridged legacy functions printStackTrace/println on ordinary records —
        // mute them (their failures are part of the parity contract), keep our channel
        PrintStream realOut = System.out;
        PrintStream devNull = new PrintStream(java.io.OutputStream.nullOutputStream());
        System.setOut(devNull);
        System.setErr(devNull);
        try {
            new ParityCheck(base).run(only, realOut);
        } finally {
            System.setOut(realOut);
        }
    }

    /** Runs every case with a corpus report present; returns the per-case parity maps. */
    List<Map<String, Object>> run(String onlyCase, PrintStream out) throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        for (GoldenCapture.CaseSpec spec : GoldenCapture.CASES) {
            if (onlyCase != null && !spec.name().equals(onlyCase)) {
                continue;
            }
            Path report = corpusDir.resolve(spec.name()).resolve("report.json");
            if (!Files.exists(report)) {
                continue;
            }
            out.println("--- parity " + spec.name() + " ---");
            Map<String, Object> parity = checkCase(spec, json.readTree(report.toFile()), out);
            json.writerWithDefaultPrettyPrinter().writeValue(
                    corpusDir.resolve(spec.name()).resolve("parity.json").toFile(), parity);
            all.add(parity);
        }
        if (onlyCase == null) { // a partial run must not clobber the full summary
            writeSummary(all);
            out.println("summary: " + corpusDir.resolve("PARITY.md"));
        }
        return all;
    }

    private Map<String, Object> checkCase(GoldenCapture.CaseSpec spec, JsonNode legacyReport,
                                          PrintStream out) {
        Map<String, Object> parity = new LinkedHashMap<>();
        parity.put("case", spec.name());
        List<Map<String, Object>> fileResults = new ArrayList<>();
        parity.put("files", fileResults);
        CompiledSchema schema;
        List<String> warnings = new ArrayList<>();
        try {
            schema = compileGrammar(spec, warnings);
            parity.put("grammarWarnings", warnings.size());
        } catch (Exception e) {
            parity.put("error", "grammar: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return parity;
        }
        DecoderRegistry registry = DecoderRegistry.withDefaults();
        com.gamma.asn.transform.LegacyTransformEngine engine = null;
        List<String> txKeys = List.of();
        if (spec.txJson() != null) {
            try {
                Path txPath = base.resolve(spec.txJson());
                com.gamma.asn.transform.TxConfig cfg = com.gamma.asn.transform.TxConfig.load(txPath);
                @SuppressWarnings("unchecked")
                Map<String, Object> lookups = (Map<String, Object>) cfg.section("@simpleLookup");
                engine = new com.gamma.asn.transform.LegacyTransformEngine(
                        cfg, com.gamma.asn.transform.FunctionRegistry.fromProviders(
                                () -> lookups, ParityCheck.class.getClassLoader()));
                txKeys = cfg.root().keySet().stream().filter(k -> !k.startsWith("@")).toList();
            } catch (Exception e) {
                parity.put("txError", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        for (JsonNode legacyFile : legacyReport.path("files")) {
            String rel = legacyFile.path("file").asText();
            Path dataFile = base.resolve(rel);
            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("file", rel);
            fileResults.add(fr);
            if (!Files.isRegularFile(dataFile)) {
                fr.put("error", "data file missing");
                continue;
            }
            try {
                checkFile(spec, schema, registry, engine, txKeys, dataFile, legacyFile, fr);
            } catch (Exception e) {
                fr.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.println("  " + dataFile.getFileName() + ": " + summaryLine(fr));
        }
        return parity;
    }

    /**
     * Unlike the legacy reader (which drops the first skipLines lines and with them the
     * module header), the new parser reads the whole file: the header's IMPLICIT/EXPLICIT
     * TAGS default is load-bearing for tag resolution.
     */
    private CompiledSchema compileGrammar(GoldenCapture.CaseSpec spec, List<String> warnings)
            throws IOException {
        String text = Files.readString(base.resolve(spec.asn()), StandardCharsets.ISO_8859_1);
        List<ModuleAst> modules = Asn1Parser.parseLenient(text, warnings);
        return SchemaCompiler.compileLenient(modules, spec.root(), warnings);
    }

    private void checkFile(GoldenCapture.CaseSpec spec, CompiledSchema schema,
                           DecoderRegistry registry,
                           com.gamma.asn.transform.LegacyTransformEngine engine,
                           List<String> txKeys, Path dataFile, JsonNode legacyFile,
                           Map<String, Object> fr) throws IOException {
        // 0x00/0xFF padding always: legacy readTag unconditionally skips both before
        // every record tag (ASN1Utils.readTag), e.g. the zero-fill in Ericsson OCC files
        Framing framing = Framing.of(new Framing.FramingSpec(
                spec.headerLength() == null ? 0 : spec.headerLength(), 0, Set.of(0x00, 0xFF),
                spec.recordHeaderLength() == null ? null
                        : Framing.RecordHeaderSpec.skipOnly(Math.toIntExact(spec.recordHeaderLength()))));

        long legacyRecords = legacyFile.path("records").asLong();
        long legacyEmpty = legacyFile.path("emptyRecords").asLong();
        long captured = legacyFile.path("capturedRecords").asLong();

        List<String> newErrors = new ArrayList<>();
        long nonEmpty = 0;
        long empty = 0;
        ContentTally tally = new ContentTally();
        ContentTally rowTally = new ContentTally();
        Map<Long, List<JsonNode>> legacyRows = engine == null ? Map.of()
                : loadLegacyRows(spec, dataFile);
        long newRowCount = 0;
        long exactRowMatches = 0;

        try (ByteSource src = ByteSource.map(dataFile);
             BufferedReader legacyLines = openRecords(spec, dataFile)) {
            RecordReader reader = new RecordReader(src, framing, Strictness.BER,
                    RecoveryPolicy.STOP_FILE,
                    e -> newErrors.add("record " + e.recordIndex() + " @" + e.fileOffset()
                            + ": " + e.message() + " -> " + e.action()));
            SchemaBinder binder = new SchemaBinder(schema, src, registry);
            while (reader.hasNext()) {
                NamedNode node = binder.bind(reader.next());
                List<String[]> newLeaves = leaves(node);
                // legacy skipped records that decoded to an empty map without capturing
                // or counting them as records; a bound tree with no leaves is the same case
                if (newLeaves.isEmpty()) {
                    empty++;
                    continue;
                }
                nonEmpty++;
                if (legacyLines == null || nonEmpty > captured) {
                    continue;
                }
                String line = legacyLines.readLine();
                if (line == null) {
                    continue;
                }
                tally.compareRecord(leaves(json.readTree(line)), newLeaves);
                if (engine != null) {
                    List<Map<String, Object>> rows =
                            transformRows(spec, engine, txKeys, RecordMapper.toMap(node));
                    newRowCount += rows.size();
                    exactRowMatches += tallyRows(rowTally,
                            legacyRows.getOrDefault(nonEmpty - 1, List.of()), rows);
                }
            }
            fr.put("newRecordsOk", reader.recordsOk());
            fr.put("newRecordsFailed", reader.recordsFailed());
        }
        fr.put("newNonEmptyRecords", nonEmpty);
        fr.put("newEmptyRecords", empty);
        fr.put("legacyRecords", legacyRecords);
        // legacy counts one extra empty record when a file ends in padding (its reader
        // returns an empty map at EOF); reported as data, not part of the parity verdict
        fr.put("legacyEmptyRecords", legacyEmpty);
        boolean structural = nonEmpty == legacyRecords;
        fr.put("structuralParity", structural);
        if (!newErrors.isEmpty()) {
            fr.put("newErrors", newErrors);
        }
        fr.put("content", tally.report());
        if (engine != null) {
            Map<String, Object> rows = new LinkedHashMap<>();
            rows.put("legacyRows", legacyRows.values().stream().mapToLong(List::size).sum());
            rows.put("newRows", newRowCount);
            rows.put("exactRowMatches", exactRowMatches);
            rows.put("content", rowTally.report());
            fr.put("rows", rows);
        }
    }

    // ---- rows parity (Phase 3) --------------------------------------------------------

    /** Mirrors GoldenCapture: transform each top-level record key found in the tx config. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> transformRows(GoldenCapture.CaseSpec spec,
                                                    com.gamma.asn.transform.LegacyTransformEngine engine,
                                                    List<String> txKeys, Map<String, Object> record) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (spec.eventListTransform()) { // GMSC mode: per-event transform by record type
            if (record.get("callEventRecords") instanceof Map<?, ?> byType) {
                for (Map.Entry<?, ?> e : byType.entrySet()) {
                    String type = String.valueOf(e.getKey());
                    if (!txKeys.contains(type)) {
                        continue;
                    }
                    List<Object> events = e.getValue() instanceof List<?> l
                            ? (List<Object>) l : List.of(e.getValue());
                    for (Object event : events) {
                        if (event instanceof Map<?, ?> m) {
                            rows.addAll(engine.transform(type, (Map<String, Object>) m));
                        }
                    }
                }
            }
            return rows;
        }
        for (String key : record.keySet()) {
            if (txKeys.contains(key)) {
                rows.addAll(engine.transform(key, record));
            }
        }
        return rows;
    }

    /** Legacy rows.jsonl grouped by record index. */
    private Map<Long, List<JsonNode>> loadLegacyRows(GoldenCapture.CaseSpec spec, Path dataFile)
            throws IOException {
        Path rowsFile = corpusDir.resolve(spec.name())
                .resolve(dataFile.getFileName() + ".rows.jsonl");
        Map<Long, List<JsonNode>> byRecord = new LinkedHashMap<>();
        if (!Files.exists(rowsFile)) {
            return byRecord;
        }
        try (BufferedReader r = Files.newBufferedReader(rowsFile, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {
                JsonNode n = json.readTree(line);
                byRecord.computeIfAbsent(n.path("record").asLong(), k -> new ArrayList<>())
                        .add(n.path("row"));
            }
        }
        return byRecord;
    }

    /** Leaf-multiset tally over all rows of one record, plus exact whole-row matches. */
    private long tallyRows(ContentTally rowTally, List<JsonNode> legacy,
                           List<Map<String, Object>> fresh) {
        List<String[]> legacyLeaves = new ArrayList<>();
        List<String> legacySignatures = new ArrayList<>();
        for (JsonNode row : legacy) {
            legacyLeaves.addAll(leaves(row));
            legacySignatures.add(signature(leaves(row)));
        }
        List<String[]> newLeaves = new ArrayList<>();
        long exact = 0;
        for (Map<String, Object> row : fresh) {
            List<String[]> rl = leaves(json.valueToTree(row));
            newLeaves.addAll(rl);
            if (legacySignatures.remove(signature(rl))) {
                exact++;
            }
        }
        rowTally.compareRecord(legacyLeaves, newLeaves);
        return exact;
    }

    private static String signature(List<String[]> leaves) {
        return leaves.stream()
                .map(l -> l[0] + "=" + l[1])
                .sorted()
                .reduce("", (a, b) -> a + "|" + b);
    }

    private BufferedReader openRecords(GoldenCapture.CaseSpec spec, Path dataFile) throws IOException {
        Path records = corpusDir.resolve(spec.name())
                .resolve(dataFile.getFileName() + ".records.jsonl");
        return Files.exists(records)
                ? Files.newBufferedReader(records, StandardCharsets.UTF_8) : null;
    }

    // ---- leaf extraction ------------------------------------------------------------

    /** (lowercased leaf name, value-as-string) pairs of a legacy captured record. */
    private static List<String[]> leaves(JsonNode node) {
        List<String[]> out = new ArrayList<>();
        collectJson(node, null, out);
        return out;
    }

    private static void collectJson(JsonNode node, String name, List<String[]> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectJson(e.getValue(), e.getKey(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectJson(child, name, out));
        } else if (name != null) {
            out.add(new String[]{name.toLowerCase(Locale.ROOT), node.asText()});
        }
    }

    private static List<String[]> leaves(NamedNode node) {
        List<String[]> out = new ArrayList<>();
        collectNamed(node, out, true);
        return out;
    }

    private static void collectNamed(NamedNode node, List<String[]> out, boolean root) {
        if (node.leaf()) {
            if (!root) {
                out.add(new String[]{node.name().toLowerCase(Locale.ROOT), node.value()});
            }
            return;
        }
        for (NamedNode child : node.children()) {
            collectNamed(child, out, false);
        }
    }

    // ---- comparison -----------------------------------------------------------------

    /** Aggregates leaf-multiset comparison over the compared records of one file. */
    private static final class ContentTally {

        long recordsCompared;
        long legacyLeaves;
        long newLeaves;
        long valueMatches;
        long caseOnlyMatches;      // values equal ignoring case (e.g. hex case)
        long encodingOnlyMatches;  // legacy base64(bytes) == new hex(bytes)
        long valueMismatches;      // same leaf name, different value
        long legacyOnlyLeaves;     // leaf name (occurrence) present only in legacy
        long newOnlyLeaves;
        final Map<String, Long> mismatchByName = new TreeMap<>();
        final Map<String, Long> legacyOnlyByName = new TreeMap<>();
        final Map<String, Long> newOnlyByName = new TreeMap<>();
        final List<Map<String, String>> examples = new ArrayList<>();

        void compareRecord(List<String[]> legacy, List<String[]> fresh) {
            recordsCompared++;
            legacyLeaves += legacy.size();
            newLeaves += fresh.size();
            Map<String, List<String>> byNameLegacy = group(legacy);
            Map<String, List<String>> byNameNew = group(fresh);
            for (Map.Entry<String, List<String>> e : byNameLegacy.entrySet()) {
                List<String> l = e.getValue();
                List<String> n = byNameNew.getOrDefault(e.getKey(), List.of());
                compareName(e.getKey(), l, new ArrayList<>(n));
            }
            for (Map.Entry<String, List<String>> e : byNameNew.entrySet()) {
                if (!byNameLegacy.containsKey(e.getKey())) {
                    newOnlyLeaves += e.getValue().size();
                    newOnlyByName.merge(e.getKey(), (long) e.getValue().size(), Long::sum);
                }
            }
        }

        private void compareName(String name, List<String> legacy, List<String> fresh) {
            List<String> pendingLegacy = new ArrayList<>();
            for (String lv : legacy) {
                if (fresh.remove(lv)) {
                    valueMatches++;
                } else {
                    pendingLegacy.add(lv);
                }
            }
            for (String lv : new ArrayList<>(pendingLegacy)) {
                String ci = fresh.stream().filter(nv -> nv.equalsIgnoreCase(lv)).findFirst().orElse(null);
                if (ci != null) {
                    fresh.remove(ci);
                    pendingLegacy.remove(lv);
                    caseOnlyMatches++;
                }
            }
            for (String lv : new ArrayList<>(pendingLegacy)) {
                // legacy serializes raw byte[] as base64 where the new stack emits hex
                String hex = base64ToHex(lv);
                if (hex != null && fresh.remove(hex)) {
                    pendingLegacy.remove(lv);
                    encodingOnlyMatches++;
                }
            }
            while (!pendingLegacy.isEmpty() && !fresh.isEmpty()) {
                String lv = pendingLegacy.removeFirst();
                String nv = fresh.removeFirst();
                valueMismatches++;
                mismatchByName.merge(name, 1L, Long::sum);
                if (examples.size() < MAX_EXAMPLES) {
                    examples.add(Map.of("leaf", name, "legacy", lv, "new", nv));
                }
            }
            if (!pendingLegacy.isEmpty()) {
                legacyOnlyLeaves += pendingLegacy.size();
                legacyOnlyByName.merge(name, (long) pendingLegacy.size(), Long::sum);
            }
            if (!fresh.isEmpty()) {
                newOnlyLeaves += fresh.size();
                newOnlyByName.merge(name, (long) fresh.size(), Long::sum);
            }
        }

        /** Hex of the base64-decoded bytes, or null when the value is not base64. */
        private static String base64ToHex(String value) {
            if (value.isEmpty() || value.length() % 4 != 0
                    || !value.matches("[A-Za-z0-9+/]+={0,2}")) {
                return null;
            }
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(value);
                return java.util.HexFormat.of().withUpperCase().formatHex(bytes); // Decoders.hex is uppercase
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static Map<String, List<String>> group(List<String[]> leaves) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String[] leaf : leaves) {
                out.computeIfAbsent(leaf[0], k -> new ArrayList<>())
                        .add(leaf[1] == null ? "" : leaf[1]);
            }
            return out;
        }

        Map<String, Object> report() {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recordsCompared", recordsCompared);
            r.put("legacyLeaves", legacyLeaves);
            r.put("newLeaves", newLeaves);
            r.put("valueMatches", valueMatches);
            r.put("caseOnlyMatches", caseOnlyMatches);
            r.put("encodingOnlyMatches", encodingOnlyMatches);
            r.put("valueMismatches", valueMismatches);
            r.put("legacyOnlyLeaves", legacyOnlyLeaves);
            r.put("newOnlyLeaves", newOnlyLeaves);
            r.put("matchRatio", legacyLeaves == 0 ? null
                    : Math.round(10000.0 * (valueMatches + caseOnlyMatches + encodingOnlyMatches)
                            / legacyLeaves) / 10000.0);
            r.put("mismatchByName", top(mismatchByName));
            r.put("legacyOnlyByName", top(legacyOnlyByName));
            r.put("newOnlyByName", top(newOnlyByName));
            r.put("examples", examples);
            return r;
        }

        private static Map<String, Long> top(Map<String, Long> byName) {
            Map<String, Long> out = new LinkedHashMap<>();
            byName.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(MAX_NAME_ROWS)
                    .forEach(e -> out.put(e.getKey(), e.getValue()));
            return out;
        }
    }

    // ---- reporting ------------------------------------------------------------------

    private static String summaryLine(Map<String, Object> fr) {
        if (fr.containsKey("error")) {
            return "ERROR " + fr.get("error");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) fr.get("content");
        return "structural=" + fr.get("structuralParity")
                + " new=" + fr.get("newNonEmptyRecords") + "/" + fr.get("legacyRecords")
                + " match=" + content.get("matchRatio");
    }

    private void writeSummary(List<Map<String, Object>> all) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Parity report — new stack (asn-core/asn-schema) vs golden corpus\n\n");
        md.append("Generated by `ParityCheck` (asn-golden). Regenerate: build the reactor, then\n");
        md.append("`java -cp <asn-golden classpath> com.gamma.asn.golden.ParityCheck <repo asn-parser dir>`.\n\n");
        md.append("| Case | File | Structural | New records | Legacy records (+empty) | Compared | Leaf match |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (Map<String, Object> parity : all) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) parity.get("files");
            if (files == null || files.isEmpty()) {
                md.append("| ").append(parity.get("case")).append(" | — | — | — | — | — | ")
                        .append(parity.get("error")).append(" |\n");
                continue;
            }
            for (Map<String, Object> fr : files) {
                String file = String.valueOf(fr.get("file"));
                file = file.substring(file.lastIndexOf('/') + 1);
                if (fr.containsKey("error")) {
                    md.append("| ").append(parity.get("case")).append(" | ").append(file)
                            .append(" | ERROR | — | — | — | ").append(fr.get("error")).append(" |\n");
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) fr.get("content");
                md.append("| ").append(parity.get("case"))
                        .append(" | ").append(file)
                        .append(" | ").append(Boolean.TRUE.equals(fr.get("structuralParity")) ? "✅" : "❌")
                        .append(" | ").append(fr.get("newNonEmptyRecords"))
                        .append(fr.get("newRecordsFailed").equals(0L) ? "" : " (+failed " + fr.get("newRecordsFailed") + ")")
                        .append(" | ").append(fr.get("legacyRecords")).append(" (+").append(fr.get("legacyEmptyRecords")).append(")")
                        .append(" | ").append(content.get("recordsCompared"))
                        .append(" | ").append(content.get("matchRatio"))
                        .append(" |\n");
            }
        }
        md.append("\nDetails per case: `corpus/<case>/parity.json` (mismatch/missing leaf names + examples).\n");
        md.append("Leaf match compares (leaf name, decoded value) multisets per record — structural\n");
        md.append("naming differences (element-type layers, array indexing) are out of scope by design.\n");
        Files.writeString(corpusDir.resolve("PARITY.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
