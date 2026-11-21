package com.gamma.recon;

import com.gamma.spi.http.ComponentKindValidator;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Authoring-time validation of a {@code reconciliation} component (RECON-PERF-1, operator 2026-10-09): its saved
 * tolerance {@code bands} ({@link ReconConfigLoader#bands}) and the shape its run builds from ({@link ReconConfigLoader#buildSpec}). Runs on every component writer — the generic
 * {@code PUT /components/reconciliation/{id}} (gated {@code canAuthorWorkbench}, the capability that already gates
 * editing a Reconciliation) and the bulk writers — so a bad band is a 422 at save, never a broken Board later.
 */
public final class ReconKindValidator implements ComponentKindValidator {
    @Override public String type() { return "reconciliation"; }

    @Override public void validate(String id, Map<String, Object> content) {
        ReconConfigLoader.bands(content);
        // COMPONENT-VALIDATE-ALL-1: the shape the run builds its spec from (two or three Datasets, a known cardinality) is refused at
        // save too, not first at run; the relation SQL is not needed to judge the shape.
        ReconConfigLoader.buildSpec(content, dataset -> "SELECT 1");
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        // nothing Space-dependent to check
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        // a Reconciliation reserves no store names
    }
}
