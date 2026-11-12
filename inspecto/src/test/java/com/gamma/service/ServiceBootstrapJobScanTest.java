package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOB-LOADER-SAVED-VIEW-SUFFIX-1 (release proof 2026-11-09): {@code la-logistics} ships the saved view
 * {@code config/registry/link-analysis-views/driver_team_inside_job.toon}, and every boot read it as a Job and logged
 * WARN "Pipeline toon is missing required section 'job'". {@code registry/} is the component store's tree (views,
 * alert rules, Datasets…); no suffix-found config lives there, so the boot scan does not enter it.
 */
class ServiceBootstrapJobScanTest {

    @Test
    void aSavedViewNamedLikeAJobIsNotScannedAsAJob(@TempDir Path config) throws Exception {
        Path job = write(config.resolve("jobs/links_job.toon"));
        Path nested = write(config.resolve("examples/sql/nightly_job.toon"));   // Jobs live outside jobs/ too
        write(config.resolve("registry/link-analysis-views/driver_team_inside_job.toon"));

        List<Path> found = ServiceBootstrap.resolveBySuffix(new String[]{config.toString()}, "_job.toon");

        assertEquals(List.of(nested, job).stream().sorted().toList(), found);
    }

    @Test
    void aRegistryFileGivenDirectlyIsStillRead(@TempDir Path config) throws Exception {
        // an explicit file argument is the caller's choice, not a scan: it is not filtered
        Path direct = write(config.resolve("registry/x/odd_job.toon"));
        assertEquals(List.of(direct), ServiceBootstrap.resolveBySuffix(new String[]{direct.toString()}, "_job.toon"));
    }

    private static Path write(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "job:\n  name: x\n");
        return p;
    }
}
