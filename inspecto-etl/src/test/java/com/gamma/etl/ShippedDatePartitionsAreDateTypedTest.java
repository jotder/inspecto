package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No shipped Pipeline cuts a {@code DATE_*} partition from a field its schema declares non-date
 * ({@code DATE-PARTITION-ON-TEXT-SHIPPED-1}). {@code excel_example} ({@code CATEGORY}), {@code asn1_example}
 * ({@code IMSI}), {@code orders_by_region_feed} ({@code REGION}) and two {@code inspecto/examples} did, and each
 * loaded and ran clean with every row under {@code __HIVE_DEFAULT_PARTITION__}. The rule is
 * {@link ConfigValidator}'s; this sweep runs it over every shipped Space and example so the shape cannot ship
 * again.
 *
 * <p>Each tree is copied to a temp dir before loading (loading creates status dirs under the Space), as
 * {@link ShippedPipelinesLoadFromAnyWorkingDirectoryTest} does.
 */
class ShippedDatePartitionsAreDateTypedTest {

    /** Runtime state (gitignored) — not shipped. */
    private static final Set<String> NOT_SHIPPED_SPACES = Set.of("_shared", "uat");

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void noShippedPipelineDatePartitionsAFieldDeclaredNonDate(@TempDir Path tmp) throws Exception {
        List<Path> pipelines = new ArrayList<>();
        try (Stream<Path> spaces = Files.list(REPO.resolve("spaces"))) {
            for (Path space : spaces.filter(Files::isDirectory).toList()) {
                String name = space.getFileName().toString();
                if (NOT_SHIPPED_SPACES.contains(name) || !Files.isDirectory(space.resolve("config"))) continue;
                pipelines.addAll(copyTree(space.resolve("config"), tmp.resolve("spaces").resolve(name).resolve("config")));
            }
        }
        pipelines.addAll(copyTree(REPO.resolve("inspecto/examples"), tmp.resolve("examples")));
        assertTrue(pipelines.size() >= 50, "found only " + pipelines.size()
                + " pipelines (60 when this landed) — the walk is broken");

        List<String> hits = new ArrayList<>();
        int loaded = 0;
        for (Path p : pipelines) {
            PipelineConfig cfg;
            try {
                cfg = PipelineConfig.load(p.toString());
            } catch (Exception | Error notLoadable) {
                continue;   // loading is ShippedPipelinesLoadFromAnyWorkingDirectoryTest's claim, not this one's
            }
            loaded++;
            for (String w : ConfigValidator.validate(cfg))
                if (w.contains("DATE partition source")) hits.add(tmp.relativize(p) + ": " + w);
        }
        assertTrue(loaded >= 50, "only " + loaded + " of " + pipelines.size() + " loaded — the sweep proves nothing");
        assertTrue(hits.isEmpty(), hits.size() + " shipped DATE partition(s) over a non-date field:\n"
                + String.join("\n", hits));
    }

    /** Copy a tree verbatim; returns the copied {@code pipeline.toon} / {@code *_pipeline.toon} files. */
    private static List<Path> copyTree(Path from, Path into) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> all = Files.walk(from)) {
            for (Path f : all.filter(Files::isRegularFile).toList()) {
                Path dest = into.resolve(from.relativize(f));
                Files.createDirectories(dest.getParent());
                Files.copy(f, dest);
                String n = f.getFileName().toString();
                if (n.equals("pipeline.toon") || n.endsWith("_pipeline.toon")) out.add(dest);
            }
        }
        return out;
    }
}
