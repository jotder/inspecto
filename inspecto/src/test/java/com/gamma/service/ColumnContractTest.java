package com.gamma.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ReactorModules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.gamma.service.ColumnContract.Col;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-3a — the column contract: each change class, the rename heuristic and its limits, the published set read
 * from a Schema, and the lock section over a copy of the demo Space (a hand rename and a hand removal are
 * reported on the next computation with exactly the census consumers they break; a fixed consumer drops out;
 * the kill switch removes the section).
 */
class ColumnContractTest {

    @AfterEach
    void clear() {
        System.clearProperty(ColumnContract.PROPERTY);
        System.clearProperty(SpaceLock.PROPERTY);
    }

    private static List<String> classes(List<Col> before, List<Col> after) {
        List<String> out = new ArrayList<>();
        for (ColumnContract.Change c : ColumnContract.classify(before, after))
            out.add(c.change() + " " + c.column() + (c.to() == null ? "" : "->" + c.to())
                    + (c.change().startsWith("type") ? " " + c.typeBefore() + "->" + c.typeAfter() : ""));
        return out;
    }

    private static Col c(String n, String t) {
        return new Col(n, t);
    }

    // ── classes ──────────────────────────────────────────────────────────────────

    @Test
    void anAddedColumnIsSafe() {
        List<ColumnContract.Change> ch = ColumnContract.classify(List.of(c("A", "VARCHAR")),
                List.of(c("A", "VARCHAR"), c("B", "INTEGER")));
        assertEquals(1, ch.size());
        assertEquals("added", ch.get(0).change());
        assertFalse(ch.get(0).breaking());
    }

    @Test
    void aRemovedColumnBreaks() {
        List<ColumnContract.Change> ch = ColumnContract.classify(List.of(c("A", "VARCHAR"), c("B", "INTEGER")),
                List.of(c("A", "VARCHAR")));
        assertEquals(List.of("removed B"), classes(List.of(c("A", "VARCHAR"), c("B", "INTEGER")), List.of(c("A", "VARCHAR"))));
        assertTrue(ch.get(0).breaking());
    }

    @Test
    void oneRemovedAndOneAddedOfTheSameTypeInTheSamePositionIsARename() {
        assertEquals(List.of("renamed B->B2"), classes(
                List.of(c("A", "VARCHAR"), c("B", "INTEGER"), c("C", "DATE")),
                List.of(c("A", "VARCHAR"), c("B2", "INTEGER"), c("C", "DATE"))));
        assertTrue(ColumnContract.classify(List.of(c("B", "")), List.of(c("B2", ""))).get(0).breaking(),
                "two unknown types count as the same type");
    }

    @Test
    void theRenameHeuristicNeedsTheSameTypeAndTheSamePosition() {
        assertEquals(List.of("removed B", "added B2"), classes(
                List.of(c("A", "VARCHAR"), c("B", "INTEGER")), List.of(c("A", "VARCHAR"), c("B2", "VARCHAR"))),
                "another type: a removal and an addition");
        assertEquals(List.of("removed B", "added B2"), classes(
                List.of(c("A", "VARCHAR"), c("B", "INTEGER")), List.of(c("B2", "INTEGER"), c("A", "VARCHAR"))),
                "another position: a removal and an addition");
    }

    @Test
    void aTypeChangeOutsideTheWideningLatticeIsNarrowed() {
        assertEquals(List.of("type-narrowed N BIGINT->INTEGER"),
                classes(List.of(c("N", "BIGINT")), List.of(c("N", "INTEGER"))));
        assertEquals(List.of("type-narrowed S VARCHAR->INTEGER"),
                classes(List.of(c("S", "VARCHAR")), List.of(c("S", "INTEGER"))));
        assertTrue(ColumnContract.classify(List.of(c("N", "BIGINT")), List.of(c("N", "INTEGER"))).get(0).breaking());
    }

    @Test
    void aWideningIsSafeAndUnknownTypesAndCaseAreNoChange() {
        List<ColumnContract.Change> w = ColumnContract.classify(List.of(c("N", "INTEGER")), List.of(c("N", "BIGINT")));
        assertEquals("type-widened", w.get(0).change());
        assertFalse(w.get(0).breaking());
        assertEquals(List.of(), classes(List.of(c("N", "")), List.of(c("N", "INTEGER"))));
        assertEquals(List.of(), classes(List.of(c("region", "VARCHAR")), List.of(c("REGION", "VARCHAR"))),
                "DuckDB binds every consumer case-insensitively");
    }

    // ── the published set ────────────────────────────────────────────────────────

    @Test
    void theSetIsTheMappingFieldsTypedFromRawForKeep() {
        Map<String, Object> schema = Map.of(
                "raw", Map.of("fields", List.of(Map.of("name", "ID", "type", "BIGINT"), Map.of("name", "R", "type", "VARCHAR"))),
                "mapping", Map.of("fields", List.of(
                        Map.of("name", "ID", "from", "ID", "fn", "keep"),
                        Map.of("name", "REGION", "from", "", "fn", "custom"),
                        Map.of("name", "AMT", "type", "decimal(18,2)"))));
        assertEquals(List.of(c("ID", "BIGINT"), c("REGION", ""), c("AMT", "DECIMAL(18,2)")), ColumnContract.columns(schema));
        assertNull(ColumnContract.columns(Map.of("mapping", Map.of("rules", List.of(Map.of("a", "b"))))),
                "a rule-built mapping is unknown, as the census keeps it");
        assertNull(ColumnContract.columns(Map.of("raw", Map.of())), "no mapping.fields: unknown");
    }

    // ── the lock section over the demo Space ─────────────────────────────────────

    /** What reads the demo's mapped GROSS (the LC-5 census, ControlApiColumnImpactTest). */
    static final List<String> GROSS_CONSUMERS = List.of(
            "enrichment orders_daily_enrich transform",
            "expectation orders_gross_non_null column",
            "decision-rule orders_high_value_review when.items[0].field",
            "dataset orders_enriched_dataset calculated[0].expr",
            "widget orders_daily_trend controls.y[0].field",
            "widget orders_revenue_by_region controls.y[0].field",
            "reconciliation orders_regional_recon compareColumns[0].column");

    /** A copy of {@code spaces/demo/config} (no {@code .history}) at {@code <root>/demo/config}. */
    static Path demoSpace(Path root) throws Exception {
        Path src = ReactorModules.root().resolve("spaces/demo/config");
        Path config = Files.createDirectories(root.resolve("demo/config"));
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                Path rel = src.relativize(p);
                if (rel.toString().replace('\\', '/').contains(".history")) continue;
                Path to = config.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> section(SpaceLock.Report r) {
        return (Map<String, Object>) r.body().get("columnChanges");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(SpaceLock.Report r) {
        return (List<Map<String, Object>>) section(r).get("items");
    }

    @SuppressWarnings("unchecked")
    static List<String> holders(Object consumers) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) consumers)
            out.add(m.get("fromKind") + " " + m.get("fromName") + " " + m.get("key"));
        return out;
    }

    @Test
    void theLockRecordsEachSchemaSetAndAHandRenameIsReportedWithItsConsumers(@TempDir Path root) throws Exception {
        Path config = demoSpace(root);
        SpaceLock.Report first = SpaceLock.compute("demo", config, List.of());
        assertEquals("none", section(first).get("baseline"));
        assertEquals(0, section(first).get("total"), "a first computation is a baseline: nothing changed yet");
        Map<?, ?> lock = new ObjectMapper().readValue(SpaceLock.file(config).toFile(), Map.class);
        Map<?, ?> orders = ((List<Map<?, ?>>) lock.get("columns")).stream()
                .filter(m -> "orders/orders_schema.toon".equals(m.get("path"))).findFirst().orElseThrow();
        assertTrue(orders.get("columns").toString().contains("name=PRODUCT, type=VARCHAR"), orders.toString());
        assertEquals(new TreeSet<>(GROSS_CONSUMERS), new TreeSet<>(holders(((Map<?, ?>) orders.get("consumers")).get("gross"))));

        // the hand rename, made outside the server
        Path schema = config.resolve("orders/orders_schema.toon");
        String text = Files.readString(schema);
        assertTrue(text.contains("    - name: GROSS\n"), "the demo schema changed shape — re-aim the plant");
        Files.writeString(schema, text.replace("    - name: GROSS\n", "    - name: GROSS_AMOUNT\n"));
        SpaceLock.Report second = SpaceLock.compute("demo", config, List.of());
        assertEquals("lock", section(second).get("baseline"));
        List<Map<String, Object>> it = items(second);
        assertEquals(1, it.size(), "only the renamed column changed: " + it);
        Map<String, Object> rename = it.get(0);
        assertEquals("orders/orders_schema.toon", rename.get("schema"));
        assertEquals("renamed", rename.get("change"));
        assertEquals("GROSS", rename.get("column"));
        assertEquals("GROSS_AMOUNT", rename.get("to"));
        assertEquals(true, rename.get("breaking"));
        assertEquals(true, rename.get("outside"), "no server write window: a hand edit");
        assertEquals(new TreeSet<>(GROSS_CONSUMERS), new TreeSet<>(holders(rename.get("consumers"))));
        assertEquals(1, section(second).get("breaking"));

        // carried forward while the set is unchanged; a consumer fixed by hand drops out
        Path exp = config.resolve("registry/expectations/orders_gross_non_null.toon");
        assertTrue(Files.isRegularFile(exp), "the demo expectation moved — re-aim the plant");
        String e = Files.readString(exp);
        assertTrue(e.contains("GROSS"), e);
        Files.writeString(exp, e.replace("GROSS", "GROSS_AMOUNT"));
        SpaceLock.Report third = SpaceLock.compute("demo", config, List.of());
        assertEquals(1, items(third).size());
        List<String> left = new ArrayList<>(GROSS_CONSUMERS);
        left.remove("expectation orders_gross_non_null column");
        assertEquals(new TreeSet<>(left), new TreeSet<>(holders(items(third).get(0).get("consumers"))));
    }

    @Test
    void aHandRemovalIsReportedAndAnAdditionIsSafe(@TempDir Path root) throws Exception {
        Path config = demoSpace(root);
        SpaceLock.compute("demo", config, List.of());
        Path schema = config.resolve("orders/orders_schema.toon");
        String text = Files.readString(schema);
        int at = text.indexOf("    - name: GROSS\n");
        assertTrue(at > 0 && text.indexOf("    - name: ", at + 5) < 0 && text.trim().endsWith("2)\""),
                "the demo schema changed shape (GROSS is its last block) — re-aim the plant");
        // GROSS (type unknown) removed; NOTE_X takes its position with a declared type: not the same type, so the
        // rename heuristic does not pair them
        String fields = text.substring(0, at)
                + "    - name: NOTE_X\n      type: VARCHAR\n      from: \"\"\n      fn: custom\n      args:\n        expression: \"'x'\"\n";
        Files.writeString(schema, fields);
        assertNotNull(ColumnContract.columns(schema), "the edited schema must still be readable:\n" + fields);

        SpaceLock.Report r = SpaceLock.compute("demo", config, List.of());
        List<String> got = new ArrayList<>();
        for (Map<String, Object> m : items(r)) got.add(m.get("change") + " " + m.get("column"));
        assertEquals(List.of("removed GROSS", "added NOTE_X"), got);
        assertEquals(new TreeSet<>(GROSS_CONSUMERS), new TreeSet<>(holders(items(r).get(0).get("consumers"))));
        assertEquals(List.of(), items(r).get(1).get("consumers"));
        assertEquals(false, items(r).get(1).get("breaking"));
    }

    @Test
    void theKillSwitchRemovesTheSection(@TempDir Path root) throws Exception {
        Path config = demoSpace(root);
        System.setProperty(ColumnContract.PROPERTY, "off");
        SpaceLock.Report r = SpaceLock.compute("demo", config, List.of());
        assertEquals("disabled", section(r).get("status"));
        Map<?, ?> lock = new ObjectMapper().readValue(SpaceLock.file(config).toFile(), Map.class);
        assertFalse(lock.containsKey("columns"), "off: nothing recorded");
        // on again: a fresh baseline, never a flood of changes
        System.clearProperty(ColumnContract.PROPERTY);
        SpaceLock.Report on = SpaceLock.compute("demo", config, List.of());
        assertEquals("none", section(on).get("baseline"));
        assertEquals(0, section(on).get("total"));
    }
}
