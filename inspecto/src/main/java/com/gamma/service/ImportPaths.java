package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The ONE judge of what an import may write under a Space config root (`SEC-IMPORT-ROLES-ESCALATION-1`), used by
 * {@code POST /import}, {@code POST /pipelines/import} (satellites, companions and the pipeline file),
 * {@code /bundle/import}'s {@code pipeline} items and the {@link BundleImporter#writeConfig} backstop. Every path
 * passes FOUR layers, all required, with no exemption:
 * <ol>
 *   <li><b>segment rules</b>, on every platform — each segment is a plain name
 *       ({@code [A-Za-z0-9][A-Za-z0-9._-]*}), never ending in {@code .}, never containing {@code ..}, never a
 *       Windows device name ({@code CON}, {@code NUL}, {@code AUX}, {@code PRN}, {@code COM1-9}, {@code LPT1-9},
 *       with or without an extension); so no {@code ~} (8.3 short names), {@code :} (streams), space, control
 *       character or leading dot can appear;</li>
 *   <li>a <b>shape allowlist</b> — a {@code .toon} / {@code .csv} config; at the config ROOT only the
 *       conventional suffixes ({@link #ROOT_SUFFIXES} — the root is where every settings and identity document
 *       lives); under {@code registry/} exactly {@code registry/<importable kind dir>/<name>}; elsewhere (a
 *       Pipeline's own directory, {@code jobs/}, {@code views/}) only {@link #SUBDIR_SUFFIXES} — and never a
 *       reserved root name one level down. No {@link #REFUSED_SUFFIXES} file (the suffix-scanned ops and semantic
 *       configs: workflows, Case / Tag Rules, Tags, semantic models, RCA and Job templates) anywhere, referenced
 *       or not (`SEC-IMPORT-OPS-CONFIGS-1`). A file that a
 *       carried config names through a REAL reference key ({@link #referenceValues}) may also sit at the root
 *       under its own name (a schema beside a root-level pipeline) and may also be a grammar source or SQL
 *       ({@link #REFERENCE_ONLY_EXTENSIONS}) — and that is ALL a reference buys: never another extension,
 *       never a registry shape, never a way around the next two layers. Because a referenced file may sit at
 *       the root, every name a loader reads there by FIXED name must be reserved —
 *       {@code ImportLoaderInventoryTest} enforces that;</li>
 *   <li>the <b>denylist</b> ({@link ReservedConfigPaths});</li>
 *   <li>a <b>real-path check</b>: the target's nearest existing parent is resolved with {@code toRealPath} and
 *       compared against every reserved file and directory ({@code isSameFile} / real-path prefix), failing
 *       closed on any I/O error.</li>
 * </ol>
 * 🔴 The hole this replaced: the shape rule was skipped for any entry that ANY string value of a carried
 * Pipeline named, so {@code notes.r0: "demo-users.toon"} wrote the Demo sign-in user table (a
 * {@code roles: super} Demo User) and {@code agent/policy.json} the assist agent's autonomy policy.
 * ⚠ Connections ({@code *_connection.toon}) and Jobs ({@code *_job.toon}) stay importable: a data-source
 * bundle carries them. That the import asks only {@code canAuthorWorkbench} while {@code /connections} asks
 * {@code canOnboardConnections} is the separate row {@code IMPORT-CONNECTION-JOB-GATE-1}.
 */
public final class ImportPaths {

    private ImportPaths() {}

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> DEVICES = devices();

    /** The only file shapes an import may land AT the config root. */
    static final List<String> ROOT_SUFFIXES = List.of("_pipeline.toon", "_enrich.toon", "_schema.toon",
            "_job.toon", "_connection.toon", "_mapping.csv", "_structure.csv", ".grammar.toon", "_grammar.toon",
            "_profile.toon");

    /**
     * The shapes an import may land in a SUBDIRECTORY outside {@code registry/} without a reference: the root's
     * data-source shapes plus a View ({@code views/<name>_view.toon}, {@code ViewStore.java:31}). Anything else there
     * must be named by a carried config through a real reference key (`SEC-IMPORT-OPS-CONFIGS-1`: "any plain-named
     * config" used to be enough, which is how {@code ops/incident_workflow.toon} landed).
     */
    static final List<String> SUBDIR_SUFFIXES = subdirSuffixes();

    /**
     * Suffixes no import may write ANYWHERE — at the root, in a subdirectory, under {@code registry/}, and whether or
     * not a carried config references the file (`SEC-IMPORT-OPS-CONFIGS-1`). Each is loaded by a boot-time SUFFIX
     * scan that walks the whole config tree ({@code ServiceBootstrap.resolveBySuffix} is a recursive
     * {@code Files.walk}, {@code ServiceBootstrap.java:120-135}) and each has its own route, narrower or validated,
     * that an import would skip:
     * <ul>
     *   <li>{@code _workflow.toon}, {@code _caserule.toon}, {@code _tagrule.toon}, {@code _tag.toon} — the ops engine,
     *       {@code OpsEngineProvider.java:237-241} over {@code ServiceBootstrap.java:84}'s every-{@code .toon} walk;
     *       the LAST workflow per object type wins, so an imported INCIDENT workflow replaced the lifecycle the
     *       Disposition / postmortem gate is keyed on. Case / Tag Rules and Tags are written by
     *       {@code POST /cases/rules} and {@code /tags*}, which validate them;</li>
     *   <li>{@code _meta.toon} (semantic models, {@code ServiceBootstrap.java:65}), {@code _rca.toon} (RCA templates,
     *       {@code ServiceBootstrap.java:75}) and {@code _job_template.toon} (Job templates, expanded into every Job
     *       naming them, {@code ServiceBootstrap.java:63}) — none travels in a data-source or Pipeline bundle;</li>
     *   <li>{@code _escalation.toon} and {@code _queue.toon} — no loader reads them since {@code RETIRE-HALVES-1}
     *       (2026-09-14); refused so a loader brought back cannot inherit an import door.</li>
     * </ul>
     * {@code ImportLoaderInventoryTest} fails when a {@code _x.toon} suffix literal appears in main code that is
     * neither refused here nor allow-listed there with a reason.
     */
    static final List<String> REFUSED_SUFFIXES = List.of("_workflow.toon", "_caserule.toon", "_tagrule.toon",
            "_tag.toon", "_meta.toon", "_rca.toon", "_job_template.toon", "_escalation.toon", "_queue.toon");

    private static List<String> subdirSuffixes() {
        List<String> s = new ArrayList<>(ROOT_SUFFIXES);
        s.add("_view.toon");
        return List.copyOf(s);
    }

    /** Extensions every import may write (outside the root, and at the root under {@link #ROOT_SUFFIXES}). */
    static final List<String> CONFIG_EXTENSIONS = List.of(".toon", ".csv");

    /** Extensions allowed ONLY for a file a carried config names through a real reference key: an ASN.1 grammar
     *  module ({@code asn1.grammar_file}, {@code ingester_config.grammar}) or an Enrichment's {@code transform_file}. */
    static final List<String> REFERENCE_ONLY_EXTENSIONS = List.of(".asn", ".asn1", ".sql");

    /** Registry type directories an import may write — every writable kind's but the access config's. */
    static final Set<String> REGISTRY_DIRS = registryDirs();

    private static Set<String> devices() {
        Set<String> d = new TreeSet<>(Set.of("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"));
        for (int i = 1; i <= 9; i++) { d.add("COM" + i); d.add("LPT" + i); }
        return d;
    }

    private static Set<String> registryDirs() {
        Set<String> dirs = new TreeSet<>();
        for (String kind : ComponentStore.WRITABLE_TYPES)
            if (!ReservedConfigPaths.KINDS.contains(kind)) ComponentRegistry.dirForType(kind).ifPresent(dirs::add);
        dirs.add("connections");
        dirs.removeAll(Set.of("access-catalog", "access-profiles"));
        return dirs;
    }

    /** {@link #refusal(Path, String, Set)} for a file no carried config references. */
    public static String refusal(Path configRoot, String relPath) {
        return refusal(configRoot, relPath, Set.of());
    }

    /**
     * Why {@code relPath} may not be written by an import under {@code configRoot}, or {@code null} when it may.
     * {@code referenced} are the config-relative paths a carried config names through a real reference key
     * ({@link #referencedEntries}); they widen the allowed EXTENSIONS only (class doc, layer 2).
     */
    public static String refusal(Path configRoot, String relPath, Set<String> referenced) {
        String segments = segmentRefusal(relPath);
        if (segments != null) return segments;
        String norm = relPath.replace('\\', '/');
        String shape = shapeRefusal(norm, referenced.contains(norm));
        if (shape != null) return shape;
        return reservedRefusal(configRoot, norm);
    }

    /** Layer 2 alone (the path already passed the segment rules). */
    static String shapeRefusal(String norm, boolean referenced) {
        List<String> parts = List.of(norm.split("/"));
        String file = parts.getLast().toLowerCase(Locale.ROOT);
        boolean config = CONFIG_EXTENSIONS.stream().anyMatch(file::endsWith);
        if (!config && !(referenced && REFERENCE_ONLY_EXTENSIONS.stream().anyMatch(file::endsWith)))
            return "not a config file " + CONFIG_EXTENSIONS + (referenced ? " or a referenced " + REFERENCE_ONLY_EXTENSIONS
                    : " (and no carried config names it through a reference key)");
        // A referenced file may sit at the root under its own name (a schema beside a root-level pipeline is how
        // a Space is laid out and exported) — which is why the root's fixed names MUST all be reserved, and
        // ImportLoaderInventoryTest fails when a loader reads one that is not.
        // SEC-IMPORT-OPS-CONFIGS-1: before any exemption — a reference does not stop a suffix scan from loading it
        for (String suffix : REFUSED_SUFFIXES)
            if (file.endsWith(suffix))
                return "a '*" + suffix + "' is loaded by a config-tree suffix scan and written only through its own "
                        + "route — no import may carry one, anywhere";
        if (parts.size() == 1 && !referenced && ROOT_SUFFIXES.stream().noneMatch(file::endsWith))
            return "the config root takes only " + ROOT_SUFFIXES + " or a file a carried config names through a "
                    + "reference key — it is where the settings and identity documents live";
        boolean registry = "registry".equalsIgnoreCase(parts.getFirst());
        if (registry && (parts.size() != 3 || !REGISTRY_DIRS.contains(parts.get(1))))
            return "a registry entry must be registry/<kind>/<name> for an importable kind " + REGISTRY_DIRS;
        if (parts.size() > 1 && !registry) {
            // a reserved root name one level down is nobody's legitimate file — and a later loader reading it by
            // suffix or recursively would otherwise inherit the door (registry/ excepted: its names are component ids)
            if (ReservedConfigPaths.FILES.contains(file))
                return "'" + file + "' is a reserved config-root name (ReservedConfigPaths) — refused in any directory";
            if (!referenced && SUBDIR_SUFFIXES.stream().noneMatch(file::endsWith))
                return "a subdirectory takes only " + SUBDIR_SUFFIXES + " or a file a carried config names through a "
                        + "reference key";
        }
        return null;
    }

    /** Layers 3 and 4 alone — for a file the SERVER chose (an overwrite's registered pipeline file). */
    public static String reservedRefusal(Path configRoot, String relPath) {
        if (ReservedConfigPaths.reserved(relPath)) return "a reserved file (ReservedConfigPaths)";
        return realPathRefusal(configRoot, relPath);
    }

    // ── real reference keys ────────────────────────────────────────────────────────────────────

    /**
     * The values of the REAL reference keys of a Pipeline config — exactly the keys the loaders resolve as a
     * file, and the keys {@code PipelineBundleRoutes.rewriteSatelliteRefs} rewrites (so export, import and this
     * judge agree). Any other string — {@code notes}, a description, a column name — references nothing:
     * <ul>
     *   <li>{@code processing.schema_file} — {@code PipelineConfigParser.java:746};</li>
     *   <li>{@code processing.schemas[].schema_file} (and top-level {@code schemas[]}) — {@code PipelineConfigParser.java:710};</li>
     *   <li>{@code parsing.grammar} / {@code processing.grammar} — {@code PipelineConfigParser.java:1028-1029};</li>
     *   <li>{@code processing.mapping_file} — {@code PipelineConfigParser.java:1373};</li>
     *   <li>{@code processing.segments.*} / {@code parsing.plugin.segments.*} / {@code parsing.asn1.segments.*}
     *       — {@code PipelineConfigParser.java:1201-1211};</li>
     *   <li>{@code ingester_config.grammar} (in {@code processing} or {@code parsing.plugin}) —
     *       {@code PipelineConfigParser.java:1183-1197};</li>
     *   <li>{@code parsing.asn1.grammar} / {@code grammar_file} (and {@code parsing.plugin.grammar_file}) —
     *       {@code PipelineConfigParser.java:1871-1888}, {@code Asn1ParserPlugin.java:146};</li>
     *   <li>{@code parsing.asn1.profile_file} — a Decode Profile, {@code DecodeProfile.java:38}; its own
     *       {@code asn1.grammar_file} and {@code asn1.segments.*} ({@code DecodeProfile.java:76}) are collected
     *       by {@link #profileReferenceValues}.</li>
     * </ul>
     * For an Enrichment the one file reference is {@code transform_file} ({@code EnrichmentConfig.java:151}).
     */
    public static List<String> referenceValues(Map<String, Object> pipeline) {
        List<String> out = new ArrayList<>();
        Map<?, ?> proc = map(pipeline.get("processing"));
        Map<?, ?> parsing = map(pipeline.get("parsing"));
        add(out, proc.get("schema_file"));
        add(out, proc.get("grammar"));
        add(out, proc.get("mapping_file"));
        schemaList(out, proc.get("schemas"));
        schemaList(out, pipeline.get("schemas"));
        segments(out, proc.get("segments"));
        add(out, map(proc.get("ingester_config")).get("grammar"));
        add(out, parsing.get("grammar"));
        for (String frontend : List.of("asn1", "plugin")) {
            Map<?, ?> f = map(parsing.get(frontend));
            segments(out, f.get("segments"));
            add(out, f.get("grammar"));
            add(out, f.get("grammar_file"));
            add(out, f.get("profile_file"));
            add(out, map(f.get("ingester_config")).get("grammar"));
        }
        return out;
    }

    /** The refs INSIDE a Decode Profile document ({@code asn1.grammar_file}, {@code asn1.segments.*}). */
    public static List<String> profileReferenceValues(Map<String, Object> profile) {
        List<String> out = new ArrayList<>();
        Map<?, ?> asn1 = map(profile.get("asn1"));
        add(out, asn1.get("grammar_file"));
        segments(out, asn1.get("segments"));
        return out;
    }

    /** The one file reference of an Enrichment config. */
    public static List<String> enrichmentReferenceValues(Map<String, Object> enrichment) {
        List<String> out = new ArrayList<>();
        add(out, enrichment.get("transform_file"));
        return out;
    }

    /** The Decode Profile refs of a Pipeline (a subset of {@link #referenceValues}). */
    private static List<String> profileRefs(Map<String, Object> pipeline) {
        List<String> out = new ArrayList<>();
        Map<?, ?> parsing = map(pipeline.get("parsing"));
        for (String frontend : List.of("asn1", "plugin")) add(out, map(parsing.get(frontend)).get("profile_file"));
        return out;
    }

    /**
     * The config-relative entries a bundle's {@code *_pipeline.toon} / {@code *_enrich.toon} entries name through
     * a real reference key ({@link #referenceValues}), resolved beside the naming config (how the loader resolves
     * a config reference) or as written. A referenced Decode Profile's own refs count too. Unparseable configs
     * reference nothing.
     */
    public static Set<String> referencedEntries(Map<String, byte[]> entries) {
        Set<String> names = new HashSet<>();
        for (String k : entries.keySet()) names.add(k.replace('\\', '/'));
        Set<String> out = new TreeSet<>();
        for (var e : entries.entrySet()) {
            String key = e.getKey().replace('\\', '/');
            boolean pipeline = key.endsWith("_pipeline.toon"), enrich = key.endsWith("_enrich.toon");
            if (!pipeline && !enrich) continue;
            String dir = dirOf(key);
            Map<String, Object> doc = parse(e.getValue());
            if (doc == null) continue;
            for (String ref : pipeline ? referenceValues(doc) : enrichmentReferenceValues(doc))
                resolveEntry(ref, dir, names).ifPresent(out::add);
            if (!pipeline) continue;
            for (String pref : profileRefs(doc)) {
                var profileEntry = resolveEntry(pref, dir, names);
                if (profileEntry.isEmpty()) continue;
                Map<String, Object> profile = parse(entries.getOrDefault(profileEntry.get(),
                        entries.get(profileEntry.get().replace('/', '\\'))));
                if (profile == null) continue;
                for (String ref : profileReferenceValues(profile))
                    resolveEntry(ref, dirOf(profileEntry.get()), names).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * The satellite basenames a Pipeline bundle's pipeline (and its bundled Decode Profile) name through a real
     * reference key — how {@code PipelineBundleRoutes.rewritten} maps a ref to the bare basename landing beside
     * the pipeline file. {@code schema/<id>} / {@code grammar/<id>} registry refs leaf to {@code <id>.toon}.
     */
    public static Set<String> referencedSatellites(Map<String, Object> pipeline, Map<String, byte[]> entries) {
        Set<String> out = new TreeSet<>();
        for (String ref : referenceValues(pipeline)) out.add(leaf(ref));
        for (String pref : profileRefs(pipeline)) {
            Map<String, Object> profile = parse(entries.get(leaf(pref)));
            if (profile != null) for (String ref : profileReferenceValues(profile)) out.add(leaf(ref));
        }
        return out;
    }

    private static String leaf(String ref) {
        if (ref.startsWith("schema/") || ref.startsWith("grammar/")) return ref.substring(ref.indexOf('/') + 1) + ".toon";
        int cut = Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\'));
        return cut < 0 ? ref : ref.substring(cut + 1);
    }

    private static java.util.Optional<String> resolveEntry(String ref, String dir, Set<String> names) {
        String r = ref.trim().replace('\\', '/');
        String base = r.contains("/") ? r.substring(r.lastIndexOf('/') + 1) : r;
        // As written, beside the config, and by file name beside the config (an exported config may still spell an
        // absolute path the exporter re-homed to a config-relative entry).
        for (String cand : List.of(r, dir + r, dir + base)) {
            try {
                String n = Path.of(cand).normalize().toString().replace('\\', '/');
                if (names.contains(n)) return java.util.Optional.of(n);
            } catch (RuntimeException bad) {
                // not a path — references nothing
            }
        }
        return java.util.Optional.empty();
    }

    private static String dirOf(String key) {
        return key.contains("/") ? key.substring(0, key.lastIndexOf('/') + 1) : "";
    }

    private static Map<String, Object> parse(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    private static void add(List<String> out, Object v) {
        if (v instanceof String s && !s.isBlank()) out.add(s.trim());
    }

    private static void schemaList(List<String> out, Object v) {
        if (v instanceof Collection<?> c) for (Object row : c) add(out, map(row).get("schema_file"));
    }

    private static void segments(List<String> out, Object v) {
        for (Object s : map(v).values()) add(out, s);
    }

    // ── layer 1 and layer 4 ────────────────────────────────────────────────────────────────────

    /** Layer 1 alone — the segment rules every import applies, a brand-new Space included. */
    public static String segmentRefusal(String relPath) {
        if (relPath == null || relPath.isEmpty()) return "an empty path";
        String p = relPath.replace('\\', '/');
        if (p.startsWith("/")) return "an absolute path";
        for (String seg : p.split("/", -1)) {
            if (!SEGMENT.matcher(seg).matches())
                return "segment '" + seg + "' is not a plain name ([A-Za-z0-9][A-Za-z0-9._-]*; no ~, :, space, "
                        + "control character, leading dot or '..')";
            if (seg.endsWith(".")) return "segment '" + seg + "' ends in '.' (Windows drops it: an alias)";
            if (seg.contains("..")) return "segment '" + seg + "' contains '..'";
            String stem = seg.contains(".") ? seg.substring(0, seg.indexOf('.')) : seg;
            if (DEVICES.contains(stem.toUpperCase(Locale.ROOT))) return "segment '" + seg + "' is a Windows device name";
        }
        return null;
    }

    /** Layer 4: the target, resolved on the real filesystem, is none of the reserved files or directories. */
    static String realPathRefusal(Path configRoot, String relPath) {
        try {
            Path root = configRoot.toAbsolutePath().normalize();
            Path target = root.resolve(relPath).normalize();
            if (!target.startsWith(root)) return "escapes the config root";
            if (!Files.exists(root)) return null;   // nothing on disk yet — nothing to alias
            Path realRoot = root.toRealPath();
            Path existing = target;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing == null) return "cannot be resolved";
            Path realTarget = existing.toRealPath().resolve(existing.relativize(target)).normalize();
            if (!realTarget.startsWith(realRoot)) return "resolves outside the config root";
            for (String f : ReservedConfigPaths.FILES) {
                Path rf = realRoot.resolve(f);
                if (realTarget.toString().equalsIgnoreCase(rf.toString())) return "resolves to the reserved " + f;
                if (Files.exists(target) && Files.exists(rf) && Files.isSameFile(target, rf))
                    return "is the same file as the reserved " + f;
            }
            for (String d : ReservedConfigPaths.DIRS) {
                Path rd = realRoot.resolve(d);
                Path realDir = Files.exists(rd) ? rd.toRealPath() : rd.normalize();
                if (realTarget.startsWith(realDir)
                        || realTarget.toString().toLowerCase(Locale.ROOT).startsWith(
                                (realDir + java.io.File.separator).toLowerCase(Locale.ROOT)))
                    return "resolves inside the reserved " + d;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return "its real path could not be checked (" + e.getMessage() + ") — refused, fail closed";
        }
    }

    /** Every refusal among a bundle's {@code entries}, as {@code "path: reason"}. */
    public static List<String> refusals(Path configRoot, Map<String, byte[]> entries) {
        Set<String> referenced = referencedEntries(entries);
        List<String> out = new ArrayList<>();
        for (String p : entries.keySet()) {
            String why = refusal(configRoot, p, referenced);
            if (why != null) out.add(p + ": " + why);
        }
        return out;
    }
}
