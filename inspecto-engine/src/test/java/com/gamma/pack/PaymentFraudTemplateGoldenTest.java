package com.gamma.pack;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.risk.EvidenceMasker;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PACK-PAYMENT-FRAUD-1 slice 1: the {@code payment-fraud} Space Template, RUN end to end from a verbatim
 * copy — the three Pipelines ingest the committed synthetic corpus, the four {@code sql.template} feature Jobs
 * build their Datasets, the {@code payment_account} Risk Score is evaluated as the {@code risk.score} Job does,
 * and every shipped Alert Rule is swept by the production {@link AlertService} + {@link DatasetMeasureProbe}.
 * The golden assertion is EXACT per typology: the planted offenders and nothing else — every planted look-alike
 * stays silent.
 */
class PaymentFraudTemplateGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "spaces", "_templates", "payment-fraud").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("payment_attempts", "sim_changes", "disputes");
    private static final List<String> FEATURE_JOBS = List.of("pf_device_small_amounts", "pf_bin_declines",
            "pf_instrument_velocity", "pf_sim_swap_payments", "pf_account_activity");
    /** A well-known PUBLISHED test card number (Luhn-valid, 16 digits) — never a real instrument. */
    private static final String TEST_PAN = "4111111111111111";

    @Test
    void theCommittedCorpusIsExactlyTheSeededGeneratorsOutput() throws Exception {
        Map<String, String> generated = PaymentFraudCorpus.files();
        Path samples = TEMPLATE.resolve("data/samples");
        Set<String> committed = new TreeSet<>();
        try (Stream<Path> w = Files.walk(samples)) {
            w.filter(Files::isRegularFile).forEach(p -> committed.add(samples.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(new TreeSet<>(generated.keySet()), committed, "the sample files are the generator's, no more");
        for (Map.Entry<String, String> e : generated.entrySet())
            assertEquals(e.getValue(), Files.readString(samples.resolve(e.getKey())).replace("\r\n", "\n"),
                    e.getKey() + " drifted from PaymentFraudCorpus — regenerate it with PaymentFraudCorpus.main");
        assertEquals(generated, PaymentFraudCorpus.files(), "the fixed seed makes the corpus deterministic");
    }

    @Test
    void theGoldenCorpusRaisesExactlyThePlantedCasesPerTypology(@TempDir Path tmp) throws Exception {
        Path space = copyTemplate(tmp);
        Path cfg = space.resolve("config");
        Path data = space.resolve("data");
        for (String feed : FEEDS) {
            PipelineConfig pc = ingest(space, feed, true);
            assertEquals(0, count(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
            assertEquals(0, count(restricted(pc)), feed + ": no realistic reference (order id, epoch ms, IMEI, phone) trips");
        }
        // The tripwire look-alikes (a Luhn-INVALID 16-digit, a 12-digit and a 20-digit value) all landed.
        assertEquals(3L, scalar(data, "payment_attempts/database",
                "SELECT count(*) FROM \"s\" WHERE ACCOUNT_ID = 'acc_la_pan'"));

        Map<String, String> seeded = new TreeMap<>();
        for (String j : FEATURE_JOBS) seeded.put(j, schema(data, j));
        runFeatureJobs(space);
        for (String j : FEATURE_JOBS) {
            assertFalse(Files.exists(data.resolve(j).resolve("schema-seed.parquet")), j + ": the first run replaced the seed");
            assertEquals(seeded.get(j), schema(data, j), j + ": the shipped zero-row seed declares exactly the Job's schema");
        }

        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> modelContent = store.get("risk-score", "payment_account").orElseThrow().content();
        RiskScoreModel model = RiskScoreModel.fromMap("payment_account", modelContent);
        var run = RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null),
                EvidenceMasker.of(store, cfg, model));
        RiskScoreEvaluator.write(data, model, RiskScoreEvaluator.version(modelContent), "golden", Instant.now(), run.scored());

        // The Risk Score's Alert Rule cannot ship in the template (its Dataset is the Job's own output, which does not
        // exist when the seed gate judges it) — it is added the documented way, after the first scoring run.
        store.write("dataset", "risk_scores_payment_account_latest", Map.of("physicalRef", "risk_scores_payment_account_latest"));
        store.write("alert-rule", "pf_high_risk_account", Map.of("name", "pf_high_risk_account",
                "dataset", "risk_scores_payment_account_latest", "measure", "max(score)",
                "by", List.of("model", "entity_key"), "comparator", "gte", "threshold", model.highThreshold(),
                "severity", "CRITICAL", "description", "High payment Risk Score"));
        Map<String, Set<String>> detections = sweep(store, cfg, data);
        assertEquals(Map.of(
                "pf_card_testing", Set.of("dev_ct_01", "dev_ct_02"),
                "pf_bin_attack", Set.of("498765", "498700"),
                "pf_velocity_burst", Set.of("tok_shared", "tok_vb_01", "tok_vb_02", "tok_vb_03"),
                "pf_sim_swap_takeover", Set.of("acc_ss01", "acc_ss02"),
                "pf_high_risk_account", Set.of("acc_ct_guest", "acc_ss01", "acc_ss02", "acc_vb01")), detections,
                "exactly the planted offenders per typology — every look-alike stays silent");

        // One instrument shared by two accounts: the velocity Alert Rule sees all 6 attempts on the INSTRUMENT, while
        // each account's velocity factor counts only its own 3 — the partition and the grouping agree on one key.
        Map<String, Double> shared = new TreeMap<>();
        run.scored().stream().filter(x -> x.entityKey().startsWith("acc_sh")).forEach(x -> shared.put(x.entityKey(), x.score()));
        assertEquals(Map.of("acc_sh1", 15.0, "acc_sh2", 15.0), shared, "3 own attempts x 5 each, not the instrument's 6");

        // The exact scores of the high accounts, recomputed by hand from the default factor table.
        Map<String, Double> scores = new TreeMap<>();
        run.scored().stream().filter(s -> s.score() >= model.highThreshold()).forEach(s -> scores.put(s.entityKey(), s.score()));
        assertEquals(Map.of("acc_ct_guest", 60.0, "acc_ss01", 65.0, "acc_ss02", 65.0, "acc_vb01", 87.0), scores,
                "ss: 60 (SIM swap) + 5 (velocity 1); vb01: 35 (velocity 7) + 12 (3 declines) + 40 (2 disputes); "
                        + "ct_guest: 40 (velocity 10, capped) + 20 (8 declines, capped)");
    }

    /** Trip probes: each hides the published test PAN in one cell, spelled as an adversary would. */
    private static final List<String[]> TRIPS = List.of(
            new String[]{"INSTRUMENT_TOKEN", TEST_PAN},
            new String[]{"INSTRUMENT_TOKEN", "4111 1111 1111 1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111-1111-1111-1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111.1111.1111.1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111/1111/1111/1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111_1111_1111_1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111\t1111\t1111\t1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111  1111  1111  1111"},                           // 2 separators
            new String[]{"INSTRUMENT_TOKEN", "4111 - 1111 - 1111 - 1111"},                        // 3 separators
            new String[]{"INSTRUMENT_TOKEN", "4111 1111 1111 1111"},              // NBSP
            new String[]{"INSTRUMENT_TOKEN", "4111–1111–1111–1111"},              // en-dash
            new String[]{"INSTRUMENT_TOKEN", "41​11111111111111"},                          // zero-width space
            new String[]{"INSTRUMENT_TOKEN", "4́" + "111111111111111"},                           // combining mark
            new String[]{"INSTRUMENT_TOKEN", fold(TEST_PAN, 0xFF10)},                            // full-width
            new String[]{"INSTRUMENT_TOKEN", fold(TEST_PAN, 0x0660)},                            // Arabic-Indic
            new String[]{"INSTRUMENT_TOKEN", fold(TEST_PAN, 0x0E50)},                            // Thai
            new String[]{"INSTRUMENT_TOKEN", fold(TEST_PAN, 0x09E6)},                            // Bengali
            new String[]{"INSTRUMENT_TOKEN", "+" + TEST_PAN},
            new String[]{"MERCHANT_ID", "card " + TEST_PAN + " exp"},
            new String[]{"MERCHANT_ID", "4111 1111 1111 1111 123"},                               // PAN + CVV
            new String[]{"MERCHANT_ID", "12/28 4111 1111 1111 1111"},                             // expiry + PAN
            new String[]{"MERCHANT_ID", "card " + TEST_PAN + " 12/27"},
            new String[]{"MERCHANT_ID", "3782 822463 10005"},                                     // 4-6-5 (published Amex test)
            new String[]{"MERCHANT_ID", "3530111333300000"},                                      // JCB 35 (published test)
            new String[]{"MERCHANT_ID", "3566 0020 2036 0505"},                                   // JCB 35, grouped
            new String[]{"MERCHANT_ID", "3056 930902 5904"},                                      // Diners 30, 4-6-4
            new String[]{"MERCHANT_ID", "38520000023237"},                                        // Diners 38
            new String[]{"MERCHANT_ID", "36227206271667"},                                        // Diners 36
            new String[]{"MERCHANT_ID", "6759649826438453"},                                      // Maestro 67
            new String[]{"MERCHANT_ID", "5018000000000009"},                                      // Maestro 50
            new String[]{"MERCHANT_ID", "5610591081018250"},                                      // Maestro 56
            new String[]{"MERCHANT_ID", "DE00 4111 1111 1111 1111"},                              // a PAN dressed as an IBAN
            new String[]{"MERCHANT_ID", "ID12 4111111111111111"},                                 // ditto, ungrouped
            new String[]{"AMOUNT", TEST_PAN});                                                    // the numeric leak path
    /** True negatives: digit strings that are NOT card numbers — each must ingest. */
    private static final List<String[]> PASSES = List.of(
            new String[]{"INSTRUMENT_TOKEN", "tok_y"},
            new String[]{"MERCHANT_ID", "4111111111111112"},
            new String[]{"MERCHANT_ID", "4111 1111 1111 1112"},
            new String[]{"MERCHANT_ID", "411111111111"},
            new String[]{"MERCHANT_ID", "41111111111111111111"},
            new String[]{"MERCHANT_ID", "ORD-2026-482913-0071"},
            new String[]{"MERCHANT_ID", "1751328000123"},                                         // epoch ms
            new String[]{"MERCHANT_ID", "356938035643809"},                                       // a Luhn-valid IMEI (15 digits: no JCB length)
            new String[]{"MERCHANT_ID", "DE89 3704 0044 0532 0130 00"},                           // an IBAN in 4-digit groups
            new String[]{"MERCHANT_ID", "+49 1512 3456789"},                                      // E.164
            new String[]{"AMOUNT", "1234567.89"});

    private static String fold(String ascii, int zero) {
        StringBuilder sb = new StringBuilder();
        for (char c : ascii.toCharArray()) sb.appendCodePoint(zero + (c - '0'));
        return sb.toString();
    }

    private static final String HEADER =
            "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME";
    private static final String CLEAN_ROW = "pa_x1,2026-07-04 10:00:00,2026-07-04,acc_x,tok_x,402400,dev_x,m_01,10.00,EUR,APPROVED";

    private static String row(String column, String value) {
        Map<String, String> row = new java.util.LinkedHashMap<>();
        String[] names = HEADER.split(",");
        String[] vals = "pa_x2,2026-07-04 10:05:00,2026-07-04,acc_x,tok_x,402400,dev_x,m_01,10.00,EUR,APPROVED".split(",");
        for (int i = 0; i < names.length; i++) row.put(names[i], vals[i]);
        row.put(column, value);
        return String.join(",", row.values());
    }

    /** Copy the template, apply {@code edit} to the payment_attempts Pipeline TOON, write {@code files}, run one poll. */
    private static PipelineConfig run(Path dir, java.util.function.UnaryOperator<String> edit, Map<String, String> files)
            throws Exception {
        Path space = copyTemplate(dir);
        Path toon = space.resolve("config/payment_attempts/payment_attempts_pipeline.toon");
        Files.writeString(toon, edit.apply(Files.readString(toon)));
        PipelineConfig pc = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        for (Map.Entry<String, String> f : files.entrySet())
            Files.writeString(inbox.resolve(f.getKey()), f.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        CollectorProcessor.run(pc);
        assertTrue(Files.exists(Path.of(pc.dirs().statusFilePath())), "the poll picked the file(s) up");
        return pc;
    }

    private static PipelineConfig plant(Path dir, String column, String value) throws Exception {
        return run(dir, t -> t, Map.of("PAYMENT_ATTEMPTS_20260704.csv", HEADER + "\n" + CLEAN_ROW + "\n" + row(column, value) + "\n"));
    }

    private static Path restricted(PipelineConfig pc) {
        // <data root>/.restricted/<pipeline> — outside every sealed-ingest allowlist (round 5).
        return Path.of(pc.dirs().quarantine()).toAbsolutePath().normalize().getParent().getParent()
                .resolve(".restricted").resolve(pc.identity().pipelineName());
    }

    /** The refused file sits ONLY in the restricted quarantine, and nothing anywhere names the value. */
    private static void assertRestricted(PipelineConfig pc, String what, int files) throws Exception {
        assertEquals(files, count(restricted(pc)), what + ": the file is in the restricted quarantine");
        for (String kept : new String[]{pc.dirs().quarantine(), pc.dirs().errors(), pc.dirs().temp()})
            assertEquals(0, count(Path.of(kept)), what + ": no copy of the file is kept in " + kept);
        if (Files.isDirectory(Path.of(pc.dirs().backup())))   // a clean batch-mate is backed up; the refused file never
            try (Stream<Path> b = Files.walk(Path.of(pc.dirs().backup()))) {
                for (Path f : b.filter(Files::isRegularFile).toList())
                    assertFalse(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).contains("pa_x2")
                            || f.getFileName().toString().contains("4111"), what + ": the refused file is not in backup/");
            }
        try (Stream<Path> in = Files.list(Path.of(pc.dirs().poll()))) {
            assertEquals(0, in.filter(p -> p.getFileName().toString().startsWith("PAYMENT_ATTEMPTS_20260704")
                    || p.getFileName().toString().contains("4111")).count(), what + ": the refused file left the inbox");
        }
        String status = Files.readString(Path.of(pc.dirs().statusFilePath()));
        assertTrue(status.contains("QUARANTINED_RESTRICTED") && status.contains("INGEST_REFUSE:"), what + ": " + status);
        assertFalse(status.contains("1111") || status.contains("Invalid Input") || status.contains("4111"),
                what + ": the status holds the reason code alone: " + status);
    }

    @Test
    void digitStringsThatAreNotCardNumbersIngest(@TempDir Path tmp) throws Exception {
        int i = 0;
        for (String[] p : PASSES) {
            PipelineConfig pc = plant(tmp.resolve("p" + i++), p[0], p[1]);
            assertEquals(2L, scalar(Path.of(pc.dirs().database()).getParent().getParent(), "payment_attempts/database",
                    "SELECT count(*) FROM \"s\""), p[0] + "='" + p[1] + "' is not a card number: both rows land");
            assertEquals(0, count(restricted(pc)));
        }
    }

    @Test
    void aCardNumberInAnySpellingOrColumnIsRestrictedAndLandsNothing(@TempDir Path tmp) throws Exception {
        int i = 0;
        for (String[] p : TRIPS) {
            String what = p[0] + "='" + p[1] + "'";
            PipelineConfig pc = plant(tmp.resolve("t" + i++), p[0], p[1]);
            assertEquals(0, count(Path.of(pc.dirs().database())), what + ": not one row of the file landed");
            assertRestricted(pc, what, 1);
            assertTrue(Files.readString(Path.of(pc.dirs().statusFilePath())).contains("INGEST_REFUSE:CARD_NUMBER"), what);
        }
    }

    @Test
    void aCardNumberInTheFileNameOrHeaderIsRestricted(@TempDir Path tmp) throws Exception {
        PipelineConfig named = run(tmp.resolve("n"), t -> t,
                Map.of("PAYMENT_ATTEMPTS_" + TEST_PAN + ".csv", HEADER + "\n" + CLEAN_ROW + "\n"));
        assertRestricted(named, "file name", 1);
        assertTrue(Files.readString(Path.of(named.dirs().statusFilePath())).contains("CARD_NUMBER_IN_FILE_NAME"));
        PipelineConfig header = run(tmp.resolve("h"), t -> t,
                Map.of("PAYMENT_ATTEMPTS_20260704.csv", HEADER.replace("OUTCOME", "OUTCOME " + TEST_PAN) + "\n" + CLEAN_ROW + "\n"));
        assertRestricted(header, "header", 1);
        assertTrue(Files.readString(Path.of(header.dirs().statusFilePath())).contains("CARD_NUMBER_IN_HEADER"));
    }

    @Test
    void inAMultiMemberBatchOnlyTheRefusedFileIsRestricted(@TempDir Path tmp) throws Exception {
        PipelineConfig pc = run(tmp, t -> t.replace("collector:\n", "collector:\n  consignment:\n    max_files: 3\n"),
                Map.of("PAYMENT_ATTEMPTS_20260704.csv", HEADER + "\n" + CLEAN_ROW + "\n" + row("INSTRUMENT_TOKEN", TEST_PAN) + "\n",
                        "PAYMENT_ATTEMPTS_20260705.csv", HEADER + "\n" + CLEAN_ROW.replace("pa_x1", "pa_y1") + "\n"));
        assertRestricted(pc, "multi-member", 1);
        assertEquals(1L, scalar(Path.of(pc.dirs().database()).getParent().getParent(), "payment_attempts/database",
                "SELECT count(*) FROM \"s\" WHERE ATTEMPT_ID = 'pa_y1'"), "the clean batch-mate still lands");
        assertEquals(1L, scalar(Path.of(pc.dirs().database()).getParent().getParent(), "payment_attempts/database",
                "SELECT count(*) FROM \"s\""), "and nothing of the refused file");
    }

    @Test
    void aChunkedFileIsScannedWholeAndRestricted(@TempDir Path tmp) throws Exception {
        StringBuilder body = new StringBuilder(HEADER).append('\n');
        for (int k = 0; k < 40; k++) body.append(CLEAN_ROW.replace("pa_x1", "pa_c" + k)).append('\n');
        body.append(row("INSTRUMENT_TOKEN", TEST_PAN)).append('\n');
        PipelineConfig pc = run(tmp, t -> t.replace("  reject_mode: all_or_nothing\n",
                        "  chunking:\n    max_file_bytes: 1000\n    target_chunk_bytes: 1000\n"),
                Map.of("PAYMENT_ATTEMPTS_20260704.csv", body.toString()));
        assertTrue(pc.chunking().appliesTo(body.length()), "the premise: this file is chunked");
        assertEquals(0, count(Path.of(pc.dirs().database())), "nothing of the file landed");
        assertRestricted(pc, "chunked", 1);
    }

    /** An AUTHOR-raised refusal in a later chunk: the chunks written before it are rolled back. */
    @Test
    void anAuthorRefusalInALaterChunkRollsTheEarlierChunksBack(@TempDir Path tmp) throws Exception {
        StringBuilder body = new StringBuilder(HEADER).append('\n');
        for (int k = 0; k < 40; k++) body.append(CLEAN_ROW.replace("pa_x1", "pa_c" + k)).append('\n');
        body.append(row("MERCHANT_ID", "refuse_me")).append('\n');
        Path space = copyTemplate(tmp);
        Path schema = space.resolve("config/payment_attempts/payment_attempts_schema.toon");
        Files.writeString(schema, Files.readString(schema).replace("    - name: MERCHANT_ID\n      from: MERCHANT_ID\n      fn: keep",
                "    - name: MERCHANT_ID\n      from: \"\"\n      fn: custom\n      args:\n        expression: \"CASE WHEN "
                        + "MERCHANT_ID = 'refuse_me' THEN error('INGEST_REFUSE:TEST_REFUSAL') ELSE MERCHANT_ID END\""));
        Path toon = space.resolve("config/payment_attempts/payment_attempts_pipeline.toon");
        Files.writeString(toon, Files.readString(toon).replace("  reject_mode: all_or_nothing\n",
                "  chunking:\n    max_file_bytes: 1000\n    target_chunk_bytes: 1000\n"));
        PipelineConfig pc = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"), body.toString());
        CollectorProcessor.run(pc);
        assertEquals(0, count(Path.of(pc.dirs().database())), "the chunks written before the refusal are rolled back");
        assertEquals(1, count(restricted(pc)));
        assertTrue(Files.readString(Path.of(pc.dirs().statusFilePath())).contains("INGEST_REFUSE:TEST_REFUSAL"));
    }

    @Test
    void anExemptColumnIsNotScanned(@TempDir Path tmp) throws Exception {
        String file = HEADER + "\n" + CLEAN_ROW + "\n" + row("MERCHANT_ID", TEST_PAN) + "\n";
        PipelineConfig exempt = run(tmp.resolve("x"), t -> t.replace("  refusal_scan: card_number\n",
                "  refusal_scan: card_number\n  refusal_scan_exempt[1]: MERCHANT_ID\n"), Map.of("PAYMENT_ATTEMPTS_20260704.csv", file));
        assertEquals(List.of("MERCHANT_ID"), exempt.refusal().scanExempt(), "the premise: the key parsed");
        assertEquals(2L, scalar(Path.of(exempt.dirs().database()).getParent().getParent(), "payment_attempts/database",
                "SELECT count(*) FROM \"s\""), "an exempt column is the operator's call: the file lands");
        assertEquals(0, count(restricted(exempt)));
        PipelineConfig scanned = run(tmp.resolve("y"), t -> t, Map.of("PAYMENT_ATTEMPTS_20260704.csv", file));
        assertRestricted(scanned, "not exempt", 1);
    }

    @Test
    void aOneMegabyteDigitCellIsScannedQuickly(@TempDir Path tmp) throws Exception {
        String huge = "9".repeat(1_000_000);
        long t0 = System.nanoTime();
        PipelineConfig pc = plant(tmp, "MERCHANT_ID", huge);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(2L, scalar(Path.of(pc.dirs().database()).getParent().getParent(), "payment_attempts/database",
                "SELECT count(*) FROM \"s\""), "a 1 MB digit run is no card number");
        assertTrue(ms < 10_000, "the whole poll of a 1 MB digit cell took " + ms + " ms");
        // The scan itself, apart from the poll's fixed cost: the same file, clean, as the baseline.
        long b0 = System.nanoTime();
        plant(tmp.resolve("base"), "MERCHANT_ID", "m_01");
        long base = (System.nanoTime() - b0) / 1_000_000;
        assertTrue(ms - base < 2_000, "the 1 MB cell cost " + (ms - base) + " ms over a clean file");
    }

    /**
     * 🔴 KNOWN GAP, pinned as evidence for {@code INGEST-REJECT-SIDECAR-RAW-PAN-1}: a card number inside a MALFORMED
     * row (a field-count reject) never reaches the mapping, so the tripwire cannot see it — {@code all_or_nothing}
     * quarantines the raw file and writes a rejects sidecar holding the row verbatim. When the platform fixes it,
     * this test goes red: flip it to the restricted-quarantine assertions above.
     */
    @Test
    void knownGapACardNumberInAMalformedRowIsKeptInQuarantine(@TempDir Path tmp) throws Exception {
        PipelineConfig pc = plant(tmp, "OUTCOME", "APPROVED," + TEST_PAN);   // one extra field → a parse reject
        assertEquals(0, count(Path.of(pc.dirs().database())));
        StringBuilder kept = new StringBuilder();
        for (String dir : new String[]{pc.dirs().quarantine(), pc.dirs().errors()})
            if (Files.isDirectory(Path.of(dir)))
                try (Stream<Path> w = Files.walk(Path.of(dir))) {
                    for (Path f : w.filter(Files::isRegularFile).toList()) kept.append(Files.readString(f));
                }
        assertTrue(kept.toString().contains(TEST_PAN), "the gap is real: the raw value is kept at rest");
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private static Path copyTemplate(Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("payment-fraud");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return space;
    }

    private static PipelineConfig ingest(Path space, String feed, boolean samples) throws Exception {
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        if (samples)
            try (Stream<Path> f = Files.list(space.resolve("data/samples/" + feed))) {
                for (Path p : f.toList()) Files.copy(p, inbox.resolve(p.getFileName()));
            }
        CollectorProcessor.run(pc);
        return pc;
    }

    private static void runFeatureJobs(Path space) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : FEATURE_JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (String j : FEATURE_JOBS) {
                assertTrue(js.triggerRun(j, null).isPresent(), j);
                JobRun r = null;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((r = js.lastRunOf(j).orElse(null)) == null && System.nanoTime() < deadline) Thread.sleep(50);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
    }

    /** Sweep every shipped Alert Rule once; rule name → the offending key(s) its Incidents name. */
    private static Map<String, Set<String>> sweep(ComponentStore store, Path cfg, Path data) {
        List<AlertRule> rules = store.list("alert-rule").stream().map(c -> AlertRule.fromMap(c.content())).toList();
        assertEquals(5, rules.size(), "the template's four typology Alert Rules + the Risk Score's");
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore(), objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        svc.evaluateRules();
        Map<String, Set<String>> out = new TreeMap<>();
        for (FakeObjectAccess.Opened o : objects.opened) {
            if (o.kind() != ObjectType.INCIDENT) continue;
            String rule = String.valueOf(o.attributes().get("rule"));
            String key = o.attributes().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("key.") && !e.getKey().equals("key.model"))
                    .map(e -> String.valueOf(e.getValue())).findFirst().orElse("?");
            out.computeIfAbsent(rule, k -> new TreeSet<>()).add(key);
        }
        return out;
    }

    private static long scalar(Path data, String ref, String sql) throws Exception {
        var rows = QueryExecutor.run(new QueryExecutor.Request("s",
                DatasetRelation.relationSql(Map.of("physicalRef", ref), data, null), sql, 10, 0, List.of(), List.of())).rows();
        return ((Number) rows.get(0).values().iterator().next()).longValue();
    }

    /** The column names and DuckDB types of a sql.template sink, as {@code DESCRIBE} reports them. */
    private static String schema(Path data, String sink) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        String glob = data.resolve(sink).toString().replace('\\', '/') + "/*.parquet";
        StringBuilder sb = new StringBuilder();
        try (var c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); var st = c.createStatement();
             var rs = st.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + glob + "')")) {
            while (rs.next()) sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }

    private static long count(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> w = Files.walk(dir)) {
            return w.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).count();
        }
    }

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
    }
}
