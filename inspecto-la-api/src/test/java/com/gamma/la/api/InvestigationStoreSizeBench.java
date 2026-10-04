package com.gamma.la.api;

import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationEvaluator.Entity;
import com.gamma.la.core.InvestigationEvaluator.Link;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * InvestigationStore design S0 (docs/superpower/investigation-store-design.md section 13): how big is a sealed Working Set
 * file, how much does a log of N steps cost, how long does reading and prefix-hashing a 2,000-step log take on the filesystem.
 * Never runs in the default suite: needs {@code -Dinspecto.bench.s0=true}. Results print as {@code S0 ...} lines.
 * Run: {@code mvn -o -Pedition-enterprise -pl inspecto-la-api -am test -Dtest=InvestigationStoreSizeBench
 * -Dinspecto.bench.s0=true -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.s0", matches = "true")
class InvestigationStoreSizeBench {

    private static InvestigationEvaluator.State state(int entities, int linksPerEntity) {
        var s = new InvestigationEvaluator.State();
        for (int i = 0; i < entities; i++) {
            String id = "447700" + String.format("%06d", i);
            s.entities.put(id, new Entity(id, "msisdn", 1 + i % 3, "447700000000", 1 + i % 50));
        }
        for (int i = 0; i < entities; i++)
            for (int k = 1; k <= linksPerEntity; k++) {
                String a = "447700" + String.format("%06d", i), b = "447700" + String.format("%06d", (i + k * 7919) % entities);
                s.links.put(a + '\u0000' + b + "\u0000call", new Link(a, b, "call", 1 + i % 9, 1 + i % 50));
            }
        return s;
    }

    @Test
    void measure() throws Exception {
        for (int n : new int[]{1_000, 10_000, 100_000}) {
            var s = state(n, 2);
            byte[] set = canonical(InvestigationRoutes.setDoc(1, s)).getBytes(StandardCharsets.UTF_8);
            System.out.printf("S0 set: entities=%,d links=%,d -> %,d bytes (%.1f MB, %.0f B/entity)%n", n, s.links.size(),
                    set.length, set.length / 1e6, (double) set.length / n);
        }
        // O(state x steps): a state that grows linearly to N over S steps; total = sum of every step's set file (20 samples, scaled)
        for (int[] c : new int[][]{{10_000, 100}, {10_000, 2_000}, {100_000, 100}}) {
            int n = c[0], steps = c[1];
            long total = 0, last = 0;
            int every = Math.max(1, steps / 20);
            for (int st = every; st <= steps; st += every) {
                int cur = Math.max(1, (int) ((long) n * st / steps));
                last = canonical(InvestigationRoutes.setDoc(st, state(cur, 2))).getBytes(StandardCharsets.UTF_8).length;
                total += last * every;
            }
            System.out.printf("S0 total: entities->%,d over %,d steps ~ %,.0f MB of set files per Investigation (last set %,.1f MB)%n",
                    n, steps, total / 1e6, last / 1e6);
        }
        // log lines: an expand sealing R rows
        for (int rows : new int[]{50, 500, 5_000}) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("step", 1); e.put("kind", "op"); e.put("op", "expand"); e.put("author", "analyst"); e.put("at", "2026-10-04T10:00:00Z");
            List<Map<String, Object>> rs = new ArrayList<>();
            for (int i = 0; i < rows; i++)
                rs.add(Map.of("source", "447700" + String.format("%06d", i), "target", "447700" + String.format("%06d", i + 1), "kind", "call", "count", 3));
            e.put("read", Map.of("rows", rs, "query", Map.of("frontier", List.of("447700000000"))));
            e.put("workingSetHash", "sha256:" + "0".repeat(64));
            System.out.printf("S0 line: expand sealing %,d rows -> %,d bytes%n", rows, canonical(e).getBytes(StandardCharsets.UTF_8).length);
        }
        // reading a 2,000-step log from the filesystem, and prefixHash over it (D-IS6)
        Path root = Files.createTempDirectory("s0-");
        var store = new com.gamma.la.core.FsInvestigationStore(root);
        store.create("s0", "{\"id\":\"s0\"}");
        for (int i = 1; i <= 2_000; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("step", i); e.put("kind", "op"); e.put("op", "expand"); e.put("author", "analyst"); e.put("at", "2026-10-04T10:00:00Z");
            List<Map<String, Object>> rs = new ArrayList<>();
            for (int k = 0; k < 50; k++) rs.add(Map.of("source", "a" + k, "target", "b" + k, "kind", "call", "count", 3));
            e.put("read", Map.of("rows", rs));
            e.put("workingSetHash", "sha256:" + "0".repeat(64));
            store.append(com.gamma.la.core.InvestigationStore.Scope.main("s0"), i - 1, i, canonical(e), "{}");
        }
        long[] read = new long[31], hash = new long[31];
        for (int r = 0; r < 31; r++) {
            long t = System.nanoTime();
            List<String> lines = store.log(com.gamma.la.core.InvestigationStore.Scope.main("s0"));
            read[r] = System.nanoTime() - t;
            t = System.nanoTime();
            DraftStore.prefixHash(lines, lines.size());
            hash[r] = System.nanoTime() - t;
        }
        Arrays.sort(read);
        Arrays.sort(hash);
        long bytes = Files.size(store.investigationDir("s0").resolve("log.jsonl"));
        System.out.printf("S0 read: 2,000-step log (%,d bytes) FS readLog p50=%.1f ms; prefixHash p50=%.1f ms%n", bytes, read[15] / 1e6, hash[15] / 1e6);
        // NUL inside a string value: canonical JSON must write the ASCII escape, never a raw 0x00
        String withNul = canonical(Map.of("v", "a\u0000b"));
        System.out.printf("S0 nul: canonical(a<NUL>b) = %s ; raw NUL char present = %b%n", withNul, withNul.indexOf('\u0000') >= 0);
    }
}
