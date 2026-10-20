package com.gamma.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slices S2 and S3 golden test. The shipped {@code partner-settlement} Space Template is created
 * through {@code POST /spaces}, the synthetic {@link PartnerSettlementCorpus} is ingested through the template's own Pipelines, the
 * template's six chained {@code sql.template} Jobs and two {@code recon.run} Jobs run as authored, and what they write is held to
 * <ul>
 *   <li>the findings planted by construction in the corpus, exactly, with every benign look-alike silent;</li>
 *   <li>an INDEPENDENT plain-Java oracle for every expected and billed amount, the snap to tolerance, the classification of each
 *       variance and the Reconciliation Breaks;</li>
 *   <li>mutation checks: each mutation of the shipped SQL or data must turn a named, planted or look-alike key red.</li>
 * </ul>
 */
class PartnerSettlementPackGoldenTest {

    private static final Map<String, String> AS_OF = Map.of("as_of", PartnerSettlementCorpus.AS_OF.toString());

    @BeforeEach
    void arm() { PartnerSettlementSpace.arm(); }

    @AfterEach
    void disarm() { PartnerSettlementSpace.disarm(); }

    // ------------------------------------------------------------------------------------------- the shipped template

    @Test
    void theTemplateShipsTheChainTheDatasetsAndTheRunbookAndItsFilesReachTheSpace(@TempDir Path root) throws Exception {
        PartnerSettlementSpace s = PartnerSettlementSpace.create(root);
        List<String> sqlJobs = new ArrayList<>();
        try (var files = Files.list(s.config().resolve("jobs"))) {
            for (Path p : files.sorted().toList()) {
                JobConfig j = JobConfig.load(p.toString());
                if ("sql.template".equals(j.type())) sqlJobs.add(j.name());
                else assertEquals("recon.run", j.type());
            }
        }
        assertEquals(new TreeSet<>(PartnerSettlementSpace.CHAIN), new TreeSet<>(sqlJobs), "six chained sql.template Jobs, no more");
        // the chain: each Job after the first runs on the previous Job's success
        for (int i = 1; i < PartnerSettlementSpace.CHAIN.size(); i++) {
            JobConfig j = JobConfig.load(s.config().resolve("jobs").resolve(PartnerSettlementSpace.CHAIN.get(i) + "_job.toon").toString());
            assertEquals("job.run.completed", j.onSignal(), j.name());
            assertEquals("$signal.job == " + PartnerSettlementSpace.CHAIN.get(i - 1) + " && $signal.outcome == SUCCESS", j.when(), j.name());
        }
        assertEquals("0 1 * * *", JobConfig.load(s.config().resolve("jobs").resolve("ps_calendar_job.toon").toString()).cron());
        try (var files = Files.list(s.config().resolve("registry").resolve("datasets"))) {
            assertEquals(12, files.count(), "six feeds and six Job-written Datasets");
        }
        assertTrue(Files.isRegularFile(s.config().resolve("runbooks").resolve("partner-settlement-runbooks.md")), "the runbook ships");
        assertTrue(Files.isRegularFile(s.config().resolve("registry").resolve("alert-rules").resolve("ps_findings_found.toon")));
        for (String r : List.of("ps_voice", "ps_sms"))
            assertTrue(Files.isRegularFile(s.config().resolve("registry").resolve("reconciliations").resolve(r + ".toon")), r);
        // tolerance, FX and cut-off live in the agreement Reference, never in the SQL: no tolerance literal and no currency in any Job
        for (String job : PartnerSettlementSpace.CHAIN) {
            String sql = JobConfig.load(s.config().resolve("jobs").resolve(job + "_job.toon").toString()).params().get("sql");
            assertFalse(sql.matches("(?s).*'(USD|EUR)'.*"), job + " hard-codes a currency");
            assertFalse(sql.matches("(?s).*tolerance\\s*[<>]=?\\s*[0-9].*"), job + " hard-codes a tolerance");
        }
    }

    // ------------------------------------------------------------------------------------------- S2 and S3, the golden

    @Test
    void theChainFlagsExactlyThePlantedFindingsAndEveryLookAlikeStaysSilent(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of());

        // ---- S2: statement integrity
        Map<String, Set<String>> stmt = new TreeMap<>();
        for (Map<String, Object> r : s.read("settlement_statement_findings", null, "reason, item_key"))
            stmt.computeIfAbsent((String) r.get("reason"), k -> new TreeSet<>()).add((String) r.get("item_key"));
        assertEquals(corpus.plantedStatementFindings, stmt, "exactly the planted statement findings, no look-alike");
        assertEquals(1, s.read("settlement_statement_findings", "reason = 'duplicate_statement'", "item_key").size(),
                "a re-sent statement raises ONE finding");
        // a control-total mismatch is data quality, not a variance
        assertEquals(Set.of("data_quality"), new TreeSet<>(column(s.read("settlement_statement_findings", "reason = 'control_total_mismatch'", "item_key"), "finding")));
        assertEquals(new BigDecimal("-1.0000"), s.read("settlement_statement_findings", "reason = 'control_total_mismatch'", "item_key").get(0).get("amount_delta"),
                "the v1 July statement's lines sum to its control total minus the planted 1.0000");
        // the re-sent statement counts once: 62 usage lines (31 days x 2 classes) + the fee line, not 126
        long may = s.read("settlement_statement_current", "statement_id = 'STM-A1-2026-05'", "line_no").size();
        assertEquals(63, may, "the re-sent May statement is counted once");
        // the restated July statement: only v2 is current, v1 stays queryable in the feed
        assertEquals(Set.of(2), new TreeSet<>(intColumn(s.read("settlement_statement_current", "statement_id = 'STM-A1-2026-07'", "line_no"), "statement_version")));
        assertEquals(Set.of(1, 2), new TreeSet<>(intColumn(s.read("settlement_statement", "statement_id = 'STM-A1-2026-07'", "line_no"), "statement_version")),
                "the superseded version stays for evidence");

        // ---- S3: the settlement findings
        Map<String, Set<String>> found = new TreeMap<>();
        for (Map<String, Object> r : s.read("settlement_findings", null, "reason, item_key"))
            found.computeIfAbsent((String) r.get("reason"), k -> new TreeSet<>()).add((String) r.get("item_key"));
        assertEquals(corpus.plantedFindings, found, "exactly the planted settlement findings");
        Set<String> all = new TreeSet<>();
        found.values().forEach(all::addAll);
        for (String benign : corpus.benign) assertFalse(all.contains(benign), "a look-alike was flagged: " + benign);
        // classification and class: variances are 'variance', the rest data quality
        for (Map<String, Object> r : s.read("settlement_findings", null, "item_key, reason"))
            assertEquals(Set.of("amount_mismatch", "cutoff_difference", "fx_difference").contains((String) r.get("reason")) ? "variance" : "data_quality", r.get("finding"), r.toString());
        // the one agreement-level alert grain: A1, C1 and D1 have findings, E1 has none
        assertEquals(Set.of("AG-VOICE-A1", "AG-SMS-C1", "AG-VOICE-D1"), new TreeSet<>(column(s.read("settlement_findings", null, "agreement_id"), "agreement_id")));
        // the accepted look-alike pair at the boundary: a cut-off pair is two findings, a non-offsetting pair is two mismatches
        assertEquals(Set.of("cutoff_difference"), reasonsOf(s, "AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-14", "AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-15"));
        assertEquals(Set.of("amount_mismatch"), reasonsOf(s, "AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-20", "AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-21"));
        // FX: the partner's own rate is named, a coincident rate is clean, and a variance FX cannot explain stays a mismatch
        assertEquals(Set.of("fx_difference"), reasonsOf(s, "AG-SMS-C1|they_pay|SMS|NATIONAL|2026-06-18"));
        assertEquals(Set.of("amount_mismatch"), reasonsOf(s, "AG-SMS-C1|they_pay|SMS|NATIONAL|2026-06-22"));
        // missing-after-due is one finding per cycle, and not-yet-due is silent
        assertEquals(Set.of("AG-VOICE-D1|2026-07-01|2026-07-31"), new TreeSet<>(found.get("missing_statement")));
        assertTrue(s.read("settlement_expected", "agreement_id = 'AG-VOICE-E1' AND usage_date >= DATE '2026-07-01'", "usage_date").isEmpty(),
                "E1's July statement is not due until 08-26: neither expected nor missing");
        // the stale May re-rating run never reaches the compare
        assertEquals(Set.of("RR-A1-2026-05-2"), new TreeSet<>(column(s.read("settlement_expected", "agreement_id = 'AG-VOICE-A1' AND period_start = DATE '2026-05-01'", "usage_date"), "rating_run_id")));
        // the rate in force is named on the row, a mid-cycle change shows as two rate ids across the cycle
        assertEquals("A1-R1", s.read("settlement_expected", "agreement_id = 'AG-VOICE-A1' AND usage_date = DATE '2026-07-15' AND destination_class = 'FIXED'", "usage_date").get(0).get("rate_ids"));
        assertEquals("A1-R2", s.read("settlement_expected", "agreement_id = 'AG-VOICE-A1' AND usage_date = DATE '2026-07-16' AND destination_class = 'FIXED'", "usage_date").get(0).get("rate_ids"));
        // FX conversion names the rate and its date; the decoy spot rows are never used
        Map<String, Object> fxRow = s.read("settlement_expected", "agreement_id = 'AG-SMS-C1' AND usage_date = DATE '2026-06-18'", "usage_date").get(0);
        assertEquals(new BigDecimal("0.91000000"), fxRow.get("fx_rate"));
        assertEquals(LocalDate.of(2026, 6, 16), fxRow.get("fx_rate_date"));
        assertEquals("ok", fxRow.get("fx_status"));
    }

    @Test
    void everyExpectedAndBilledAmountEqualsAnIndependentJavaOracle(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of());
        Oracle o = new Oracle(corpus);

        Map<String, String> wantExp = new TreeMap<>(), gotExp = new TreeMap<>();
        for (Map.Entry<String, Oracle.Row> e : o.expected.entrySet()) wantExp.put(e.getKey(), e.getValue().amount.toPlainString() + "|" + e.getValue().tol.toPlainString());
        for (Map<String, Object> r : s.read("settlement_expected", null, "agreement_id, usage_date, destination_class"))
            gotExp.put(key(r), ((BigDecimal) r.get("amount")).toPlainString() + "|" + ((BigDecimal) r.get("tolerance_amount")).toPlainString());
        assertEquals(wantExp, gotExp, "expected side: amount in the settlement currency and the tolerance in force that day");
        assertTrue(gotExp.size() > 300, "the corpus is not trivially small: " + gotExp.size());

        Map<String, String> wantBil = new TreeMap<>(), gotBil = new TreeMap<>();
        for (Map.Entry<String, Oracle.Row> e : o.billed.entrySet()) wantBil.put(e.getKey(), e.getValue().amount.toPlainString() + "|" + e.getValue().billed.toPlainString());
        for (Map<String, Object> r : s.read("settlement_billed", null, "agreement_id, usage_date, destination_class"))
            gotBil.put(key(r), ((BigDecimal) r.get("amount")).toPlainString() + "|" + ((BigDecimal) r.get("amount_billed")).toPlainString());
        assertEquals(wantBil, gotBil, "billed side: the snapped compare amount and the true billed amount");

        // the oracle's own classification equals what the Job reported and what the corpus planted
        Map<String, Set<String>> want = o.classify(corpus);
        assertEquals(corpus.plantedFindings.get("amount_mismatch"), want.get("amount_mismatch"));
        assertEquals(corpus.plantedFindings.get("cutoff_difference"), want.get("cutoff_difference"));
        assertEquals(corpus.plantedFindings.get("fx_difference"), want.get("fx_difference"));
        assertEquals(corpus.plantedFindings.get("missing_statement"), want.get("missing_statement"));
        assertEquals(corpus.plantedFindings.get("unrated_usage"), want.get("unrated_usage"));
    }

    @Test
    void theReconciliationsBreakOnExactlyTheKeysBeyondToleranceOrOneSided(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of());
        Oracle o = new Oracle(corpus);
        Map<String, Set<String>> want = new TreeMap<>();
        want.put("ps_voice", new TreeSet<>());
        want.put("ps_sms", new TreeSet<>());
        for (String k : o.breakKeys()) want.get(k.contains("|VOICE|") ? "ps_voice" : "ps_sms").add(k);
        for (String recon : List.of("ps_voice", "ps_sms")) {
            int reported = 0;
            for (LocalDate d = PartnerSettlementCorpus.FIRST; !d.isAfter(PartnerSettlementCorpus.LAST); d = d.plusDays(1)) reported += s.reconDay(recon, d.toString());
            Set<String> got = new TreeSet<>();
            for (String b : s.breaks(recon)) {
                String[] p = b.split("\\|", 3);   // day|type|agreement|direction|service|destination_class (the key is the key columns joined)
                got.add(p[2].replace(" · ", "|") + "|" + p[0]);   // the Break key joins the key columns with a middle dot
            }
            assertEquals(want.get(recon).size(), reported, recon + ": the runs report every Break");
            assertEquals(want.get(recon), got, recon + ": exactly the Breaks the oracle derives");
        }
        // an at-tolerance day and a day inside the new tolerance are silent in the Reconciliation too
        assertFalse(want.get("ps_voice").contains("AG-VOICE-A1|we_pay|VOICE|FIXED|2026-05-12"));
        assertTrue(want.get("ps_voice").contains("AG-VOICE-A1|we_pay|VOICE|FIXED|2026-05-20"));
    }

    @Test
    void aSecondRunOverTheSameWindowDuplicatesNothing(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of());
        Map<String, Integer> before = new TreeMap<>();
        for (String d : List.of("settlement_calendar", "settlement_statement_current", "settlement_statement_findings", "settlement_expected", "settlement_billed", "settlement_findings"))
            before.put(d, s.read(d, null, "1").size());
        s.runChain(AS_OF);
        for (Map.Entry<String, Integer> e : before.entrySet())
            assertEquals(e.getValue(), s.read(e.getKey(), null, "1").size(), e.getKey() + " changed on a re-run");
        assertTrue(before.get("settlement_findings") > 0);
    }

    // ------------------------------------------------------------------------------------------- mutation checks

    /**
     * Every mutation below changes ONE thing in the shipped SQL or data and must turn a named planted or look-alike key. A mutation that
     * leaves the findings as they were would prove the golden above is blind to that rule.
     */
    @Test
    void tighteningTheToleranceByOneStepTurnsTheAtThresholdLookAlikeRed(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        // D1's absolute tolerance 0.5000 minus one step: the day billed exactly +0.5000 is now one step beyond it
        Map<String, String> files = new LinkedHashMap<>(corpus.files());
        String ag = files.get("settlement_agreement/settlement_agreement.csv");
        String mutated = ag.replace("AG-VOICE-D1,P2,interconnect_voice,we_pay,USD,started_minute,abs,0.5000", "AG-VOICE-D1,P2,interconnect_voice,we_pay,USD,started_minute,abs,0.4999");
        assertNotEquals(ag, mutated, "the mutation must hit the corpus");
        files.put("settlement_agreement/settlement_agreement.csv", mutated);
        PartnerSettlementSpace s = chain(root, files, Map.of());
        Set<String> got = findingKeys(s);
        assertTrue(got.contains("amount_mismatch|AG-VOICE-D1|we_pay|VOICE|NATIONAL|2026-06-03"), "the at-threshold look-alike is red after the mutation");
        assertTrue(got.contains("amount_mismatch|AG-VOICE-D1|we_pay|VOICE|NATIONAL|2026-06-04"));
    }

    @Test
    void makingTheSnapComparatorStrictTurnsTheAtThresholdLookAlikeIntoABreak(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of("ps_billed", new String[]{"<= e.tolerance_amount", "< e.tolerance_amount"}));
        for (LocalDate d = LocalDate.of(2026, 6, 1); d.isBefore(LocalDate.of(2026, 6, 8)); d = d.plusDays(1)) s.reconDay("ps_voice", d.toString());
        Set<String> got = s.breaks("ps_voice");
        assertTrue(got.stream().anyMatch(b -> b.startsWith("2026-06-03|") && b.contains("AG-VOICE-D1")), "the at-tolerance day breaks once the snap is strict: " + got);
    }

    @Test
    void makingTheFindingComparatorNonStrictFlagsTheAtThresholdLookAlikes(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of("ps_findings", new String[]{"WHERE abs(variance) > tol", "WHERE abs(variance) >= tol"}));
        Set<String> got = findingKeys(s);
        assertTrue(got.contains("amount_mismatch|AG-VOICE-A1|we_pay|VOICE|FIXED|2026-05-12"), "A1 at-tolerance look-alike");
        // the C1 day billed exactly +5.0000 also happens to be explained by the partner's own rate: it is named fx_difference, still flagged
        assertTrue(got.contains("fx_difference|AG-SMS-C1|they_pay|SMS|NATIONAL|2026-06-20"), "C1 at-tolerance look-alike");
        assertTrue(got.contains("amount_mismatch|AG-VOICE-D1|we_pay|VOICE|NATIONAL|2026-06-03"), "D1 at-tolerance look-alike");
    }

    @Test
    void countingAReSentLineTwiceDoublesTheMayStatementAndFlagsIt(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of("ps_statement_current", new String[]{"FROM d WHERE rn = 1", "FROM d WHERE rn >= 1"}));
        assertEquals(126, s.read("settlement_statement_current", "statement_id = 'STM-A1-2026-05'", "line_no").size(), "the mutation double counts");
        Set<String> got = findingKeys(s);
        assertTrue(got.contains("amount_mismatch|AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-05-11"), "a double count turns an ordinary May day into a mismatch");
    }

    @Test
    void takingTheOlderRatingRunOrStatementVersionFlagsOrdinaryDays(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of("ps_expected", new String[]{"ORDER BY run_at DESC, rating_run_id DESC", "ORDER BY run_at ASC, rating_run_id ASC"}));
        assertTrue(findingKeys(s).contains("amount_mismatch|AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-05-11"), "the stale run makes an ordinary May day a mismatch");
        PartnerSettlementSpace s2 = chain(root.resolve("v"), corpus.files(), Map.of("ps_statement_current", new String[]{"WHERE statement_version = top_version", "WHERE statement_version >= 1"}));
        assertTrue(findingKeys(s2).contains("amount_mismatch|AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-07-20"), "the superseded July v1 line (999) reaches the compare");
    }

    @Test
    void ignoringTheCutOffNeighbourTurnsTheOffsettingPairIntoMismatches(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        PartnerSettlementSpace s = chain(root, corpus.files(), Map.of("ps_findings", new String[]{"AND abs(n.variance + j.variance) <= j.tol", "AND abs(n.variance + j.variance) > j.tol"}));
        Set<String> got = findingKeys(s);
        assertTrue(got.contains("amount_mismatch|AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-14"), "the offsetting pair is no longer a cut-off difference");
        assertTrue(got.contains("cutoff_difference|AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-20"), "and the non-offsetting pair now is");
    }

    @Test
    void withoutAnFxRateTheConvertedSideIsNamedNotSilentlyDropped(@TempDir Path root) throws Exception {
        PartnerSettlementCorpus corpus = PartnerSettlementCorpus.generate();
        Map<String, String> none = new LinkedHashMap<>(corpus.files());
        none.put("fx_rate/fx_rate.csv", "rate_date,from_currency,to_currency,rate_type,rate,rate_source\n");
        PartnerSettlementSpace t = chain(root, none, Map.of());
        Set<String> got = findingKeys(t);
        assertTrue(got.contains("fx_rate_missing|AG-SMS-C1|they_pay|SMS|NATIONAL|2026-06-02"), "a converted line with no rate is a named finding");
        assertTrue(t.read("settlement_expected", "agreement_id = 'AG-SMS-C1' AND amount IS NOT NULL", "usage_date").isEmpty(), "no amount is invented");
        assertTrue(got.stream().noneMatch(k -> k.startsWith("amount_mismatch|AG-SMS-C1") || k.startsWith("fx_difference|")), "a missing rate is never a partner variance");
        Set<String> voice = new TreeSet<>();
        for (String k : got) if (k.startsWith("amount_mismatch|AG-VOICE")) voice.add(k.substring("amount_mismatch|".length()));
        Set<String> plantedVoice = new TreeSet<>();
        for (String k : corpus.plantedFindings.get("amount_mismatch")) if (k.startsWith("AG-VOICE")) plantedVoice.add(k);
        assertEquals(plantedVoice, voice, "the same-currency agreements are untouched");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    /** Create the Space, optionally mutate one Job's SQL ({job: {from, to}}), ingest the files and run the chain. */
    private static PartnerSettlementSpace chain(Path root, Map<String, String> files, Map<String, String[]> edits) throws Exception {
        PartnerSettlementSpace s = PartnerSettlementSpace.create(root);
        for (Map.Entry<String, String[]> e : edits.entrySet()) {
            Path f = s.config().resolve("jobs").resolve(e.getKey() + "_job.toon");
            String text = Files.readString(f);
            assertTrue(text.contains(e.getValue()[0]), "the mutation target is not in " + e.getKey() + ": " + e.getValue()[0]);
            Files.writeString(f, text.replace(e.getValue()[0], e.getValue()[1]));
        }
        s.ingest(files);
        s.runChain(AS_OF);
        return s;
    }

    private static Set<String> findingKeys(PartnerSettlementSpace s) throws Exception {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> r : s.read("settlement_findings", null, "reason, item_key")) out.add(r.get("reason") + "|" + r.get("item_key"));
        return out;
    }

    private static Set<String> reasonsOf(PartnerSettlementSpace s, String... itemKeys) throws Exception {
        Set<String> out = new TreeSet<>();
        for (String k : itemKeys) {
            List<Map<String, Object>> rows = s.read("settlement_findings", "item_key = '" + k + "'", "reason");
            assertEquals(1, rows.size(), k);
            out.add((String) rows.get(0).get("reason"));
        }
        return out;
    }

    private static String key(Map<String, Object> r) {
        return r.get("agreement_id") + "|" + r.get("direction") + "|" + r.get("service") + "|" + r.get("destination_class") + "|" + r.get("usage_date");
    }

    private static Set<String> column(List<Map<String, Object>> rows, String col) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> r : rows) out.add(String.valueOf(r.get(col)));
        return out;
    }

    private static Set<Integer> intColumn(List<Map<String, Object>> rows, String col) {
        Set<Integer> out = new TreeSet<>();
        for (Map<String, Object> r : rows) out.add(((Number) r.get(col)).intValue());
        return out;
    }

    // ------------------------------------------------------------------------------------------- the independent oracle

    /** The expected and billed sides, the snap, and the classification, derived in plain Java from the corpus structures. */
    static final class Oracle {
        record Row(BigDecimal amount, BigDecimal orig, BigDecimal tol, BigDecimal billed, String currencyOrig, BigDecimal partnerFx, String period) {}

        final PartnerSettlementCorpus c;
        final Map<String, String> calendar;
        final Map<String, Row> expected = new TreeMap<>();
        final Map<String, Row> billed = new TreeMap<>();
        final Set<String> currentPeriods = new TreeSet<>();
        final Set<String> unratedKeys = new TreeSet<>();

        Oracle(PartnerSettlementCorpus c) {
            this.c = c;
            this.calendar = PartnerSettlementReferenceTest.oracle(c);
            buildStatements();
            buildExpected();
            buildBilled();
        }

        String period(String agr, LocalDate d) {
            for (String k : calendar.keySet()) {
                String[] p = k.split("\\|");
                if (p[0].equals(agr) && !d.isBefore(LocalDate.parse(p[1])) && !d.isAfter(LocalDate.parse(p[2]))) return p[1] + "|" + p[2];
            }
            return null;
        }

        PartnerSettlementCorpus.Agreement terms(String agr, LocalDate d) {
            for (PartnerSettlementCorpus.Agreement a : c.agreements) if (a.id().equals(agr) && a.covers(d)) return a;
            return null;
        }

        BigDecimal convert(BigDecimal amount, String from, PartnerSettlementCorpus.Agreement a, LocalDate day, String period) {
            if (from.equals(a.currency())) return amount.setScale(4, RoundingMode.HALF_UP);
            LocalDate fxDate = a.fxRule().equals("period_end") ? LocalDate.parse(period.split("\\|")[1]) : day;
            BigDecimal rate = c.contractRate(fxDate);
            return rate == null ? null : amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);
        }

        final List<PartnerSettlementCorpus.Line> current = new ArrayList<>();

        private void buildStatements() {
            Map<String, PartnerSettlementCorpus.Line> dedup = new LinkedHashMap<>();
            for (List<PartnerSettlementCorpus.Line> lines : c.statements.values())
                for (PartnerSettlementCorpus.Line l : lines) dedup.putIfAbsent(l.stmt() + "|" + l.version() + "|" + l.lineNo(), l);
            Map<String, Integer> top = new HashMap<>();
            for (PartnerSettlementCorpus.Line l : dedup.values()) top.merge(l.agreement() + "|" + l.pStart() + "|" + l.pEnd(), l.version(), Math::max);
            for (PartnerSettlementCorpus.Line l : dedup.values())
                if (l.version() == top.get(l.agreement() + "|" + l.pStart() + "|" + l.pEnd())) {
                    current.add(l);
                    currentPeriods.add(l.agreement() + "|" + l.pStart() + "|" + l.pEnd());
                }
        }

        private void buildExpected() {
            // the run per (agreement, period): latest rated_at, ties to the greater run id
            Map<String, String[]> pick = new HashMap<>();   // "agr|period" -> {runAt, runId}
            for (PartnerSettlementCorpus.Rerated r : c.rerated) {
                String period = period(r.agreement(), r.day());
                if (period == null) continue;
                String k = r.agreement() + "|" + period;
                String at = r.ratedAt().toString();
                String[] best = pick.get(k);
                if (best == null || at.compareTo(best[0]) > 0 || (at.equals(best[0]) && r.runId().compareTo(best[1]) > 0)) pick.put(k, new String[]{at, r.runId()});
            }
            Map<String, BigDecimal> sum = new TreeMap<>();
            Map<String, PartnerSettlementCorpus.Rerated> any = new HashMap<>();
            for (PartnerSettlementCorpus.Rerated r : c.rerated) {
                String key = r.agreement() + "|" + r.direction() + "|" + r.service() + "|" + r.dest() + "|" + r.day();
                if (r.reason() != null) unratedKeys.add(key);
                String period = period(r.agreement(), r.day());
                if (period == null || !pick.get(r.agreement() + "|" + period)[1].equals(r.runId()) || r.amount() == null || r.reason() != null) continue;
                String[] pe = period.split("\\|");
                LocalDate due = LocalDate.parse(calendar.get(r.agreement() + "|" + period).split("\\|")[0]);
                if (!(due.isBefore(PartnerSettlementCorpus.AS_OF) || currentPeriods.contains(r.agreement() + "|" + pe[0] + "|" + pe[1]))) continue;
                sum.merge(key, r.amount(), BigDecimal::add);
                any.put(key, r);
            }
            for (Map.Entry<String, BigDecimal> e : sum.entrySet()) {
                PartnerSettlementCorpus.Rerated r = any.get(e.getKey());
                PartnerSettlementCorpus.Agreement a = terms(r.agreement(), r.day());
                String period = period(r.agreement(), r.day());
                BigDecimal conv = convert(e.getValue(), r.currency(), a, r.day(), period);
                if (conv == null) continue;
                BigDecimal rate = r.currency().equals(a.currency()) ? BigDecimal.ONE : c.contractRate(a.fxRule().equals("period_end") ? LocalDate.parse(period.split("\\|")[1]) : r.day());
                BigDecimal tol = a.tolType().equals("pct") ? e.getValue().multiply(rate).abs().multiply(a.tolerance()).divide(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP) : a.tolerance();
                expected.put(e.getKey(), new Row(conv, e.getValue(), tol, null, r.currency(), null, period));
            }
        }

        private void buildBilled() {
            Map<String, BigDecimal> sum = new TreeMap<>();
            Map<String, PartnerSettlementCorpus.Line> any = new HashMap<>();
            for (PartnerSettlementCorpus.Line l : current) {
                if (!l.kind().equals("usage") || l.day() == null) continue;
                String period = period(l.agreement(), l.day());
                if (period == null) continue;
                String key = l.agreement() + "|" + l.direction() + "|" + l.service() + "|" + l.dest() + "|" + l.day();
                sum.merge(key, l.amount(), BigDecimal::add);
                any.put(key, l);
            }
            for (Map.Entry<String, BigDecimal> e : sum.entrySet()) {
                PartnerSettlementCorpus.Line l = any.get(e.getKey());
                PartnerSettlementCorpus.Agreement a = terms(l.agreement(), l.day());
                if (a == null) continue;
                String period = period(l.agreement(), l.day());
                BigDecimal real = convert(e.getValue(), l.currency(), a, l.day(), period);
                if (real == null) continue;
                Row exp = expected.get(e.getKey());
                BigDecimal snapped = exp != null && real.subtract(exp.amount).abs().compareTo(exp.tol) <= 0 ? exp.amount : real;
                billed.put(e.getKey(), new Row(snapped, e.getValue(), null, real, l.currency(), l.fxApplied(), period));
            }
        }

        /** The keys a Reconciliation must Break on: both sides present and unequal after the snap, or one side only. */
        Set<String> breakKeys() {
            Set<String> out = new TreeSet<>();
            Set<String> keys = new TreeSet<>(expected.keySet());
            keys.addAll(billed.keySet());
            for (String k : keys) {
                Row e = expected.get(k), b = billed.get(k);
                if (e == null || b == null || e.amount.compareTo(b.amount) != 0) out.add(k);
            }
            return out;
        }

        /** The finding reasons per item key, derived from scratch: no SQL, no corpus constants. */
        Map<String, Set<String>> classify(PartnerSettlementCorpus corpus) {
            Map<String, Set<String>> out = new TreeMap<>();
            Set<String> keys = new TreeSet<>(expected.keySet());
            keys.addAll(billed.keySet());
            Map<String, BigDecimal> variance = new HashMap<>(), tol = new HashMap<>();
            for (String k : keys) {
                Row e = expected.get(k), b = billed.get(k);
                BigDecimal v = (b == null ? BigDecimal.ZERO : b.billed).subtract(e == null ? BigDecimal.ZERO : e.amount);
                variance.put(k, v);
                tol.put(k, e == null ? BigDecimal.ZERO.setScale(4) : e.tol);
            }
            for (String k : keys) {
                if (unratedKeys.contains(k)) continue;
                Row e = expected.get(k), b = billed.get(k);
                BigDecimal v = variance.get(k);
                if (v.abs().compareTo(tol.get(k)) <= 0) continue;
                String[] p = k.split("\\|");
                LocalDate day = LocalDate.parse(p[4]);
                String period = e != null ? e.period : b.period;
                if (e != null && b == null && !currentPeriods.contains(p[0] + "|" + period)) continue;   // a missing statement is reported once per cycle
                boolean cutoff = false;
                for (int dd : new int[]{-1, 1}) {
                    String nk = p[0] + "|" + p[1] + "|" + p[2] + "|" + p[3] + "|" + day.plusDays(dd);
                    if (!variance.containsKey(nk) || unratedKeys.contains(nk)) continue;
                    BigDecimal nv = variance.get(nk);
                    if (nv.abs().compareTo(tol.get(nk)) > 0 && nv.signum() == -v.signum() && nv.add(v).abs().compareTo(tol.get(k)) <= 0) cutoff = true;
                }
                boolean fx = e != null && b != null && b.partnerFx != null && b.currencyOrig.equals(terms(p[0], day).currency()) && !e.currencyOrig.equals(b.currencyOrig)
                        && b.billed.subtract(e.orig.multiply(b.partnerFx).setScale(4, RoundingMode.HALF_UP)).abs().compareTo(tol.get(k)) <= 0;
                String reason = cutoff ? "cutoff_difference" : fx ? "fx_difference" : "amount_mismatch";
                out.computeIfAbsent(reason, r -> new TreeSet<>()).add(k);
            }
            for (String k : expected.keySet()) {
                Row e = expected.get(k);
                String a = k.split("\\|")[0];
                if (!currentPeriods.contains(a + "|" + e.period)) out.computeIfAbsent("missing_statement", r -> new TreeSet<>()).add(a + "|" + e.period);
            }
            for (String k : unratedKeys) out.computeIfAbsent("unrated_usage", r -> new TreeSet<>()).add(k);
            return out;
        }
    }
}
