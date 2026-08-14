package com.gamma.job;

import com.gamma.config.io.ConfigCodec;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.signal.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@code metadata_validate} maintenance task (System Maintenance MNT-7): a read-only
 * cross-component integrity audit over the component registry ({@code <write-root>/registry/}).
 *
 * <p>Finding classes — deliberately grounded in the reference shapes the demo space ships, never
 * guessed: <b>broken references</b> (a widget's {@code datasetId}/{@code queryId} naming a missing
 * Dataset/Query; a dashboard tile's {@code widgetId} naming a missing Widget), <b>duplicate
 * definitions</b> (two components of one type whose content is identical apart from {@code name}),
 * <b>missing physical data</b> (a Dataset whose {@code physicalRef} resolves to nothing under the
 * data root — checked only when a data root is configured; never guessed otherwise; slashed refs like
 * {@code orders/database} are resolved as relative paths, the shape go-live actually writes — they
 * were skipped entirely until 2026-08-14), and <b>broken pipeline targets</b> (an Expectation /
 * Decision Rule {@code target}, or an enrichment's {@code triggers.on_pipeline} / by-name
 * {@code references.*.ref}, naming a pipeline that no {@code *_pipeline.toon} under the write root
 * declares). Pipeline-target checks are skipped when NO pipeline file exists under the write root
 * at all — pipelines can legitimately be registered from paths outside it, and a false-positive
 * storm is worse than under-reporting.
 *
 * <p>Findings go to the Run Log and, when any exist, one {@code maintenance.metadata.findings}
 * WARNING signal an Alert Rule can subscribe to. Dry run and real run are identical (read-only).
 */
final class MetadataValidateTask {

    private MetadataValidateTask() {}

    static JobResult run(JobContext ctx, String dataDir) {
        long t0 = System.nanoTime();
        String writeRoot = System.getProperty("assist.write.root");
        if (writeRoot == null || writeRoot.isBlank()) {
            return JobResult.ok("metadata_validate: no component registry configured (-Dassist.write.root) — nothing to validate", 0L);
        }
        ComponentStore store = new ComponentStore(Path.of(writeRoot).resolve("registry"));
        Map<String, List<ComponentRegistry.Component>> byType = new LinkedHashMap<>();
        int total = 0;
        // Sweep whatever this build's store manages — never a hard-coded list, so a newly widened
        // component type is audited automatically.
        for (String type : ComponentStore.WRITABLE_TYPES.stream().sorted().toList()) {
            List<ComponentRegistry.Component> list = store.list(type);
            byType.put(type, list);
            total += list.size();
        }
        List<String> findings = new ArrayList<>();
        findings.addAll(com.gamma.pipeline.ComponentIntegrity.brokenRefs(byType));
        findings.addAll(com.gamma.pipeline.ComponentIntegrity.duplicates(byType));
        missingPhysical(byType.get("dataset"), dataDir, findings);
        Set<String> pipelines = pipelineIds(Path.of(writeRoot));
        if (!pipelines.isEmpty()) {
            findings.addAll(com.gamma.pipeline.ComponentIntegrity.brokenPipelineRefs(byType, pipelines));
            brokenEnrichmentRefs(Path.of(writeRoot), pipelines, findings);
        }
        if (ctx != null) {
            for (String f : findings) ctx.log().warn(f);
            if (!findings.isEmpty()) {
                ctx.signals().emit("maintenance.metadata.findings", Severity.WARN,
                        Map.of("count", findings.size(), "findings", findings));
            }
        }
        return JobResult.ok("metadata_validate: " + findings.size() + " finding(s) across " + total
                + " component(s)" + (findings.isEmpty() ? " — healthy" : ""),
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * A Dataset whose {@code physicalRef} has no store dir/file under the data root. A slashed ref
     * ({@code orders/database} — the exact shape go-live registers) resolves as a relative path; a ref
     * that would escape the data root is reported as unsafe rather than verified or skipped.
     */
    private static void missingPhysical(List<ComponentRegistry.Component> datasets, String dataDir,
                                        List<String> findings) {
        if (dataDir == null || dataDir.isBlank() || !Files.isDirectory(Path.of(dataDir))) return;
        Path root = Path.of(dataDir).toAbsolutePath().normalize();
        for (ComponentRegistry.Component d : datasets) {
            String ref = str(d.content().get("physicalRef"));
            if (ref == null) continue;
            Path resolved = root.resolve(ref.replace('\\', '/')).normalize();
            if (!resolved.startsWith(root)) {
                // ⛔ Do not skip this. An escaping ref is a WORSE finding than a merely missing one, and
                // an audit that stays silent about it reports the space clean. "Not ours to verify" is
                // true of the target; it is not true of the reference.
                findings.add("unsafe physical reference: dataset '" + d.name() + "' → '" + ref
                        + "' escapes the data root");
                continue;
            }
            if (!Files.exists(resolved))
                findings.add("missing physical data: dataset '" + d.name() + "' → no store '" + ref
                        + "' under the data root");
        }
    }

    /**
     * Registered pipeline ids from every {@code *_pipeline.toon} under the write root (recursively —
     * shipped spaces keep them in per-stream subdirs; the registry dir is excluded). Id derivation
     * mirrors the parser: explicit non-blank {@code id:}, else {@code name} lower-cased with spaces
     * underscored. Unreadable files are skipped — an audit must not fail on one malformed sibling.
     */
    private static Set<String> pipelineIds(Path writeRoot) {
        Set<String> ids = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(writeRoot, 4)) {
            for (Path p : files
                    .filter(f -> f.getFileName().toString().endsWith("_pipeline.toon"))
                    .filter(f -> !f.startsWith(writeRoot.resolve("registry"))).toList()) {
                try {
                    Map<String, Object> raw = ConfigCodec.toMap(Files.readString(p));
                    String explicit = str(raw.get("id"));
                    if (explicit != null) {
                        ids.add(explicit.trim());
                        continue;
                    }
                    String name = str(raw.get("name"));
                    if (name != null) ids.add(name.trim().toLowerCase().replace(' ', '_'));
                } catch (Exception ignored) {
                    // malformed pipeline file — its own registration path reports that, not this audit
                }
            }
        } catch (IOException ignored) {
            // an unwalkable write root yields no ids, which disables the pipeline-target checks
        }
        return ids;
    }

    /** Enrichment {@code triggers.on_pipeline} and by-name {@code references.*.ref} naming a missing pipeline. */
    private static void brokenEnrichmentRefs(Path writeRoot, Set<String> pipelines, List<String> findings) {
        Set<String> ids = new LinkedHashSet<>();
        for (String p : pipelines) ids.add(p.toLowerCase());
        try (Stream<Path> files = Files.walk(writeRoot, 4)) {
            for (Path p : files
                    .filter(f -> f.getFileName().toString().endsWith("_enrich.toon"))
                    .filter(f -> !f.startsWith(writeRoot.resolve("registry"))).toList()) {
                String name = p.getFileName().toString().replaceFirst("\\.toon$", "");
                try {
                    Map<String, Object> raw = ConfigCodec.toMap(Files.readString(p));
                    if (raw.get("triggers") instanceof Map<?, ?> t) {
                        String on = str(t.get("on_pipeline"));
                        if (on != null && !ids.contains(on.trim().toLowerCase()))
                            findings.add("broken reference: enrichment '" + name
                                    + "' → missing pipeline '" + on.trim() + "' (triggers.on_pipeline)");
                    }
                    if (raw.get("references") instanceof Map<?, ?> refs) {
                        for (Map.Entry<?, ?> e : refs.entrySet()) {
                            if (!(e.getValue() instanceof Map<?, ?> rv)) continue;
                            String ref = str(rv.get("ref"));
                            if (ref != null && !ids.contains(ref.trim().toLowerCase()))
                                findings.add("broken reference: enrichment '" + name
                                        + "' → missing pipeline '" + ref.trim()
                                        + "' (references." + e.getKey() + ".ref)");
                        }
                    }
                } catch (Exception ignored) {
                    // malformed enrichment file — registration reports that, not this audit
                }
            }
        } catch (IOException ignored) {
            // unwalkable write root — nothing to scan
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
