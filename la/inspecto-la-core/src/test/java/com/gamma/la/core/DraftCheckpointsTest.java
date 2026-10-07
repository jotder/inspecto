package com.gamma.la.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D7-4 - the Draft checkpoint: resume == full fold, no whole-log fold per op, and a changed file is never served from cache. */
class DraftCheckpointsTest {

    private static Map<String, Object> op(int step, String op, Map<String, Object> params) {
        return Map.of("step", step, "kind", "op", "op", op, "params", params);
    }

    private static Map<String, Object> undo(int step, int undoes) {
        return Map.of("step", step, "kind", "undo", "undoes", undoes);
    }

    private static void write(Path dir, List<Map<String, Object>> log) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> e : log) b.append(InvestigationEvaluator.canonical(e)).append('\n');
        Files.writeString(dir.resolve("log.jsonl"), b.toString());
    }

    private static List<Map<String, Object>> script() {
        return List.of(
                op(1, "seed", Map.of("ids", List.of("a", "b", "c"))),
                op(2, "hide", Map.of("ids", List.of("b"))),
                op(3, "keep", Map.of("ids", List.of("c"))),
                op(4, "annotate", Map.of("ids", List.of("a"), "note", "n", "confidence", "B2")),
                op(5, "window", Map.of("window", Map.of("from", "2026-01-01"))),
                op(6, "exclude", Map.of("ids", List.of("b"), "reason", "r")),
                undo(7, 6),
                op(8, "seed", Map.of("ids", List.of("d"))));
    }

    @Test
    void copyIsIndependentOfTheOriginal() {
        InvestigationEvaluator.State s = new InvestigationEvaluator.State();
        InvestigationEvaluator.apply(s, script().get(0));
        InvestigationEvaluator.apply(s, script().get(3));
        String before = s.hash();
        InvestigationEvaluator.State c = s.copy();
        assertEquals(before, c.hash());
        InvestigationEvaluator.apply(c, script().get(1));   // hide
        InvestigationEvaluator.apply(c, op(9, "annotate", Map.of("ids", List.of("a"), "note", "second")));
        assertEquals(before, s.hash(), "applying to the copy must not show in the original");
        assertNotEquals(before, c.hash());
    }

    @Test
    void resumingFromTheCheckpointEqualsAFullFoldAtEveryStep(@TempDir Path dir) throws IOException {
        List<Map<String, Object>> full = script();
        List<Map<String, Object>> log = new ArrayList<>();
        for (Map<String, Object> entry : full) {
            InvestigationEvaluator.State before = DraftCheckpoints.stateOf(dir, log);
            InvestigationEvaluator.State after = DraftCheckpoints.after(before, log, entry);
            log.add(entry);
            write(dir, log);
            DraftCheckpoints.remember(dir, log.size(), after);
            assertEquals(InvestigationEvaluator.evaluate(log, -1, null).hash(), after.hash(), "step " + entry.get("step"));
        }
        List<String> replay = new ArrayList<>();
        InvestigationEvaluator.evaluate(log, -1, replay);
        assertEquals(replay.get(replay.size() - 1), DraftCheckpoints.stateOf(dir, log).hash());
    }

    @Test
    void appendingOpsAfterTheFirstDoesNotFoldTheLogAgain(@TempDir Path dir) throws IOException {
        List<Map<String, Object>> log = new ArrayList<>();
        DraftCheckpoints.stateOf(dir, log);   // first look: the one fold a cold checkpoint costs
        long folds = InvestigationEvaluator.foldCount();
        for (int i = 1; i <= 20; i++) {
            Map<String, Object> entry = op(i, "seed", Map.of("ids", List.of("e" + i)));
            InvestigationEvaluator.State before = DraftCheckpoints.stateOf(dir, log);
            InvestigationEvaluator.State after = DraftCheckpoints.after(before, log, entry);
            log.add(entry);
            write(dir, log);
            DraftCheckpoints.remember(dir, log.size(), after);
        }
        assertEquals(folds, InvestigationEvaluator.foldCount(), "20 appends must not fold the log");
    }

    @Test
    void aLogThatChangedOnDiskIsNotServedFromTheCheckpoint(@TempDir Path dir) throws IOException {
        List<Map<String, Object>> log = new ArrayList<>(script().subList(0, 3));
        write(dir, log);
        InvestigationEvaluator.State s = DraftCheckpoints.stateOf(dir, log);
        long folds = InvestigationEvaluator.foldCount();
        assertEquals(s.hash(), DraftCheckpoints.stateOf(dir, log).hash());
        assertEquals(folds, InvestigationEvaluator.foldCount(), "an untouched log is a hit");
        write(dir, new ArrayList<>(script().subList(0, 2)));   // someone rewrote the file
        log.remove(2);
        DraftCheckpoints.stateOf(dir, log);
        assertTrue(InvestigationEvaluator.foldCount() > folds, "a changed file is a miss: it re-folds");
    }

    @Test
    void theBaseVerdictIsCachedPerMainFileAndRecheckedWhenItChanges(@TempDir Path dir) throws IOException {
        Path main = dir.resolve("log.jsonl");
        List<Map<String, Object>> log = new ArrayList<>(script().subList(0, 2));
        write(dir, log);
        List<String> lines = Files.readAllLines(main);
        String hash = DraftStore.prefixHash(lines, 2);
        AtomicInteger reads = new AtomicInteger();
        java.util.function.Supplier<List<String>> read = () -> {
            reads.incrementAndGet();
            try {
                return Files.readAllLines(main);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        assertTrue(DraftCheckpoints.base(main, 2, hash, read).intact());
        assertTrue(DraftCheckpoints.base(main, 2, hash, read).intact());
        assertEquals(1, reads.get(), "an unchanged main log is read and hashed once");
        // the main log grows (size changes): re-verified, prefix still intact
        log.add(script().get(2));
        write(dir, log);
        assertTrue(DraftCheckpoints.base(main, 2, hash, read).intact());
        assertEquals(2, reads.get());
        // the prefix is rewritten (size changes): fail closed
        Files.writeString(main, Files.readString(main).replaceFirst("\"a\"", "\"aa\""));
        assertFalse(DraftCheckpoints.base(main, 2, hash, read).intact());
        assertEquals(3, reads.get());
    }
}
