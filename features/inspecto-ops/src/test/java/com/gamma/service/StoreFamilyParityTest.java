package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MODULE-REORG-P1-FAMILY parity pin: how EVERY operational-store family is addressed (label, properties,
 * default, and the resolved source / URL / user / password / Postgres-schema-scoped URL under six property
 * scenarios) is compared with a golden table captured from the 16-constant {@code OperationalDb.Family} enum
 * BEFORE it was split into core + contributed families (and unchanged by the split - the golden file did not move). The table is sorted by family name, so it pins WHAT
 * each family resolves to and not the order of the roster; persisted data is unchanged exactly when this file
 * is. ⚠ Lives in the ops module because only there is the whole roster (core + the four object families) on the
 * classpath. Regenerate only for a deliberate change: the actual table is written to
 * {@code target/store-family-parity.actual.txt}.
 */
class StoreFamilyParityTest {

    private static final String PG = "jdbc:postgresql://db:5432/inspecto";

    @Test
    void everyFamilyResolvesExactlyAsItDidBeforeTheRosterWasOpened(@TempDir Path base) throws Exception {
        Path spaceDir = base.resolve("north-east");
        SpaceRoot root = SpaceRoot.under(spaceDir);
        List<String> names = new ArrayList<>();
        Map<String, Map<String, String>> scenarios = new LinkedHashMap<>();

        // Every property any family or the shared selection reads: cleared for each scenario, restored after.
        Map<String, String> prior = new TreeMap<>();
        List<String> keys = new ArrayList<>(List.of("inspecto.db", "inspecto.db.url", "inspecto.db.user",
                "inspecto.db.password"));
        for (OperationalDb.Resolved r : OperationalDb.resolveAll(root)) {
            StoreFamily f = r.family();
            names.add(f.name());
            for (String k : new String[] {f.backendProperty(), f.urlProperty(), f.userProperty(), f.passwordProperty()})
                if (k != null && !keys.contains(k)) keys.add(k);
        }
        for (String k : keys) prior.put(k, System.getProperty(k));

        Map<String, String> enabled = new LinkedHashMap<>();
        for (OperationalDb.Resolved r : OperationalDb.resolveAll(root)) {
            StoreFamily f = r.family();
            enabled.put(f.backendProperty(), "URL_OR_ENGINE".equals(f.mode().name()) ? "duckdb" : "db");
        }
        Map<String, String> shared = new LinkedHashMap<>(enabled);
        shared.put("inspecto.db", "postgres");
        shared.put("inspecto.db.url", PG);
        shared.put("inspecto.db.user", "svc");
        shared.put("inspecto.db.password", "s3cret");
        Map<String, String> own = new LinkedHashMap<>(shared);
        Map<String, String> raw = new LinkedHashMap<>(enabled);
        for (OperationalDb.Resolved r : OperationalDb.resolveAll(root)) {
            StoreFamily f = r.family();
            own.put(f.urlProperty(), "jdbc:postgresql://own-" + f.name().toLowerCase() + "/db");
            if (f.userProperty() != null) own.put(f.userProperty(), "u-" + f.userProperty());
            if (f.passwordProperty() != null) own.put(f.passwordProperty(), "p-" + f.passwordProperty());
            if ("URL_OR_ENGINE".equals(f.mode().name()))
                raw.put(f.backendProperty(), "jdbc:duckdb:/raw/" + f.name().toLowerCase() + ".db");
        }
        Map<String, String> objectsPg = new LinkedHashMap<>();
        objectsPg.put("objects.backend", "postgres");
        objectsPg.put("inspecto.db", "postgres");
        objectsPg.put("inspecto.db.url", PG);

        scenarios.put("DEFAULT", Map.of());
        scenarios.put("ENABLED-DUCKDB", enabled);
        scenarios.put("ENABLED-SHARED-POSTGRES", shared);
        scenarios.put("ENABLED-PER-FAMILY-URL-AND-CREDENTIALS", own);
        scenarios.put("RAW-JDBC-BACKEND-VALUES", raw);
        scenarios.put("OBJECTS-BACKEND-POSTGRES", objectsPg);

        List<String> lines = new ArrayList<>();
        try {
            for (Map.Entry<String, Map<String, String>> sc : scenarios.entrySet()) {
                for (String k : keys) System.clearProperty(k);
                sc.getValue().forEach(System::setProperty);
                for (int pass = 0; pass < 2; pass++) {
                    SpaceRoot r0 = pass == 0 ? root : SpaceRoot.legacy();
                    String rootTag = pass == 0 ? "space" : "legacy";
                    List<OperationalDb.Resolved> all = new ArrayList<>(OperationalDb.resolveAll(r0));
                    all.sort(Comparator.comparing(r -> r.family().name()));
                    for (OperationalDb.Resolved r : all) {
                        StoreFamily f = r.family();
                        String scoped;
                        try {
                            scoped = OperationalDb.urlFor(f, r0, "jdbc:duckdb:default.db");
                        } catch (RuntimeException e) {
                            scoped = "REFUSED " + e.getClass().getSimpleName();
                        }
                        lines.add(sc.getKey() + "|" + rootTag + "|" + f.name() + "|" + r.source() + "|" + r.url()
                                + "|" + r.user() + "|" + OperationalDb.userFor(f) + "|" + OperationalDb.passwordFor(f)
                                + "|" + scoped);
                    }
                }
            }
        } finally {
            prior.forEach((k, v) -> { if (v == null) System.clearProperty(k); else System.setProperty(k, v); });
        }

        // Metadata is independent of the properties: pin it from the DEFAULT scenario's roster once.
        List<String> meta = new ArrayList<>();
        for (OperationalDb.Resolved r : OperationalDb.resolveAll(root)) {
            StoreFamily f = r.family();
            meta.add("META|" + f.name() + "|" + f.label() + "|" + f.backendProperty() + "|" + f.backendDefault() + "|"
                    + f.mode().name() + "|" + f.urlProperty() + "|" + f.userProperty() + "|" + f.passwordProperty());
        }
        meta.sort(Comparator.naturalOrder());

        String actual = normalise(String.join("\n", meta) + "\n" + String.join("\n", lines) + "\n", spaceDir);
        Path out = Path.of("target", "store-family-parity.actual.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, actual, StandardCharsets.UTF_8);

        String golden;
        try (InputStream in = StoreFamilyParityTest.class.getResourceAsStream("/store-family-parity.golden.txt")) {
            if (in == null) throw new IllegalStateException("store-family-parity.golden.txt is missing - see " + out);
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(golden, actual, "a family's address changed - see " + out.toAbsolutePath()
                + " (persisted file names / Postgres schema names must stay identical)");
        assertEquals(16, new java.util.HashSet<>(names).size(),
                "the whole roster (core + the four object families) is on the ops test classpath");
    }

    /** Drops the per-run temp directory and the host's separators so the table is machine-independent. */
    private static String normalise(String text, Path spaceDir) throws IOException {
        String abs = spaceDir.toAbsolutePath().toString();
        String parent = spaceDir.getParent().toAbsolutePath().toString();
        String t = text.replace("\\", "/");
        t = t.replace(abs.replace("\\", "/"), "<space>").replace(parent.replace("\\", "/"), "<base>");
        return t;
    }
}
