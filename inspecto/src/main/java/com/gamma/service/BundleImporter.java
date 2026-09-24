package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/**
 * Reads a {@link BundleExporter}-produced bundle zip and unpacks its config files into a space's
 * {@code config/} tree. Pure plumbing — parsing, the zip-slip jail, and the atomic writes; deciding
 * conflicts and making the configs live (register / rebuild) is the caller's job (the HTTP route, which
 * has the {@link CollectorService}).
 *
 * <p>Config entries are written under their bundle path (relative to {@code config/}); the
 * {@code bundle.toon} manifest and any {@code space.toon} are split out and never land in {@code config/}.
 *
 * <h2>No path rewriting</h2>
 * A config's bytes travel verbatim. Its refs to other config files resolve beside it
 * ({@code SCHEMA-FILE-RESOLVES-AGAINST-CWD-1}) and its data paths under the Space directory it lands in
 * ({@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}), so {@code data/orders/database} imported into beta IS beta's.
 * ⚠ Until 2026-09-23 configs spelled {@code spaces/alpha/data/…} and this class rewrote the source space's
 * prefix onto the target's ("space rebasing", W3); with nothing space-qualified left to rewrite, that
 * mechanism was retired rather than kept for bundles nothing ships any more.
 */
public final class BundleImporter {

    /**
     * A parsed bundle: its manifest {@code kind} ({@code datasource} | {@code space}), the full manifest map,
     * the config-file entries (keyed by config-relative path), and the optional {@code space.toon} bytes.
     */
    public record Bundle(String kind, Map<String, Object> manifest,
                         LinkedHashMap<String, byte[]> configEntries, byte[] spaceToon) {}

    private BundleImporter() {}

    /** Parse a bundle zip, validating its {@code bundle.toon} manifest. */
    public static Bundle parse(byte[] zip) throws IOException {
        LinkedHashMap<String, byte[]> all = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                if (!e.isDirectory()) all.put(e.getName(), zis.readAllBytes());
        }
        byte[] mf = all.remove(BundleExporter.MANIFEST);
        if (mf == null) throw new IllegalArgumentException("not a bundle: missing " + BundleExporter.MANIFEST);
        Map<String, Object> manifest;
        try {
            manifest = ConfigCodec.toMap(new String(mf, StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            throw new IllegalArgumentException("invalid " + BundleExporter.MANIFEST + ": " + bad.getMessage(), bad);
        }
        byte[] spaceToon = all.remove(BundleExporter.SPACE_TOON);
        return new Bundle(String.valueOf(manifest.getOrDefault("kind", "")), manifest, all, spaceToon);
    }

    /** The pipeline ids declared in the bundle (lowercased in-file {@code name}) — for conflict detection. */
    public static List<String> pipelineIds(Bundle bundle) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            if (!e.getKey().endsWith("_pipeline.toon")) continue;
            Object name = ConfigCodec.toMap(new String(e.getValue(), StandardCharsets.UTF_8)).get("name");
            if (name != null && !name.toString().isBlank()) ids.add(name.toString().toLowerCase());
        }
        return ids;
    }

    /** A bundle narrowed for one target space, and the carried References it left to the target's own copy. */
    public record Narrowed(Bundle bundle, List<String> referencesKept) {}

    /**
     * Drop each carried Reference ({@link BundleExporter#REFERENCES}) the target space already hosts, so the
     * target's own copy is used as-is rather than the import 409ing on a pipeline the data source merely
     * reads. Only the entries that Reference alone brought are dropped; one another carried Reference also
     * needs stays. A bundle with no {@code references} index (every pre-W5-forward bundle) is returned
     * unchanged. The caller skips this under {@code on_conflict=overwrite}, where replacing is what was asked.
     *
     * @param existing the pipeline ids the target space already hosts
     */
    public static Narrowed keepExistingReferences(Bundle bundle, Set<String> existing) {
        if (!(bundle.manifest().get(BundleExporter.REFERENCES) instanceof Map<?, ?> index))
            return new Narrowed(bundle, List.of());
        List<String> kept = new ArrayList<>();
        Set<String> drop = new HashSet<>();
        Set<String> needed = new HashSet<>();
        for (Map.Entry<?, ?> e : index.entrySet()) {
            String id = String.valueOf(e.getKey());
            List<String> files = e.getValue() instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            if (existing.contains(id)) {
                kept.add(id);
                drop.addAll(files);
            } else {
                needed.addAll(files);
            }
        }
        if (kept.isEmpty()) return new Narrowed(bundle, List.of());
        drop.removeAll(needed);
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(bundle.configEntries());
        entries.keySet().removeAll(drop);
        return new Narrowed(new Bundle(bundle.kind(), bundle.manifest(), entries, bundle.spaceToon()), List.copyOf(kept));
    }

    /** A bundle with its connection-dependent configs switched off, and one warning per missing connection. */
    public record MissingConnections(Bundle bundle, List<Map<String, Object>> warnings) {}

    /**
     * Switch OFF every config that needs a connection the target has neither registered nor receives in this
     * bundle, and report one warning per such connection (operator decision 2026-09-25 — a missing connection
     * warns; until then it refused the whole import). Each config is disabled by the switch <b>its own kind
     * reads</b>, never a cross-kind stamp: a pipeline's top-level {@code active} (PipelineConfigParser — the
     * poll cycle runs only an active pipeline) and a job's {@code job.enabled} (JobConfig — the scheduler arms
     * only an enabled job). A pipeline needs a connection through {@code collector.connection} or
     * {@code webhook.connection}; a job through {@code job.connection} ({@code objectstore.export}).
     *
     * <p>Only a disabled entry is re-serialised; every other entry keeps its exact bytes. An entry that will
     * not parse is left alone — the import's own gates report it.
     *
     * @param registered the connection ids the target space already holds
     * @return the bundle to write, and warnings {@code {connection, code, message, disabled:[{kind,name,file}]}}
     *         in first-seen order
     */
    public static MissingConnections disableForMissingConnections(Bundle bundle, Set<String> registered) {
        Set<String> known = new HashSet<>(registered);
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            if (!e.getKey().endsWith("_connection.toon")) continue;
            Map<String, Object> doc = parseOrNull(e.getValue());
            Object id = doc == null ? null
                    : (doc.get("connection") instanceof Map<?, ?> m ? m : doc).get("id");
            if (id != null && !String.valueOf(id).isBlank()) known.add(String.valueOf(id).trim());
        }

        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(bundle.configEntries());
        Map<String, List<Map<String, Object>>> byConnection = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            String file = e.getKey();
            boolean pipeline = file.endsWith("_pipeline.toon");
            if (!pipeline && !file.endsWith("_job.toon")) continue;
            Map<String, Object> doc = parseOrNull(e.getValue());
            if (doc == null) continue;
            Map<String, Object> switchHolder = pipeline ? doc : subMap(doc, "job");
            if (switchHolder == null) continue;
            Set<String> missing = new java.util.LinkedHashSet<>();
            for (String block : pipeline ? List.of("collector", "webhook") : List.of("job")) {
                Map<String, Object> m = subMap(doc, block);
                Object conn = m == null ? null : m.get("connection");
                String id = conn == null ? "" : String.valueOf(conn).trim();
                if (!id.isEmpty() && !known.contains(id)) missing.add(id);
            }
            if (missing.isEmpty()) continue;
            switchHolder.put(pipeline ? "active" : "enabled", false);
            entries.put(file, ConfigCodec.toToon(doc).getBytes(StandardCharsets.UTF_8));
            Object name = switchHolder.get("name");
            Map<String, Object> disabled = Map.of("kind", pipeline ? "pipeline" : "job",
                    "name", name == null ? "" : pipeline ? name.toString().toLowerCase() : name.toString(),
                    "file", file);
            for (String id : missing) byConnection.computeIfAbsent(id, k -> new ArrayList<>()).add(disabled);
        }

        List<Map<String, Object>> warnings = new ArrayList<>();
        byConnection.forEach((id, disabled) -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("connection", id);
            w.put("code", com.gamma.config.spec.FindingCodes.WARN_UNRESOLVED_CONNECTION);
            w.put("message", "connect " + id + " to enable — this space has no such connection profile, so "
                    + disabled.size() + " imported config(s) that need it landed disabled");
            w.put("disabled", List.copyOf(disabled));
            warnings.add(w);
        });
        return new MissingConnections(new Bundle(bundle.kind(), bundle.manifest(), entries, bundle.spaceToon()),
                List.copyOf(warnings));
    }

    private static Map<String, Object> parseOrNull(byte[] toon) {
        try {
            return ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> doc, String key) {
        return doc.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** What an unpack did: the config-relative paths written. */
    public record Unpacked(List<String> paths) {}

    /**
     * Write the bundle's config entries under {@code configDir}, verbatim, jailed against zip-slip (each
     * resolved target must stay within {@code configDir}).
     */
    public static Unpacked writeConfig(Bundle bundle, Path configDir) throws IOException {
        Path root = configDir.toAbsolutePath().normalize();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            Path target = root.resolve(e.getKey()).normalize();
            if (!target.startsWith(root))
                throw new IllegalArgumentException("bundle entry escapes the config dir: " + e.getKey());
            AtomicFiles.write(target, e.getValue(), ".import-");
            written.add(root.relativize(target).toString().replace('\\', '/'));
        }
        return new Unpacked(written);
    }
}
