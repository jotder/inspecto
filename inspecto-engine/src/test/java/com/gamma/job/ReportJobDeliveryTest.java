package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** BI-4 scheduled export delivery: the dataset scope renders a CSV artifact into out_dir. */
class ReportJobDeliveryTest {

    @AfterEach
    void clearRoot() {
        System.clearProperty("assist.write.root");
    }

    private static void seedSales(Path writeRoot) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('EU',30.0),('US',5.0)) AS t(region,amount)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
    }

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("weekly_sales", JobType.REPORT, null, null, true, false, params);
    }

    /**
     * {@code JOB-PATH-REPORT-ENRICH-SPLIT-1}: a RELATIVE {@code out_dir} resolves against the Space
     * config root, not the process working directory — the rule {@code JOB-DIR-CWD-CONTAINMENT-1} set
     * for every other job path and that this delivery site was left out of.
     *
     * <p>⚠ The assertion that matters is the NEGATIVE one: nothing may appear at the CWD-relative path.
     * Asserting only that the artifact landed under the write root would also pass if the old rule had
     * been kept and the test happened to run with the write root as its working directory.
     */
    @Test
    void aRelativeOutDirResolvesAgainstTheSpaceRootNotTheWorkingDirectory(@TempDir Path writeRoot)
            throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        Path cwdRelative = Path.of("reports-spacerule-probe").toAbsolutePath();
        assertFalse(Files.exists(cwdRelative), "fixture: the CWD-relative path must not pre-exist");

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds",
                "out_dir", "reports-spacerule-probe")), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        Path spaceRelative = writeRoot.resolve("reports-spacerule-probe");
        assertTrue(Files.isDirectory(spaceRelative), "delivered under the Space root: " + r.message());
        try (Stream<Path> files = Files.list(spaceRelative)) {
            assertTrue(files.findFirst().isPresent(), "the artifact itself must be there, not just the dir");
        }
        assertFalse(Files.exists(cwdRelative),
                "the old working-directory rule must be gone, not merely shadowed: " + cwdRelative);
    }

    @Test
    void datasetScopeDeliversAggregatedCsv(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds",
                "measures", "sum(amount),count", "group_by", "region",
                "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("delivered to"), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertTrue(artifact.getFileName().toString().matches("weekly_sales_\\d{8}_\\d{6}\\.csv"),
                artifact.toString());
        String csv = Files.readString(artifact);
        assertTrue(csv.startsWith("region,sum_amount,count"), csv);
        assertTrue(csv.contains("EU,40.0,2") && csv.contains("US,5.0,1"), csv);
    }

    @Test
    void rawExportWithoutMeasuresDeliversAllRows(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertEquals(4, Files.readAllLines(artifact).size(), "header + all 3 raw rows");
    }

    @Test
    void datasetScopePngDeliversReadableTableImage(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        System.setProperty("java.awt.headless", "true");   // BufferedImage rendering needs no display
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "format", "png",
                "measures", "sum(amount),count", "group_by", "region",
                "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("delivered to"), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertTrue(artifact.getFileName().toString().matches("weekly_sales_\\d{8}_\\d{6}\\.png"),
                artifact.toString());
        BufferedImage img = ImageIO.read(artifact.toFile());
        assertNotNull(img, "PNG must be readable by ImageIO");
        assertTrue(img.getWidth() > 100 && img.getHeight() > 60,
                "plausible table dimensions, got " + img.getWidth() + "x" + img.getHeight());
    }

    @Test
    void datasetScopePdfDeliversTableSnapshot(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        System.setProperty("java.awt.headless", "true");
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "format", "pdf",
                "measures", "sum(amount),count", "group_by", "region",
                "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("delivered to"), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertTrue(artifact.getFileName().toString().matches("weekly_sales_\\d{8}_\\d{6}\\.pdf"),
                artifact.toString());
        byte[] bytes = Files.readAllBytes(artifact);
        String head = new String(bytes, 0, Math.min(8, bytes.length), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF-"), "must start with a PDF header, got " + head);
        String tail = new String(bytes, Math.max(0, bytes.length - 16), Math.min(16, bytes.length),
                java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(tail.contains("%%EOF"), "must end with %%EOF, got " + tail);
    }

    @Test
    void pngRendererCapsRowsAtSnapshotLimit(@TempDir Path outDir) throws Exception {
        System.setProperty("java.awt.headless", "true");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add(Map.of("n", i, "label", "row " + i));
        Path capped = outDir.resolve("capped.png");
        Path alsoCapped = outDir.resolve("also_capped.png");
        TablePngRenderer.render("cap_test", many, capped);
        TablePngRenderer.render("cap_test", many.subList(0, TablePngRenderer.MAX_ROWS + 1), alsoCapped);

        // Both exceed the cap → identical height (MAX_ROWS body rows + the "+N more" footer line).
        assertEquals(ImageIO.read(alsoCapped.toFile()).getHeight(), ImageIO.read(capped.toFile()).getHeight());
        Path uncapped = outDir.resolve("uncapped.png");
        TablePngRenderer.render("cap_test", many.subList(0, 5), uncapped);
        assertTrue(ImageIO.read(uncapped.toFile()).getHeight() < ImageIO.read(capped.toFile()).getHeight());
    }

    // ── ASSURE-XLSX-ATTACHMENTS-1 ─────────────────────────────────────────────

    /** Records what the Job asked the mail service to send. */
    private static final class RecordingMail implements com.gamma.notify.MailAccess {
        List<String> to;
        List<com.gamma.notify.MailAttachment> attachments;
        @Override public boolean send(List<String> to, List<String> cc, String subject, String body,
                                      List<com.gamma.notify.MailAttachment> attachments) {
            this.to = to;
            this.attachments = attachments;
            return true;
        }
    }

    private static JobContext ctxWith(com.gamma.notify.MailAccess mail) {
        return ctxWith(mail, Map.of(), new java.util.ArrayList<>());
    }

    /** A context whose Run PARAMS (trigger args / bound Signal values) are {@code params}; signals are captured. */
    private static JobContext ctxWith(com.gamma.notify.MailAccess mail, Map<String, String> params, List<String> signals) {
        return new JobContext() {
            @Override public String runId() { return "run-1"; }
            @Override public String spaceId() { return "default"; }
            @Override public TriggerInfo trigger() { return null; }
            @Override public Map<String, String> config() { return params; }
            @Override public Map<String, String> params() { return params; }
            @Override public ArtifactRecorder artifacts() { return null; }
            @Override public com.gamma.util.RunLog log() {
                return new com.gamma.util.RunLog() {
                    @Override public void info(String m, Object... kv) { }
                    @Override public void warn(String m, Object... kv) { }
                    @Override public void error(String m, Throwable t, Object... kv) { }
                };
            }
            @Override public com.gamma.signal.SignalEmitter signals() {
                return (type, severity, payload) -> signals.add(type + " " + payload.get("reason"));
            }
            @Override public PlatformServices services() {
                return new PlatformServices() {
                    @Override public <T> java.util.Optional<T> find(Class<T> type) {
                        return type == com.gamma.notify.MailAccess.class
                                ? java.util.Optional.of(type.cast(mail)) : java.util.Optional.empty();
                    }
                    @Override public java.util.Set<Class<?>> granted() {
                        return java.util.Set.of(com.gamma.notify.MailAccess.class);
                    }
                };
            }
        };
    }

    private static Map<String, String> attachParams(Path outDir) {
        return Map.of("scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString(),
                "recipients", "ops@example.com, finance@example.com", "attach", "true");
    }

    /** What the approve path records: the fingerprint of the Job as loaded. */
    private static void approve(JobConfig cfg, Path writeRoot) throws Exception {
        AttachApprovals.record(writeRoot, cfg.name(), AttachApprovals.fingerprint(cfg, writeRoot));
    }

    @Test
    void anApprovedUnchangedJobMailsItsOwnDeliveredArtifact(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        RecordingMail mail = new RecordingMail();
        JobConfig cfg = job(attachParams(outDir));
        approve(cfg, writeRoot);

        JobResult r = new ReportJob(cfg, null).run(ctxWith(mail));

        assertEquals("SUCCESS", r.status(), r.message());
        assertTrue(r.message().contains("mailed to 2 recipient(s) with attachment"), r.message());
        assertEquals(List.of("ops@example.com", "finance@example.com"), mail.to);
        assertEquals(1, mail.attachments.size());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        assertEquals(artifact.getFileName().toString(), mail.attachments.get(0).filename());
        assertEquals("text/csv", mail.attachments.get(0).contentType());
        assertArrayEquals(Files.readAllBytes(artifact), mail.attachments.get(0).content());
    }

    /** Round 3: a hand-edited (never approved) attach Job refuses at RUN time — audit + Signal, nothing sent. */
    @Test
    void aHandEditedAttachJobRefusesToSend(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        RecordingMail mail = new RecordingMail();
        List<String> signals = new java.util.ArrayList<>();
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                new ReportJob(job(attachParams(outDir)), null).run(ctxWith(mail, Map.of(), signals)));
        assertTrue(e.getMessage().contains("attach not approved for this job version; re-approve"), e.getMessage());
        assertNull(mail.to, "nothing was sent");
        assertEquals(List.of("report.attach.refused attach not approved for this job version; re-approve"), signals);
    }

    @Test
    void anApprovedJobEditedOnDiskRefuses(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        approve(job(attachParams(outDir)), writeRoot);
        Map<String, String> edited = new java.util.LinkedHashMap<>(attachParams(outDir));
        edited.put("recipients", "ops@example.com, leak@example.com");
        RecordingMail mail = new RecordingMail();
        assertThrows(IllegalStateException.class, () -> new ReportJob(job(edited), null).run(ctxWith(mail)));
        assertNull(mail.to);

        // the Dataset it reads is part of the approved version too
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "sales_ds",
                Map.of("view", "sales_view", "description", "now reads something else"));
        assertThrows(IllegalStateException.class, () -> new ReportJob(job(attachParams(outDir)), null).run(ctxWith(mail)));
        assertNull(mail.to);
    }

    /** Round 3: the live bypass — a template-EXPANDED attach Job (no Pending Change ever saw it) refuses. */
    @Test
    void aTemplateExpandedAttachJobRefusesAtRunTime(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        JobTemplate t = new JobTemplate("mailer", Map.of("to", ""), Map.of("type", "report", "scope", "dataset",
                "dataset", "sales_ds", "out_dir", outDir.toString(), "recipients", "${to}", "attach", "true"));
        Map<String, Object> instance = new java.util.LinkedHashMap<>();
        instance.put("name", "weekly_sales");
        instance.put("template", "mailer");
        instance.put("params", Map.of("to", "ops@example.com"));
        JobConfig expanded = JobConfig.fromMap(Map.of("job", t.instantiate(instance)));
        RecordingMail mail = new RecordingMail();
        assertThrows(IllegalStateException.class, () -> new ReportJob(expanded, null).run(ctxWith(mail)));
        assertNull(mail.to);
    }

    /** Round 3 (b): trigger args / a bound Signal can never turn attach on — the AUTHORED config decides. */
    @Test
    void triggerArgsCannotTurnAttachOn(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        Map<String, String> cfg = new java.util.LinkedHashMap<>(attachParams(outDir));
        cfg.put("attach", "false");
        RecordingMail mail = new RecordingMail();
        JobResult r = new ReportJob(job(cfg), null).run(ctxWith(mail,
                Map.of("attach", "true", "signal.attach", "true"), new java.util.ArrayList<>()));
        assertEquals("SUCCESS", r.status(), r.message());
        assertEquals(List.of(), mail.attachments, "mailed WITHOUT an attachment");
    }

    @Test
    void anOverCapAttachmentFailsTheRunWithTheReason(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        System.setProperty(com.gamma.notify.MailAttachments.MAX_ATTACHMENT_PROPERTY, "8");
        try {
            JobConfig cfg = job(Map.of("scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString(),
                    "recipients", "ops@example.com", "attach", "true"));
            approve(cfg, writeRoot);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ReportJob(cfg, null).run(ctxWith(new RecordingMail())));
            assertTrue(e.getMessage().contains("over the 8-byte cap"), e.getMessage());
        } finally {
            System.clearProperty(com.gamma.notify.MailAttachments.MAX_ATTACHMENT_PROPERTY);
        }
    }

    @Test
    void attachWithoutRecipientsIsRefused(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        seedSales(writeRoot);
        System.setProperty("assist.write.root", writeRoot.toString());
        assertThrows(IllegalArgumentException.class, () -> new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString(), "attach", "true")), null)
                .run(ctxWith(new RecordingMail())));
    }

    @Test
    void csvTextCellsAreFormulaNeutralised(@TempDir Path writeRoot, @TempDir Path outDir) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("notes_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('=HYPERLINK(1)', 1), ('@x', 2), ('ok', -3), ('  |calc', 4), ('＝1', 5)) AS t(note, n)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "notes_ds", Map.of("view", "notes_view"));
        System.setProperty("assist.write.root", writeRoot.toString());

        JobResult r = new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "notes_ds", "out_dir", outDir.toString())), null).run();

        assertEquals("SUCCESS", r.status(), r.message());
        Path artifact;
        try (Stream<Path> files = Files.list(outDir)) { artifact = files.findFirst().orElseThrow(); }
        String csv = Files.readString(artifact);
        assertTrue(csv.contains("'=HYPERLINK(1),1") && csv.contains("'@x,2"), csv);
        assertTrue(csv.contains("'  |calc,4") && csv.contains("'＝1,5"), csv);
        // a NUMBER is never prefixed — -3 is a value, and prefixing it would break every numeric reader
        assertTrue(csv.contains("ok,-3"), csv);
    }

    @Test
    void datasetScopeWithoutWriteRootFails(@TempDir Path outDir) {
        assertThrows(IllegalStateException.class, () -> new ReportJob(job(Map.of(
                "scope", "dataset", "dataset", "sales_ds", "out_dir", outDir.toString())), null).run());
    }
}
