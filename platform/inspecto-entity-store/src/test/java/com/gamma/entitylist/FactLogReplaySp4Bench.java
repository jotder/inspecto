package com.gamma.entitylist;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * MEASUREMENT harness, not a test (SP4 / risk R-09): what one read of the identity fact log costs at 10^4, 10^5, 10^6 facts —
 * the REAL {@link EntityFactLog#read} (list directory, read every file, parse JSON, SHA-256, check the chain) — then the fold
 * ({@link EntityRegistry#fold}) and the head-hash cache prototype ({@link Sp4FactLogs.HeadCached}). Run with
 * <pre>mvn -o test -pl :inspecto-entity-store -am -Dtest=FactLogReplaySp4Bench -Dsurefire.failIfNoSpecifiedTests=false
 *   -Dbench.run=true -DargLine="--enable-native-access=ALL-UNNAMED -Xmx6g"</pre>
 * Optional {@code -Dbench.dir=<dir>} (default {@code target/sp4-bench}) and {@code -Dbench.facts=10000,100000,1000000}.
 * ⚠ The files are written just before they are read, so the OS file cache is WARM: a cold-disk read is slower than any number
 * here. The log is generated (not appended) because {@code append} is O(n) per fact and fsyncs.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "bench.run", matches = "true")
class FactLogReplaySp4Bench {

    @Test
    void replayCost() throws Exception {
        Path dir = Path.of(System.getProperty("bench.dir", "target/sp4-bench")).toAbsolutePath();
        List<String> out = new ArrayList<>();
        say(out, "facts | gen s | list-only ms (min/med) | read() ms (min/med) | fold ms (min/med) | head-cache hit ms (min/med) | used heap MB after read");
        for (String n : System.getProperty("bench.facts", "10000,100000,1000000").split(",")) {
            int facts = Integer.parseInt(n.trim());
            Path root = dir.resolve("factlog_" + facts);
            if (Files.isDirectory(root)) delete(root);
            long g = System.nanoTime();
            Sp4FactLogs.generate(root, facts);
            double genS = (System.nanoTime() - g) / 1e9;
            EntityFactLog log = new EntityFactLog(root);

            double[] list = new double[3], read = new double[3], fold = new double[3], hit = new double[3];
            EntityFactLog.Log last = null;
            for (int i = 0; i < 3; i++) {
                long t = System.nanoTime();
                try (Stream<Path> s = Files.list(log.directory())) { s.count(); }
                list[i] = ms(t);
                t = System.nanoTime();
                last = log.read();
                read[i] = ms(t);
                t = System.nanoTime();
                EntityRegistry.fold(last.facts(), last.headSeq());
                fold[i] = ms(t);
            }
            Runtime rt = Runtime.getRuntime();
            System.gc();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
            Sp4FactLogs.HeadCached cache = new Sp4FactLogs.HeadCached(log);
            cache.read();
            for (int i = 0; i < 3; i++) {
                long t = System.nanoTime();
                cache.read();
                hit[i] = ms(t);
            }
            say(out, "%d | %.1f | %s | %s | %s | %s | %d".formatted(facts, genS, mm(list), mm(read), mm(fold), mm(hit), usedMb));
            last = null;
            delete(root);
        }
        Files.write(dir.resolve("sp4-factlog-results.txt"), out);
    }

    private static double ms(long since) {
        return (System.nanoTime() - since) / 1e6;
    }

    private static String mm(double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return "%.1f/%.1f".formatted(s[0], s[1]);
    }

    private static void delete(Path root) throws IOException {
        try (Stream<Path> w = Files.walk(root)) {
            w.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void say(List<String> out, String line) {
        out.add(line);
        System.out.println("[SP4-LOG] " + line);
    }
}
