package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code processing.output_per_member} end to end through {@link CollectorProcessor#run} over the delimited-CSV
 * lanes (the Java parse loop, the native single-member stream, the native multi-member UNION, the native CHUNKED
 * path): with it on every member lands in its own {@code <stem>__<hash>} file whichever lane ran it; with it off a
 * multi-member UNION batch still lands ONE file named by the batch id.
 */
class OutputPerMemberEndToEndTest {

    private static final String GOOD = "ID,AMT,EVENT_DATE\ng1,3.0,2020-04-03\ng2,4.0,2020-04-03\n";

    private static PipelineConfig pipeline(Path dir, String path, boolean perMember) throws Exception {
        String engine = path.startsWith("java") ? "java" : "duckdb";
        String section = (perMember ? "  output_per_member: true\n" : "")
                + switch (path) {
                    case "duckdb-union", "java-union" -> "  batch:\n    max_files: 10\n";
                    case "duckdb-chunked" -> "  chunking:\n    max_file_bytes: 30\n    target_chunk_bytes: 30\n";
                    default -> "";
                };
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, section);
        Files.writeString(toon, Files.readString(toon).replace(
                "    delimiter: \",\"", "    delimiter: \",\"\n    engine: " + engine));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(Path.of(cfg.dirs().poll()).resolve("a.csv"), GOOD);
        Files.writeString(Path.of(cfg.dirs().poll()).resolve("b.csv"), GOOD.replace("g", "h"));
        return cfg;
    }

    private static List<Path> outputs(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            return w.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static long rows(List<Path> files) throws Exception {
        long n = 0;
        for (Path p : files) n += Files.readAllLines(p).size() - 1;
        return n;
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb", "duckdb-union", "duckdb-chunked"})
    void everyMemberLandsInItsOwnNamedFile(String path, @TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, path, true);
        CollectorProcessor.run(cfg);
        List<Path> files = outputs(cfg);
        assertEquals(4, rows(files), path + ": " + files);
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().matches("a__[0-9a-f]{8}_.*")), path + ": " + files);
        assertTrue(files.stream().anyMatch(p -> p.getFileName().toString().matches("b__[0-9a-f]{8}_.*")), path + ": " + files);
        assertTrue(files.stream().allMatch(p -> p.getFileName().toString().matches("[ab]__[0-9a-f]{8}_.*")),
                path + ": no output is named by a batch id: " + files);
    }

    @ParameterizedTest
    @ValueSource(strings = {"java-union", "duckdb-union"})
    void offStillLandsOneBatchNamedFile(String path, @TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, path, false);
        CollectorProcessor.run(cfg);
        List<Path> files = outputs(cfg);
        assertEquals(1, files.size(), path + ": " + files);
        assertEquals(4, rows(files));
        assertFalse(files.get(0).getFileName().toString().contains("__"), files.toString());
    }
}
