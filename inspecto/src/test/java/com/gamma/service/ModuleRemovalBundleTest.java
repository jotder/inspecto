package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MODULE-REORG-1 P4a} - removal semantics (plan section 2.5) for Space bundles: config of a module that is
 * not installed travels opaquely.
 *
 * <p>Verdicts. EXPORT is a directory walk: every file, including {@code modules.toon} and a registry kind nothing
 * here understands, is carried byte-for-byte (PRESERVED). CLONE import ({@code refuseReserved=false}, the
 * new-Space seed) writes all of it verbatim (PRESERVED). IMPORT INTO AN EXISTING Space is judged by
 * {@link ImportPaths}: a Job of an unregistered type is written verbatim (PRESERVED); {@code modules.toon} is
 * REFUSED by design (a Space settings document, like {@code branding.toon}); a registry entry of a kind no
 * installed module registers is REFUSED-LOUDLY and the refusal is all-or-nothing - before the first byte lands.
 */
class ModuleRemovalBundleTest {

    private static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("modules.toon", "disabled[1]: zz-absent-module\nx-keep: 1\n");
        FILES.put("registry/zz-absent-kind/thing.toon", "name: thing\nzz_key: 1\n");
        FILES.put("registry/decision-rules/r.toon", "name: r\nconsequences[1]:\n  - action: zz-absent-action\n");
        FILES.put("zz_job.toon", "job:\n  name: zz\n  type: zz.absent-module-job\n  zz_param: keep\n");
    }

    private static BundleImporter.Bundle exported(Path tmp) throws Exception {
        Path cfg = tmp.resolve("a/config");
        for (var e : FILES.entrySet()) {
            Path p = cfg.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        return BundleImporter.parse(BundleExporter.exportSpace(cfg, null, "a"));
    }

    @Test
    void exportCarriesEveryFileOpaquely(@TempDir Path tmp) throws Exception {
        BundleImporter.Bundle b = exported(tmp);
        assertEquals(FILES.keySet(), b.configEntries().keySet(), "the export walks the directory - no roster of kinds");
        for (var e : FILES.entrySet())
            assertArrayEquals(e.getValue().getBytes(StandardCharsets.UTF_8), b.configEntries().get(e.getKey()), e.getKey());
    }

    @Test
    void aCloneImportWritesAllOfItVerbatim(@TempDir Path tmp) throws Exception {
        BundleImporter.Bundle b = exported(tmp);
        Path target = tmp.resolve("clone/config");
        BundleImporter.writeConfig(b, target, false, new ImportJournal());
        for (var e : FILES.entrySet())
            assertEquals(e.getValue(), Files.readString(target.resolve(e.getKey())), e.getKey());
    }

    @Test
    void importIntoAnExistingSpaceKeepsAnAbsentTypeJobAndRefusesWhatItCannotJudge(@TempDir Path tmp) throws Exception {
        BundleImporter.Bundle b = exported(tmp);

        var jobOnly = new BundleImporter.Bundle(b.kind(), b.manifest(),
                new LinkedHashMap<>(Map.of("zz_job.toon", b.configEntries().get("zz_job.toon"))), null);
        Path target = tmp.resolve("existing/config");
        BundleImporter.writeConfig(jobOnly, target);
        assertEquals(FILES.get("zz_job.toon"), Files.readString(target.resolve("zz_job.toon")),
                "a Job of an unregistered type is written verbatim, unvalidated against the Job Type roster");

        IllegalArgumentException settings = assertThrows(IllegalArgumentException.class,
                () -> BundleImporter.writeConfig(b, tmp.resolve("whole/config")));
        assertTrue(settings.getMessage().contains("modules.toon"), settings.getMessage());

        var unknownKind = new BundleImporter.Bundle(b.kind(), b.manifest(), new LinkedHashMap<>(Map.of(
                "zz_job.toon", b.configEntries().get("zz_job.toon"),
                "registry/zz-absent-kind/thing.toon", b.configEntries().get("registry/zz-absent-kind/thing.toon"))), null);
        Path none = tmp.resolve("refused/config");
        IllegalArgumentException loud = assertThrows(IllegalArgumentException.class,
                () -> BundleImporter.writeConfig(unknownKind, none));
        assertTrue(loud.getMessage().contains("registry/zz-absent-kind/thing.toon")
                && loud.getMessage().contains("importable kind"), loud.getMessage());
        assertFalse(Files.exists(none.resolve("zz_job.toon")), "all-or-nothing: the refusal lands before the first byte");
    }
}
