package com.gamma.ops;

import com.gamma.service.OperationalDb;
import com.gamma.service.SpaceRoot;
import com.gamma.service.StoreFamily;

import java.util.function.Function;

/**
 * The four Operational Object store families this module owns (MODULE-REORG-P1-FAMILY, 2026-10-08): they left the
 * core {@code OperationalDb.Family} enum for the contribution point ({@link OpsStoreFamilyProvider}). ⚠ Persisted
 * data is untouched - same properties, same defaults, same per-Space file names, same Postgres schemas - and
 * {@code StoreFamilyParityTest}'s golden table is what proves it.
 *
 * <p>All four share ONE toggle ({@code objects.backend}) and one credential pair
 * ({@code objects.db.user}/{@code .password}) but each has its own URL property ("URL grain != credential grain").
 * Default {@code db}, never {@code memory} (OBJECTS-BACKEND-DEFAULT-MEMORY-1, operator 2026-09-25): Incidents,
 * Cases, notes, links and tags vanishing on restart is not a default any edition keeps. {@code postgres} is what
 * the Enterprise launcher sets - PostgreSQL mandatory, refused at boot by {@code OperationalDb.verifyObjectsBackend()}
 * when it cannot be honoured; {@code memory} survives only as an explicit opt-in.
 */
public enum OpsStoreFamily implements StoreFamily {
    OBJECTS("Objects", "objects.db.url", SpaceRoot::objectsDbUrl),
    LINKS("Links", "objects.links.db.url", SpaceRoot::linksDbUrl),
    NOTES("Notes", "objects.notes.db.url", SpaceRoot::notesDbUrl),
    TAGS("Tag assignments", "objects.tags.db.url", SpaceRoot::tagAssignmentsDbUrl);

    private final String label;
    private final String urlProperty;
    private final Function<SpaceRoot, String> spaceDefault;

    OpsStoreFamily(String label, String urlProperty, Function<SpaceRoot, String> spaceDefault) {
        this.label = label;
        this.urlProperty = urlProperty;
        this.spaceDefault = spaceDefault;
    }

    @Override public String label() { return label; }
    @Override public String backendProperty() { return OperationalDb.OBJECTS_BACKEND; }
    @Override public String backendDefault() { return OperationalDb.OBJECTS_BACKEND_DEFAULT; }
    @Override public Mode mode() { return Mode.DB_FLAG; }
    @Override public String urlProperty() { return urlProperty; }
    @Override public String userProperty() { return "objects.db.user"; }
    @Override public String passwordProperty() { return "objects.db.password"; }
    @Override public String spaceDefault(SpaceRoot root) { return spaceDefault.apply(root); }
}
