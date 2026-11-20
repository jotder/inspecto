package com.gamma.etl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} Gate: the Basic profile is one declaration - what Basic keeps per dimension, the feature
 * everything else needs, Basic's entry points - and the console's mirrors of it agree. Every route-level check is over real HTTP
 * in {@code ControlApiBasicProfileGateTest}; the screens in {@code ControlApiDataTierScreenGatesTest}.
 */
class BasicProfileTest {

    @AfterEach
    void restore() {
        EditionFeatures.overrideForTest(null);
    }

    @Test
    void basicKeepsLocalFilesTheDatasetEntryAndPostgresAndLocksEveryOtherScheme() {
        for (String kept : List.of("local", "dataset", "postgres")) assertNull(BasicProfile.schemeFeature(kept), kept);
        for (String remote : List.of("sftp", "ftp", "ftps", "db", "s3", "azure", "gcs", "kafka"))
            assertEquals(EditionFeatures.REMOTE_SYSTEMS, BasicProfile.schemeFeature(remote), remote);
    }

    @Test
    void basicKeepsNoCodeShapingAndLocksTheStepsThatComputeJoinOrEnrich() {
        for (String kept : List.of("transform.filter", "transform.dedup", "transform.mask", "parser.delimited", "acquisition"))
            assertNull(BasicProfile.stepFeature(kept), kept);
        assertNull(BasicProfile.stepFeature(null));
        for (String locked : List.of("transform.sql", "transform.join", "transform.summarize", "enrichment"))
            assertEquals(EditionFeatures.PIPELINE_TRANSFORM, BasicProfile.stepFeature(locked), locked);
        assertEquals(EditionFeatures.PIPELINE_EGRESS, BasicProfile.jobTypeFeature("objectstore.export"));
        assertEquals(EditionFeatures.PIPELINE_EGRESS, BasicProfile.jobTypeFeature("publish.postgres"));
        assertNull(BasicProfile.jobTypeFeature("maintenance"));
    }

    /** Every feature the profile locks anything behind is a screen gate, so each locked control shows its reason. */
    @Test
    void everyLockIsAScreenFlagAndEachEntryPointIsOfferedPerItsFeature() {
        Set<String> screen = Set.copyOf(BasicProfile.SCREEN_FLAGS.values());
        assertTrue(screen.contains(BasicProfile.schemeFeature("sftp")));
        assertTrue(screen.contains(BasicProfile.stepFeature("transform.sql")));
        assertTrue(screen.contains(BasicProfile.jobTypeFeature("publish.postgres")));
        for (BasicProfile.Entry e : BasicProfile.Entry.values())
            if (e.feature() != null) assertTrue(screen.contains(e.feature()), e + " is locked behind a screen gate");

        EditionFeatures.overrideForTest(Set.of());   // Basic
        Set<BasicProfile.Entry> offered = new LinkedHashSet<>();
        for (BasicProfile.Entry e : BasicProfile.Entry.values()) if (e.offered()) offered.add(e);
        assertEquals(Set.of(BasicProfile.Entry.ADD_FILE, BasicProfile.Entry.POSTGRES_TABLE, BasicProfile.Entry.WATCH_FOLDER), offered);

        EditionFeatures.overrideForTest(screen);   // Standard
        for (BasicProfile.Entry e : BasicProfile.Entry.values()) assertTrue(e.offered(), e + " on Standard");
    }

    /** DT-9 as narrowed (ruling O2): a configuration using every Standard-only option is not refused on save or load by Basic. */
    @Test
    void nothingTheProfileLocksIsRefusedByTheSaveGate() {
        EditionFeatures.overrideForTest(Set.of());   // Basic
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("name", "GATE_ON_LOAD");
        draft.put("collector", Map.of("connector", "sftp", "connection", "sftp_prod",
                "duplicate", Map.of("mode", "content", "on_change", "ignore")));
        draft.put("trigger", Map.of("type", "schedule", "every", "5m"));
        draft.put("steps", List.of(Map.of("sql", Map.of("query", "SELECT 1")), Map.of("join", Map.of("reference", "r"))));
        assertEquals(List.of(), EditionFeatures.pipelineRefusals(draft));
    }

    // ── the console's mirrors ─────────────────────────────────────────────────────────────────────────────────

    private static final Path UI = Path.of("..", "..", "inspecto-ui", "src", "app", "inspecto");

    /** {@code REMOTE_CONNECTORS} in {@code connection-types.ts}: each is a scheme the profile locks, none one it keeps. */
    @Test
    void theConsolesRemoteConnectorsAreTheSchemesTheProfileLocks() throws Exception {
        String ts = Files.readString(UI.resolve("connections").resolve("connection-types.ts"));
        int from = ts.indexOf("REMOTE_CONNECTORS");
        int to = ts.indexOf("]);", from);
        assertTrue(from >= 0 && to > from, "REMOTE_CONNECTORS not found in connection-types.ts");
        Matcher m = Pattern.compile("'([a-z0-9]+)'").matcher(ts.substring(from, to));
        Set<String> remote = new LinkedHashSet<>();
        while (m.find()) remote.add(m.group(1));
        assertFalse(remote.isEmpty(), "REMOTE_CONNECTORS parsed");
        for (String c : remote) assertEquals(EditionFeatures.REMOTE_SYSTEMS, BasicProfile.schemeFeature(c), c);
    }

    /** {@code SCREEN_LOCK_FALLBACK} in {@code edition-locks.ts}: the same flags, each with the server's own line. */
    @Test
    void theConsolesScreenLockFallbacksAreTheProfilesFlags() throws Exception {
        String ts = Files.readString(UI.resolve("edition-locks.ts"));
        int from = ts.indexOf("SCREEN_LOCK_FALLBACK");
        int to = ts.indexOf("};", from);
        assertTrue(from >= 0 && to > from, "SCREEN_LOCK_FALLBACK not found in edition-locks.ts");
        Matcher m = Pattern.compile("(\\w+):\\s*'([^']*)'").matcher(ts.substring(from, to));
        Map<String, String> fallback = new LinkedHashMap<>();
        while (m.find()) fallback.put(m.group(1), m.group(2));
        assertEquals(List.copyOf(BasicProfile.SCREEN_FLAGS.keySet()), List.copyOf(fallback.keySet()));
        EditionFeatures.overrideForTest(Set.of());   // Basic: the lines a locked control shows
        BasicProfile.SCREEN_FLAGS.forEach((flag, feature) -> assertEquals(EditionFeatures.lockedReason(feature), fallback.get(flag), flag));
    }
}
