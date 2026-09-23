package com.gamma.acquire.connectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reads the first column of every data row across a Pipeline's {@code *_out.csv} outputs, sorted. */
final class OutputRows {
    private OutputRows() {}

    static List<String> ids(Path databaseDir) throws IOException {
        List<String> ids = new ArrayList<>();
        if (!Files.isDirectory(databaseDir)) return ids;              // no output at all = every row dropped
        try (Stream<Path> w = Files.walk(databaseDir)) {
            for (Path f : w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (String line : lines.subList(1, lines.size()))     // line 0 is the output header
                    if (!line.isBlank()) ids.add(line.split(",", -1)[0].replace("\"", ""));
            }
        }
        ids.sort(null);
        return ids;
    }
}
