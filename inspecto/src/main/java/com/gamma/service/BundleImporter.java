package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h2>Space rebasing (W3, 2026-08-01)</h2>
 * A pipeline's {@code dirs.*}, {@code processing.schema_file}, {@code processing.grammar} and
 * {@code parsing.plugin.segments.*} are <b>space-qualified paths</b> ({@code spaces/alpha/data/orders/…}).
 * They travelled verbatim, so importing alpha's data source into beta produced a live pipeline that
 * polled, wrote and quarantined inside <em>alpha's</em> data plane, and read alpha's schema file —
 * silently, because the schema reference still resolved against the working directory. {@link #writeConfig}
 * therefore rewrites the source space's path prefix to the target's on every {@code .toon} entry, and
 * reports which files it touched: an import that re-pointed directories without saying so is a trap.
 * The UI's stream-config transfer ({@code stream-bundle.ts}) has always done this at import; this is the
 * same rule on the zip path.
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

    /** What an unpack did: the config-relative paths written, and which of them were space-rebased. */
    public record Unpacked(List<String> paths, List<String> rebased) {}

    /**
     * Write the bundle's config entries under {@code configDir}, jailed against zip-slip (each resolved
     * target must stay within {@code configDir}), rebasing the source space's paths onto the target.
     */
    public static Unpacked writeConfig(Bundle bundle, Path configDir) throws IOException {
        Path root = configDir.toAbsolutePath().normalize();
        String from = sourcePrefix(bundle);
        String to = targetPrefix(root);
        List<String> written = new ArrayList<>();
        List<String> rebased = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : bundle.configEntries().entrySet()) {
            Path target = root.resolve(e.getKey()).normalize();
            if (!target.startsWith(root))
                throw new IllegalArgumentException("bundle entry escapes the config dir: " + e.getKey());
            byte[] body = e.getValue();
            String moved = rebase(e.getKey(), body, from, to);
            if (moved != null) {
                body = moved.getBytes(StandardCharsets.UTF_8);
                rebased.add(e.getKey());
            }
            AtomicFiles.write(target, body, ".import-");
            written.add(root.relativize(target).toString().replace('\\', '/'));
        }
        return new Unpacked(written, rebased);
    }

    /** The entry keys {@link #writeConfig} would rebase — for the import preview, which writes nothing. */
    public static List<String> rebaseTargets(Bundle bundle, Path configDir) {
        String from = sourcePrefix(bundle);
        String to = targetPrefix(configDir.toAbsolutePath().normalize());
        List<String> out = new ArrayList<>();
        bundle.configEntries().forEach((key, body) -> {
            if (rebase(key, body, from, to) != null) out.add(key);
        });
        return out;
    }

    /**
     * The rebased text of one entry, or {@code null} when nothing changes.
     *
     * <p>A textual prefix swap rather than a parse/re-serialise round trip, deliberately: the exporter's
     * contract is that a config's bytes travel verbatim, and re-emitting TOON would strip the operator's
     * comments and reflow the file for what is meant to be a path correction. {@code spaces/<id>/} is a
     * distinctive literal, so a match is a path into that space by construction.
     *
     * <p>Limit, stated rather than papered over: only the <b>relative</b> {@code spaces/<id>/} form is
     * recognised. A deployment whose {@code -Dspaces.root} puts spaces somewhere else writes some other
     * prefix, which is left verbatim — a missed rebase the operator can see and fix, never a wrong one.
     *
     * <p>Re-importing into the source space is a no-op ({@code from.equals(to)}) in the normal layout, where
     * spaces sit under the working directory. Where they do not, the swap rewrites the relative form to the
     * target's absolute one: a cosmetic change, but still pointing at the space that owns the files.
     */
    private static String rebase(String key, byte[] body, String from, String to) {
        if (from == null || from.equals(to) || !key.endsWith(".toon")) return null;
        String text = new String(body, StandardCharsets.UTF_8);
        return text.contains(from) ? text.replace(from, to) : null;
    }

    /** {@code spaces/<source_space>/} from the manifest, or {@code null} when the bundle names no space. */
    private static String sourcePrefix(Bundle bundle) {
        Object id = bundle.manifest().get("source_space");
        String s = id == null ? "" : String.valueOf(id).trim();
        return s.isEmpty() ? null : "spaces/" + s + "/";
    }

    /**
     * The target space's own path prefix, as a config would spell it: the config dir's parent relative to
     * the working directory (that is what {@code dirs.*} and a schema reference resolve against), falling
     * back to the absolute path when the space lives outside it. Empty for a single-tenant root, which
     * correctly turns {@code spaces/alpha/data/x} into a plain {@code data/x}.
     */
    private static String targetPrefix(Path configRoot) {
        Path base = configRoot.getParent();
        if (base == null) return "";
        Path cwd = Path.of("").toAbsolutePath().normalize();
        String s = (base.startsWith(cwd) ? cwd.relativize(base) : base).toString().replace('\\', '/');
        return s.isEmpty() ? "" : s + "/";
    }
}
