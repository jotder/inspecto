package com.gamma.service;

import com.gamma.consignment.DbConsignmentOutputStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The backend-toggle defaults in {@link ServiceStores} (addressing plan D1).
 *
 * <p>Only {@code consignment.outputs.backend} defaults to a real store, and that asymmetry is deliberate
 * enough to be worth a test: the registry carries {@code ReprocessCommand}'s only way to tell a compacted-away
 * output from a hand-deleted one, and reprocessing on a wrong guess duplicates rows. Every other store here
 * stays opt-in, so a regression that "tidied" this one back to {@code none} would silently switch a
 * data-corruption guard off again.
 */
class ServiceStoresDefaultsTest {

    private static final String KEY = "consignment.outputs.backend";

    /** Run {@code body} with {@code KEY} set to {@code value} ({@code null} = unset), restoring it after. */
    private static void withBackend(String value, Runnable body) {
        String previous = System.getProperty(KEY);
        try {
            if (value == null) System.clearProperty(KEY);
            else System.setProperty(KEY, value);
            body.run();
        } finally {
            if (previous == null) System.clearProperty(KEY);
            else System.setProperty(KEY, previous);
        }
    }

    @Test
    void consignmentOutputsRegistryIsOpenedWithNoPropertySet(@TempDir Path dir) {
        withBackend(null, () -> {
            try (DbConsignmentOutputStore store =
                         ServiceStores.openConsignmentOutputStore(SpaceRoot.under(dir))) {
                assertNotNull(store, "the registry must be on by default — ReprocessCommand's compacted-away "
                        + "guard is undecidable without it, and guessing duplicates rows");
            }
        });
    }

    /** Optionality is still part of the contract: an operator can turn it off, and absence must stay a
     *  supported state rather than a broken one. */
    @Test
    void explicitNoneStillDisablesIt(@TempDir Path dir) {
        withBackend("none", () ->
                assertNull(ServiceStores.openConsignmentOutputStore(SpaceRoot.under(dir))));
    }

    /** Unrecognised values are off, not a hard failure — same three-value contract as the other toggles. */
    @Test
    void unrecognisedBackendIsOffRatherThanAnError(@TempDir Path dir) {
        withBackend("mysql", () ->
                assertNull(ServiceStores.openConsignmentOutputStore(SpaceRoot.under(dir))));
    }

    /** The stores that were not flipped: a default change must not spread by copy-paste. */
    @Test
    void theOtherRegistriesStayOptIn(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        assertNull(ServiceStores.openFileStageStore(root), "file.stages.backend still defaults to none");
        assertNull(ServiceStores.openProvenanceStore(root), "provenance.backend still defaults to none");
        assertNull(ServiceStores.openJobRunStore(root), "jobs.backend still defaults to none");
    }
}
