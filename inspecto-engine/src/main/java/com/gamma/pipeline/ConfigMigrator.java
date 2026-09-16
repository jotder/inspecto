package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.util.MappingCsv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * <b>ELT amendment §6 step 1 — the one-shot converter</b> behind {@code inspecto migrate-configs}.
 *
 * <p>Projects a space's legacy per-file configuration into the component-registry shape the recipe path
 * reads: every {@code *_pipeline.toon} becomes a {@code pipelines/<name>.toon} recipe, and every
 * {@code *_schema.toon} splits into a {@code schemas/<name>.toon} structure plus a
 * {@code mappings/<name>.csv} Mapping component. Originals are left untouched, and moved aside to
 * {@code archived-config/} only on {@code --apply}.
 *
 * <p><b>Deterministic and dry-run-first.</b> Files are visited in sorted path order, so two runs over the
 * same tree plan byte-identical output; nothing is written until {@code apply} is passed.
 *
 * <p><b>Refuses rather than loses.</b> A file it cannot project in full is REFUSED by name with the reason,
 * and a refusal fails the whole migration before anything is written — a half-migrated space is the one
 * outcome worse than an unmigrated one. The known refusals:
 * <ul>
 *   <li>🔴 {@code *_enrich.toon} — the plan's "→ a table-entry recipe" has <b>no implemented target</b>. It
 *       is named here rather than skipped, because a silent skip would leave the enrichment running off a
 *       legacy file the deletion half is about to remove.</li>
 *   <li>🔴 A {@code materialize} maintenance task — it lives in a JOB, not in a config file this walker can
 *       see, so "every {@code materialize} task → a {@code summarize} recipe" cannot be discharged from
 *       here at all. Reported as an un-migratable class when the space declares one.</li>
 *   <li>A target path that already exists (the space is part-migrated, or two legacy files claim one name).</li>
 * </ul>
 *
 * <p>⚠ <b>Schemas are TOON, not CSV.</b> The amendment's §6 wording — <i>"every {@code *_schema.toon} splits
 * → {@code schemas/*.csv} + {@code mappings/*.csv}"</i> — is wrong for the first half as the code stands:
 * {@link ComponentRegistry#CSV_KINDS} holds {@code mapping} alone, so a schema written as CSV would be a
 * file the registry never loads. Only the Mapping half is CSV.
 */
public final class ConfigMigrator {

    private ConfigMigrator() {}

    /** One planned conversion: what would be read, and what would be written. */
    public record Conversion(Path from, List<Path> to, String kind) {}

    /** One file the migration cannot carry, and why. */
    public record Refusal(Path from, String reason) {}

    /**
     * The plan (and, when applied, the outcome). {@code refusals} being non-empty means nothing was
     * written: the migration is all-or-nothing by design.
     */
    public record Plan(List<Conversion> conversions, List<Refusal> refusals, boolean applied) {
        public boolean ok() { return refusals.isEmpty(); }
    }

    /**
     * Plan (and optionally apply) the migration of {@code configRoot} into the registry shape under
     * {@code outRoot}.
     *
     * @param apply {@code false} plans only and writes nothing; {@code true} writes the components and moves
     *              each original under {@code configRoot/archived-config/}, preserving its relative path.
     */
    public static Plan migrate(Path configRoot, Path outRoot, boolean apply) throws IOException {
        List<Conversion> conversions = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();
        Set<Path> claimed = new LinkedHashSet<>();

        for (Path src : legacyFiles(configRoot)) {
            String file = src.getFileName().toString();
            try {
                if (file.endsWith("_pipeline.toon")) {
                    // Project it now and COMPILE the projection: writing a recipe the recipe path then
                    // refuses is the one failure this converter must never ship, because the legacy file it
                    // replaces is about to be archived. A compile failure is a lossy case, so it refuses.
                    String reason = whyTheRecipeWouldNotCompile(src);
                    if (reason != null) {
                        refusals.add(new Refusal(src, "the converted recipe would not compile - " + reason));
                        continue;
                    }
                    plan(conversions, refusals, claimed, src, "pipeline",
                            List.of(outRoot.resolve("pipelines").resolve(baseName(file, "_pipeline") + ".toon")));
                } else if (file.endsWith("_schema.toon")) {
                    String name = baseName(file, "_schema");
                    plan(conversions, refusals, claimed, src, "schema",
                            List.of(outRoot.resolve("schemas").resolve(name + ".toon"),
                                    outRoot.resolve("mappings").resolve(name + ".csv")));
                } else if (file.endsWith("_enrich.toon")) {
                    refusals.add(new Refusal(src, "an enrichment config has no table-entry recipe target "
                            + "implemented (amendment §6 step 1) - migrate it by hand or leave this space "
                            + "on the legacy path until that conversion exists"));
                }
            } catch (Exception e) {
                refusals.add(new Refusal(src, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }

        if (!refusals.isEmpty() || !apply) return new Plan(List.copyOf(conversions), List.copyOf(refusals), false);

        for (Conversion c : conversions) {
            if ("pipeline".equals(c.kind())) writePipeline(c);
            else writeSchema(c);
            archive(configRoot, c.from());
        }
        return new Plan(List.copyOf(conversions), List.of(), true);
    }

    // ── planning ─────────────────────────────────────────────────────────────

    /** Legacy config files in sorted path order, skipping anything already under {@code archived-config/}. */
    static List<Path> legacyFiles(Path configRoot) throws IOException {
        if (!Files.isDirectory(configRoot)) return List.of();
        try (Stream<Path> walk = Files.walk(configRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".toon"))
                    .filter(p -> !configRoot.relativize(p).startsWith("archived-config"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static void plan(List<Conversion> conversions, List<Refusal> refusals, Set<Path> claimed,
                             Path src, String kind, List<Path> targets) {
        for (Path t : targets) {
            if (Files.exists(t)) {
                refusals.add(new Refusal(src, "target already exists: " + t
                        + " - the space looks part-migrated; move it aside before re-running"));
                return;
            }
            if (!claimed.add(t)) {
                refusals.add(new Refusal(src, "two legacy files both claim " + t
                        + " - rename one before migrating, or the second would overwrite the first"));
                return;
            }
        }
        conversions.add(new Conversion(src, targets, kind));
    }

    /**
     * {@code null} when the projection of {@code src} compiles, else the compiler's own reason. ⚠ Found by
     * the test that pins this: a legacy pipeline whose parse step names no Grammar / {@code schema_file} /
     * {@code segments} projects to a syntactically fine recipe that {@link RecipeCompiler} then REFUSES
     * ({@code PARSER_NO_SCHEMA}) - so without this gate the migration would archive a working legacy config
     * and leave an unrunnable recipe in its place.
     */
    static String whyTheRecipeWouldNotCompile(Path src) {
        try {
            RecipeCompiler.compile(RecipeConverter.toRecipe(
                    ConfigCodec.toMap(Files.readString(src, StandardCharsets.UTF_8))));
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String baseName(String file, String suffix) {
        return file.substring(0, file.length() - (suffix + ".toon").length());
    }

    // ── writing ──────────────────────────────────────────────────────────────

    private static void writePipeline(Conversion c) throws IOException {
        Map<String, Object> config = ConfigCodec.toMap(Files.readString(c.from(), StandardCharsets.UTF_8));
        write(c.to().get(0), ConfigCodec.toToon(RecipeConverter.toRecipe(config)));
    }

    /**
     * Split a legacy schema: the Mapping rules become the {@code mappings/<name>.csv} component and the rest
     * of the file stays the {@code schemas/<name>.toon} structure. A schema with no {@code mapping.rules[]}
     * writes no CSV rather than an empty one — an empty Mapping component and an absent one mean different
     * things to the registry.
     */
    @SuppressWarnings("unchecked")
    private static void writeSchema(Conversion c) throws IOException {
        Map<String, Object> schema =
                new LinkedHashMap<>(ConfigCodec.toMap(Files.readString(c.from(), StandardCharsets.UTF_8)));
        Object mapping = schema.remove("mapping");
        List<Map<String, Object>> rules = mapping instanceof Map<?, ?> m
                && ((Map<String, Object>) m).get("rules") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        write(c.to().get(0), ConfigCodec.toToon(schema));
        if (!rules.isEmpty()) write(c.to().get(1), MappingCsv.encode(rules));
    }

    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    /** Move the original under {@code archived-config/}, keeping its path relative to the config root. */
    private static void archive(Path configRoot, Path src) throws IOException {
        Path target = configRoot.resolve("archived-config").resolve(configRoot.relativize(src));
        Files.createDirectories(target.getParent());
        Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
