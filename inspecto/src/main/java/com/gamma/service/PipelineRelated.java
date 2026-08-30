package com.gamma.service;

import com.gamma.etl.PipelineConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything related to one pipeline — <b>what it owns, what names it, and what an import needs</b>
 * (pipeline spec §12 gap 5, decision D9). The honest answer to "what belongs to this pipeline", which
 * a caller previously had to assemble by scanning in both directions itself (§4: a pipeline points out
 * to its schemas, while enrichments and jobs point in).
 *
 * <p><b>This joins two halves that already existed; it re-traverses nothing.</b>
 * <ul>
 *   <li><b>Inward</b> — {@link PipelineDependents#scan}, already serving
 *       {@code GET /config/pipeline/{name}/impact}. It reports a superset of what D9 asks for
 *       (enrichment · job · expectation · decision-rule · dataset · widget · dashboard), bounded, with
 *       a TRUE total. Reused verbatim: one scan, one set of matching rules, one place to fix.</li>
 *   <li><b>Outward</b> — {@link PipelineConfig#referencedFiles()}, the files the parser <em>actually
 *       read</em> for this pipeline. Deliberately NOT a re-derivation from config keys: a second reader
 *       of the same config is precisely the drift {@code PipelineKeyCoverageContractTest} exists to
 *       stop, and this class must not add an instance of it.</li>
 * </ul>
 *
 * <p><b>A {@code kind} is only claimed where it is certain.</b> A file living under
 * {@code <writeRoot>/registry/<dir>/} IS that component type — the directory says so. A plain path is
 * reported as {@code kind: "file"} with its real location rather than guessed at from its suffix.
 * 🔴 <b>Completeness does not depend on that labelling:</b> every file the parser read is reported
 * either way. That matters today, not hypothetically — the parser also picks up a <b>sibling mapping
 * CSV by convention, with no config key naming it at all</b>, so reporting only files some known key
 * explained would silently drop it and an import would lose the mapping. If a precise kind for plain
 * paths is ever wanted, the honest fix is for {@code PipelineConfig} to carry the provenance, not for a
 * second reader to infer it here.
 *
 * <p>⛔ <b>Connections are excluded</b> (D9, the operator's call): they carry environment and
 * credentials, and a bundle that moved them would move a deployment's identity between spaces.
 * {@code connections} is absent from {@link #TYPE_BY_REGISTRY_DIR}, so one can never be reported.
 */
public final class PipelineRelated {

    private PipelineRelated() {
    }

    /**
     * Registry directory → component type, for the kinds a pipeline may reference. ⛔ {@code connections}
     * is deliberately absent (D9) — adding it would put credentials in an export closure.
     */
    private static final Map<String, String> TYPE_BY_REGISTRY_DIR = Map.of(
            "schemas", "schema",
            "mappings", "mapping",
            "grammars", "grammar",
            "references", "reference");

    /**
     * One thing the pipeline references and an import would need alongside it.
     *
     * @param kind the component type when this is a shared component, else {@code file}
     * @param ref  the component as {@code <type>/<id>} — {@code null} for a plain path. ⚠ A config may
     *             spell the same ref with a singular or plural directory ({@code grammar/x} and
     *             {@code grammars/x} both resolve); this is always the canonical singular type, so a
     *             caller never has to normalise.
     * @param path where it lives, RELATIVE to the write root and always {@code /}-separated — an
     *             absolute server path is neither portable nor a caller's business
     */
    public record Reference(String kind, String ref, String path) {
    }

    /**
     * @param pipeline     the id that was scanned for
     * @param references   what this pipeline points OUT to
     * @param referencedBy what points IN at it, bounded by {@link PipelineDependents#MAX_DEPENDENTS}
     * @param total        the TRUE count of inward dependents, even when {@code truncated}
     * @param truncated    whether {@code referencedBy} is short of {@code total}
     */
    public record Report(String pipeline, List<Reference> references,
                         List<PipelineDependents.Dependent> referencedBy,
                         int total, boolean truncated) {
    }

    /** Join both halves for {@code cfg}, with the default dependents cap. */
    public static Report of(Path writeRoot, PipelineConfig cfg) {
        return of(writeRoot, cfg, PipelineDependents.MAX_DEPENDENTS);
    }

    /**
     * Join both halves for {@code cfg}. {@code limit} bounds only the inward list — the outward set is
     * this pipeline's own companions, which cannot grow unboundedly the way dependents can.
     */
    public static Report of(Path writeRoot, PipelineConfig cfg, int limit) {
        String id = cfg.identity().pipelineName();
        PipelineDependents.Report inward = PipelineDependents.scan(writeRoot, id, limit);
        return new Report(id, references(writeRoot, cfg), inward.dependents(),
                inward.total(), inward.truncated());
    }

    /** Every file the parser read for this pipeline, de-duplicated, in first-seen order. */
    private static List<Reference> references(Path writeRoot, PipelineConfig cfg) {
        List<Reference> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path p : cfg.referencedFiles()) {
            if (p == null) continue;
            Path abs = p.toAbsolutePath().normalize();
            if (!seen.add(abs.toString())) continue;
            String ref = registryRefFor(writeRoot, abs);
            String kind = ref == null ? "file" : ref.substring(0, ref.indexOf('/'));
            out.add(new Reference(kind, ref, relative(writeRoot, abs)));
        }
        return List.copyOf(out);
    }

    /**
     * {@code <type>/<id>} when {@code file} is a component directly under
     * {@code <writeRoot>/registry/<dir>/}, else {@code null}.
     */
    private static String registryRefFor(Path writeRoot, Path file) {
        if (writeRoot == null) return null;
        Path registry = writeRoot.resolve("registry").toAbsolutePath().normalize();
        if (!file.startsWith(registry)) return null;
        Path rel = registry.relativize(file);
        if (rel.getNameCount() != 2) return null;            // registry/<dir>/<file>, never nested deeper
        String type = TYPE_BY_REGISTRY_DIR.get(rel.getName(0).toString());
        if (type == null) return null;
        String name = rel.getName(1).toString();
        int dot = name.indexOf('.');                          // strip .toon / .csv / .grammar.toon
        return type + "/" + (dot < 0 ? name : name.substring(0, dot));
    }

    /**
     * Wire shape, mirroring {@link PipelineDependents#toJson} so the two reads read alike: the inward
     * half keeps its {@code dependents} map-by-kind and its {@code total}/{@code truncated}, and the
     * outward half is added as a flat {@code references} list. It is a LIST, not a map by kind, because
     * an import applies these in order and two entries can share a kind.
     */
    public static Map<String, Object> toJson(Report report) {
        Map<String, List<Map<String, String>>> byKind = new LinkedHashMap<>();
        for (PipelineDependents.Dependent d : report.referencedBy()) {
            byKind.computeIfAbsent(d.kind(), k -> new ArrayList<>())
                    .add(Map.of("name", d.name(), "via", d.via()));
        }
        List<Map<String, String>> refs = new ArrayList<>();
        for (Reference r : report.references()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("kind", r.kind());
            if (r.ref() != null) m.put("ref", r.ref());   // absent, not null, for a plain path
            m.put("path", r.path());
            refs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pipeline", report.pipeline());
        out.put("references", refs);
        out.put("dependents", byKind);
        out.put("total", report.total());
        out.put("truncated", report.truncated());
        return out;
    }

    /** {@code file} relative to the write root, {@code /}-separated; the absolute path if it lies outside. */
    private static String relative(Path writeRoot, Path file) {
        if (writeRoot != null) {
            Path root = writeRoot.toAbsolutePath().normalize();
            if (file.startsWith(root)) return root.relativize(file).toString().replace('\\', '/');
        }
        return file.toString().replace('\\', '/');
    }
}
