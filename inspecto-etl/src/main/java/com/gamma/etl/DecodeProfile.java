package com.gamma.etl;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.safety.PathJail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The <b>Decode Profile</b> (GLOSSARY §5; parser-plugins trust design slices C1–C2): one vendor's ASN.1
 * decode settings kept once, in a satellite {@code .toon} file whose one top-level block is {@code asn1:} —
 * byte-for-byte the keys a Pipeline's {@code parsing.asn1} block takes — so N Pipelines (one per switch of
 * that vendor) name it with {@code asn1.profile_file} instead of repeating it. Not a registry kind, not a
 * transform language: a satellite file that travels, previews and jails like the grammar file.
 *
 * <p>The ONE overlay rule, shared by the load ({@code PipelineConfigParser.asn1PluginBlock}) and the preview
 * ({@code Asn1ParserPlugin}), so a profile that previews is the profile that ingests:
 * <ul>
 *   <li><b>Resolution.</b> {@code profile_file} must end {@code .toon} (checked before anything touches the
 *       disk), resolves beside the referring config ({@link PathJail#resolveConfigRef}) and is jailed
 *       ({@link PathJail#requireUnderAny} over {@link PathJail#allowedRoots()}) BEFORE the readability probe.
 *       Every relative ref INSIDE the profile ({@code grammar_file}, the {@code segments} values) resolves
 *       beside the PROFILE — returned absolute, so the downstream resolvers leave it alone and still jail it.</li>
 *   <li><b>Overlay.</b> Every key the referring {@code asn1:} block sets wins, key by key. {@code segments}
 *       is therefore replaced WHOLE, never merged (operator D5 2026-09-25): the record kinds a Pipeline
 *       loads must be readable off one file.</li>
 *   <li><b>No nesting</b>, and nothing but the {@code asn1:} block: anything else in the file would be
 *       silently ignored, so it is refused.</li>
 * </ul>
 */
public final class DecodeProfile {

    /** The key, inside an {@code asn1:} block, that names a Decode Profile. */
    public static final String KEY = "profile_file";
    private static final String FIELD = "asn1." + KEY;

    private DecodeProfile() {
    }

    /**
     * @param asn1 the merged block (the profile's keys under the referring block's), without {@code profile_file}
     * @param file the jailed profile file, or {@code null} when the block names none
     */
    public record Resolved(Map<String, Object> asn1, Path file) {
    }

    /**
     * Overlay the profile {@code asn1} names (if any) under it.
     *
     * @param asn1      the referring config's {@code asn1:} block
     * @param configDir the referring config's directory; {@code null} keeps the working-directory reading
     */
    @SuppressWarnings("unchecked")
    public static Resolved overlay(Map<String, Object> asn1, Path configDir) throws IOException {
        Object raw = asn1.get(KEY);
        String ref = raw == null ? "" : String.valueOf(raw).trim();
        if (ref.isEmpty()) {
            if (!asn1.containsKey(KEY)) return new Resolved(asn1, null);
            Map<String, Object> without = new LinkedHashMap<>(asn1);
            without.remove(KEY);
            return new Resolved(without, null);
        }
        if (!ref.toLowerCase(Locale.ROOT).endsWith(".toon"))
            throw new IllegalArgumentException(FIELD + " must name a Decode Profile .toon file, got: " + ref);
        Path file = PathJail.requireUnderAny(PathJail.allowedRoots(),
                PathJail.resolveConfigRef(configDir, ref, FIELD).toString(), FIELD);
        if (!Files.isRegularFile(file) || !Files.isReadable(file))
            throw new IllegalArgumentException(FIELD + " not readable: " + file);

        Map<String, Object> doc;
        try {
            doc = ConfigCodec.toMap(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException("Decode Profile " + file + ": " + bad.getMessage(), bad);
        }
        if (!(doc.get("asn1") instanceof Map<?, ?> profileBlock) || doc.size() != 1)
            throw new IllegalArgumentException("Decode Profile " + file + " must hold exactly one block, asn1: "
                    + "(got top-level keys " + doc.keySet() + ")");
        if (profileBlock.containsKey(KEY))
            throw new IllegalArgumentException("Decode Profile " + file + " names another profile_file — "
                    + "profiles do not nest");

        Path profileDir = file.getParent();
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) profileBlock).entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if ("grammar_file".equals(k) && v instanceof String s && !s.isBlank())
                v = besideProfile(profileDir, s, "asn1.grammar_file");
            else if ("segments".equals(k) && v instanceof Map<?, ?> segs) {
                Map<String, Object> resolved = new LinkedHashMap<>();
                for (Map.Entry<?, ?> s : segs.entrySet())
                    resolved.put(String.valueOf(s.getKey()), s.getValue() instanceof String path
                            && !path.startsWith("schema/")   // a registry ref is an id, not a path
                            ? besideProfile(profileDir, path, "asn1.segments." + s.getKey()) : s.getValue());
                v = resolved;
            }
            merged.put(k, v);
        }
        for (Map.Entry<String, Object> e : asn1.entrySet())
            if (!KEY.equals(e.getKey()) && e.getValue() != null) merged.put(e.getKey(), e.getValue());
        return new Resolved(merged, file);
    }

    private static String besideProfile(Path profileDir, String ref, String field) {
        return PathJail.resolveConfigRef(profileDir, ref.trim(), field).toString();
    }
}
