package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.util.ToonHelper;
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
 * {@code *_schema.toon} becomes a {@code schemas/<name>.toon} structure — plus a
 * {@code mappings/<name>.csv} Mapping component when, and only when, it carries a LEGACY
 * {@code mapping.rules[]} block, the one shape that CSV can express (see {@link #writeSchema}).
 * Originals are left untouched, and moved aside to
 * {@code archived-config/} only on {@code --apply}.
 *
 * <p><b>Deterministic and dry-run-first.</b> Files are visited in sorted path order, so two runs over the
 * same tree plan byte-identical output; nothing is written until {@code apply} is passed.
 *
 * <p><b>Refuses rather than loses.</b> A file it cannot project in full is REFUSED by name with the reason,
 * and a refusal fails the whole migration before anything is written — a half-migrated space is the one
 * outcome worse than an unmigrated one. The known refusals:
 * <ul>
 *   <li>A pipeline whose projected recipe would not COMPILE — see {@link #whyTheRecipeWouldNotCompile}.</li>
 *   <li>A target path that already exists (the space is part-migrated, or two legacy files claim one name).</li>
 * </ul>
 *
 * <p>⚠ <b>{@code *_enrich.toon} and the {@code materialize} task are OUT OF SCOPE, by decision</b>
 * (operator, 2026-09-16): the amendment's clauses converting them are <b>struck</b>. The 2026-08-06
 * reversal that made <b>Job</b> canonical again cancelled the file-format migration as well as the
 * vocabulary — {@code enrich} is a shipped, registered Job type ({@code EnrichJob}) with its own audit
 * trail, and {@code materialize} is a Job task that registers a Dataset, which no recipe can express. So
 * this walker passes enrich files over in silence; they are not a lossy case, they are someone else's.
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
                }
                // *_enrich.toon is deliberately NOT converted (operator, 2026-09-16): the amendment's
                // "every *_enrich.toon -> a table-entry recipe" clause is STRUCK. The 2026-08-06 reversal
                // that made Job canonical again cancelled the file-format migration too, not only the
                // vocabulary - `enrich` is a shipped, registered Job type (EnrichJob) with its own audit
                // trail, and a periodic enrich is Job work. So an enrich config is out of scope here, and
                // skipping it is correct rather than lossy. Same call struck the `materialize` clause: that
                // task lives in a Job and registers a Dataset, which no recipe can express.
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
                    ToonHelper.load(src.toString())));
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
        Map<String, Object> config = ToonHelper.load(c.from().toString());
        write(c.to().get(0), ConfigCodec.toToon(RecipeConverter.toRecipe(config)));
    }

    /**
     * Split a legacy schema: a legacy {@code mapping.rules[]} block becomes the {@code mappings/<name>.csv}
     * Mapping component, and the rest of the file stays the {@code schemas/<name>.toon} structure. A schema
     * with no {@code mapping.rules[]} writes no CSV rather than an empty one — an empty Mapping component
     * and an absent one mean different things to the registry.
     *
     * <p>🔴 <b>Only what the CSV actually carries is removed from the schema</b> (fixed 2026-09-17,
     * {@code CONFIG-MIGRATOR-LOSES-MAPPINGS-1}). This used to {@code remove("mapping")} unconditionally and
     * then write the CSV only for {@code rules[]} — so every schema on the CURRENT spelling
     * ({@code mapping.fields[]}, which is what {@link com.gamma.etl.MappingMigrator} migrated the whole
     * corpus to, and what <b>all 25 committed schemas</b> carry) had its whole mapping DELETED, exit 0, no
     * refusal, with the original already archived away. It also dropped {@code canonicalName}/{@code rawName}
     * in the {@code rules[]} case, which the CSV has no column for.
     *
     * <p><b>Why carry {@code mapping.fields[]} through rather than translate it.</b> The Mapping CSV is the
     * LEGACY triple {@code targetColumn,sourceExpression,transformType} ({@link MappingCsv#encode}); of the
     * 23 Record Transformer catalog functions only four ({@code keep}, {@code custom},
     * {@code date.concat_parts}, {@code date.from_filename}) have any {@code transformType} at all — the
     * inverse of {@link com.gamma.etl.RecordTransform#fromMappingRules} — and even those lose their
     * {@code args} (the concat {@code format}, the filename {@code pattern}). The translation is not total,
     * and a lossy translation that looks complete is the worse failure. Refusing the file was the other
     * candidate, but there is nothing here to lose: a schema component is loaded whole by
     * {@code ComponentRegistry}, and {@code RowShaper.mappingSchemaOf} reads {@code schema.mapping.fields}
     * directly — so the carried-through block is the usable, current-spelling artifact, and refusing would
     * reject every schema in the repo for a case that is not lossy.
     */
    @SuppressWarnings("unchecked")
    private static void writeSchema(Conversion c) throws IOException {
        Map<String, Object> schema =
                new LinkedHashMap<>(ToonHelper.load(c.from().toString()));
        Map<String, Object> mapping = schema.get("mapping") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : null;
        List<Map<String, Object>> rules = mapping != null && mapping.get("rules") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();

        if (!rules.isEmpty()) {
            mapping.remove("rules");                       // and ONLY rules - the CSV carries nothing else
            if (mapping.isEmpty()) schema.remove("mapping");
            else schema.put("mapping", mapping);           // same key, so LinkedHashMap keeps its position
        }

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
