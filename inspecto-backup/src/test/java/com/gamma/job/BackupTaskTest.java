package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The backup / backup_verify / restore tasks (MNT-5 / MNT-6), driven the way production drives them —
 * through {@link MaintenanceJob}, whose {@code default} arm finds {@code BackupTaskProvider} on this
 * module's classpath via {@code META-INF/services}. So every case here also proves the
 * {@link MaintenanceTaskProvider} wiring end to end; a broken services file fails all of them.
 *
 * <p>⚠ <b>Package {@code com.gamma.job}, on purpose.</b> These five cases moved VERBATIM out of the
 * engine's {@code MaintenanceLibraryTest} on 2026-09-07 (EDG-01 cell 2), and they construct
 * {@code MaintenanceJob}, {@code RunContext}, {@code RunLogStore} and {@code RunArtifactStore} — all
 * package-private. A test-scope split package is what keeps them unchanged in meaning; rewriting them to
 * drive the provider directly would have stopped testing the seam they now depend on.
 */
class BackupTaskTest {

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    /** A real {@link RunContext} marked as a preview fire (MNT-1), audit files under {@code auditDir}. */
    private static JobContext dryCtx(Path auditDir) {
        RunContext ctx = new RunContext("r-dry", "default", "m", "manual", "r-dry", null, 0, Map.of(),
                new RunLogStore(auditDir.toString()), 100, new RunArtifactStore(auditDir.toString()));
        ctx.dryRun(true);
        return ctx;
    }

    /** Seed a two-file source tree (one nested) and return the config for a backup of it. */
    private static JobConfig backupCfg(Path source, Path backupDir) throws Exception {
        Files.writeString(Files.createDirectories(source.resolve("orders")).resolve("a.toon"), "alpha");
        Files.writeString(source.resolve("space.toon"), "root");
        return job(Map.of("task", "backup", "dir", source.toString(), "backup_dir", backupDir.toString()));
    }

    private static Path onlyZip(Path backupDir) throws Exception {
        try (var s = Files.list(backupDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }
    }

    /** The seam itself: this module's provider is what the core discovers, and it claims exactly three tasks. */
    @Test
    void theProviderIsDiscoveredAndClaimsExactlyTheThreeTasks() {
        var found = new java.util.ArrayList<MaintenanceTaskProvider>();
        java.util.ServiceLoader.load(MaintenanceTaskProvider.class).forEach(found::add);
        assertEquals(1, found.size(), "exactly one provider on this classpath: " + found);
        assertEquals(java.util.Set.of("backup", "backup_verify", "restore"), found.get(0).tasks());
    }

    @Test
    void backupArchivesWithManifestAndDryRunPreviews(@TempDir Path source, @TempDir Path backupDir,
                                                     @TempDir Path ctxDir) throws Exception {
        JobConfig cfg = backupCfg(source, backupDir);

        JobResult dry = new MaintenanceJob(cfg).run(dryCtx(ctxDir));
        assertTrue(dry.message().contains("would archive 2 file(s), 9 byte(s)"), dry.message());
        try (var s = Files.list(backupDir)) {
            assertEquals(0, s.count(), "dry run writes nothing");
        }

        JobResult real = new MaintenanceJob(cfg).run();
        assertTrue(real.message().contains("archived 2 file(s), 9 byte(s)"), real.message());
        Path zip = onlyZip(backupDir);
        assertTrue(Files.isRegularFile(zip.resolveSibling(zip.getFileName() + ".manifest.json")),
                "sidecar manifest written");
    }

    @Test
    void backupVerifyPassesThenDetectsCorruption(@TempDir Path source, @TempDir Path backupDir) throws Exception {
        new MaintenanceJob(backupCfg(source, backupDir)).run();
        JobConfig verify = job(Map.of("task", "backup_verify", "backup_dir", backupDir.toString()));

        JobResult ok = new MaintenanceJob(verify).run();
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertTrue(ok.message().contains("1 archive(s) OK, 2 file entr(ies) hash-checked"), ok.message());

        // Flip bytes inside the archive — verification must fail on the archive hash, fail-closed.
        Path zip = onlyZip(backupDir);
        byte[] bytes = Files.readAllBytes(zip);
        bytes[bytes.length / 2] ^= 0x7f;
        Files.write(zip, bytes);
        JobResult bad = new MaintenanceJob(verify).run();
        assertEquals("FAILED", bad.status(), bad.message());
        assertTrue(bad.message().contains("archive hash mismatch"), bad.message());
    }

    @Test
    void restoreRoundTripsBlocksConflictsAndPreviews(@TempDir Path source, @TempDir Path backupDir,
                                                     @TempDir Path target, @TempDir Path ctxDir) throws Exception {
        new MaintenanceJob(backupCfg(source, backupDir)).run();
        Path zip = onlyZip(backupDir);
        JobConfig restore = job(Map.of("task", "restore",
                "archive", zip.toString(), "target_dir", target.toString()));

        JobResult first = new MaintenanceJob(restore).run();
        assertEquals("SUCCESS", first.status(), first.message());
        assertEquals("alpha", Files.readString(target.resolve("orders").resolve("a.toon")), "byte-identical restore");
        assertEquals("root", Files.readString(target.resolve("space.toon")));

        // Same restore again: everything now conflicts — preview says so, real run blocks fail-closed.
        JobResult dry = new MaintenanceJob(restore).run(dryCtx(ctxDir));
        assertTrue(dry.message().contains("(2 conflict(s))"), dry.message());
        JobResult blocked = new MaintenanceJob(restore).run();
        assertEquals("FAILED", blocked.status(), blocked.message());
        assertTrue(blocked.message().contains("restore blocked: 2 existing file(s)"), blocked.message());

        JobResult forced = new MaintenanceJob(job(Map.of("task", "restore", "archive", zip.toString(),
                "target_dir", target.toString(), "overwrite", "true"))).run();
        assertEquals("SUCCESS", forced.status(), forced.message());
        assertTrue(forced.message().contains("(2 overwritten)"), forced.message());
    }

    /**
     * PATH-2 residual (pinned 2026-09-06): the zip-slip jail in {@code BackupTask.restore} sits BEHIND the
     * sidecar + archive-hash verification — and that verification also checks that every sidecar entry
     * EXISTS in the archive, so a tampered sidecar alone is refused one layer earlier ("manifest entry
     * missing from archive") and never reaches the jail. Reaching it takes what an attacker with write
     * access to the backup dir has: an archive RE-PACKED with the escaping entry name and a sidecar
     * re-signed to match (same hashing as the writer, {@code Checksums.of}). Both a relative escape and an
     * absolute path must refuse BEFORE a byte is written — the jail is a pre-pass over every entry.
     */
    @Test
    void restoreRefusesAnArchiveEntryThatEscapesTheTargetBeforeWritingAnything(
            @TempDir Path source, @TempDir Path backupDir, @TempDir Path target) throws Exception {
        new MaintenanceJob(backupCfg(source, backupDir)).run();
        Path zip = onlyZip(backupDir);
        Path sidecar = zip.resolveSibling(zip.getFileName() + ".manifest.json");
        byte[] signedZip = Files.readAllBytes(zip);
        String signedSidecar = Files.readString(sidecar);
        Path outside = target.getParent().resolve("evil.txt");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();

        for (String escape : new String[]{"../evil.txt", outside.toString().replace('\\', '/')}) {
            // 1. re-pack: the entry `space.toon` travels under the escaping name, bytes unchanged
            var out = new java.io.ByteArrayOutputStream();
            try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(signedZip));
                 var zout = new java.util.zip.ZipOutputStream(out)) {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    zout.putNextEntry(new java.util.zip.ZipEntry("space.toon".equals(e.getName()) ? escape : e.getName()));
                    zin.transferTo(zout);
                    zout.closeEntry();
                }
            }
            Files.write(zip, out.toByteArray());
            // 2. re-sign: the sidecar names the new entry and the archive's new hash, exactly as the writer would
            @SuppressWarnings("unchecked")
            Map<String, Object> manifest = json.readValue(signedSidecar, Map.class);
            manifest.put("archiveSha256", com.gamma.acquire.Checksums.of(zip, "SHA-256"));
            boolean renamed = false;
            for (Object o : (List<?>) manifest.get("files")) {
                @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) o;
                if ("space.toon".equals(entry.get("path"))) { entry.put("path", escape); renamed = true; }
            }
            assertTrue(renamed, "the fixture's second entry is space.toon");
            Files.writeString(sidecar, json.writeValueAsString(manifest));

            // the archive verifies (hash + every entry present) — and THEN the jail refuses
            JobResult r = new MaintenanceJob(job(Map.of("task", "restore",
                    "archive", zip.toString(), "target_dir", target.toString()))).run();
            assertEquals("FAILED", r.status(), r.message());
            assertTrue(r.message().contains("restore blocked: entry escapes target_dir: " + escape), r.message());
            assertFalse(Files.exists(outside), "nothing lands outside the target");
            assertFalse(Files.exists(target.resolve("orders").resolve("a.toon")),
                    "the pre-pass refuses before the FIRST entry is written, not after the good ones");
        }

        // and the untampered pair restores — the refusal was the escape, not the fixture
        Files.write(zip, signedZip);
        Files.writeString(sidecar, signedSidecar);
        JobResult ok = new MaintenanceJob(job(Map.of("task", "restore",
                "archive", zip.toString(), "target_dir", target.toString()))).run();
        assertEquals("SUCCESS", ok.status(), ok.message());
    }

    @Test
    void backupAppendsCatalogRowAndRegistersTheDataset(@TempDir Path source, @TempDir Path backupDir,
                                                       @TempDir Path dataDir, @TempDir Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            JobResult r = new MaintenanceJob(backupCfg(source, backupDir), dataDir.toString()).run();
            assertEquals("SUCCESS", r.status(), r.message());
            Path storeDir = dataDir.resolve("maintenance_backups");
            try (var s = Files.list(storeDir)) {
                assertEquals(1, s.filter(p -> p.getFileName().toString().endsWith(".parquet")).count(),
                        "one catalog row parquet per backup");
            }
            var store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
            assertTrue(store.exists("dataset", "maintenance_backups"), "catalog Dataset registered");
        } finally {
            System.clearProperty("assist.write.root");
        }
    }
}
