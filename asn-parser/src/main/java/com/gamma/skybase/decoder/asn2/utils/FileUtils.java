package com.gamma.skybase.decoder.asn2.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class FileUtils {

    private FileUtils() {
        // Private constructor for utility class
    }

    public static List<Path> findFiles(String dir, String date, String namePattern, int depth) throws IOException {
        Objects.requireNonNull(dir, "Directory must not be null");

        Path startPath = Paths.get(dir);

        BiPredicate<Path, BasicFileAttributes> predicate = (path, attrs) -> attrs.isRegularFile();

        if (namePattern != null && !namePattern.isEmpty()) {
            predicate = predicate.and((path, attrs) -> RegexUtils.matches(path.getFileName().toString(), namePattern));
        }

        if (date != null && !date.isEmpty()) {
            predicate = predicate.and((path, attrs) -> DateTimeUtils.isDateMatch(attrs.lastModifiedTime().toMillis() / 1000, date));
        }

        try (Stream<Path> pathStream = Files.find(startPath, depth, predicate)) {
            return pathStream.collect(Collectors.toList());
        }
    }
}
