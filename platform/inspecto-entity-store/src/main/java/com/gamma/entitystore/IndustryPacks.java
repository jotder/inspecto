package com.gamma.entitystore;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The installed {@link IndustryPack}s, discovered once from the classpath (LA-EDITIONS-1 §3; bundled in every edition,
 * Basic included — ED-5). Every jar may ship an index {@value #INDEX} naming its packs ({@code packs[n]: a,b}); each
 * pack is the file set under {@value #DIR}{@code <id>/}:
 * <pre>
 *   industry-pack.toon               manifest: id, name, description, space_templates, pattern_packs, profile
 *   industry-pack-terms.toon         terms[n]{key,label,abbreviation,definition} - the relabels
 *   industry-pack-entity-types.toon  entity_types[n] - core Entity Types restated (normaliser, masked, classifications)
 *   industry-pack-link-kinds.toon    link_kinds[n] - the link kinds the pack's terms may name
 * </pre>
 * ⛔ <b>Fail closed.</b> A pack whose files are missing, do not parse or break any {@link IndustryPack#parse} rule, or
 * whose id two jars both declare, is <b>refused whole</b> and reported in {@link Catalogue#refused()} — never partly
 * installed. A Space naming a refused or absent pack reads the neutral vocabulary, and the settings route refuses to
 * select it (422).
 */
public final class IndustryPacks {

    private IndustryPacks() {}

    /** The per-jar index of bundled packs. */
    public static final String INDEX = "META-INF/inspecto/industry-packs.toon";
    /** The directory each pack's file set lives under, as {@code <DIR><id>/}. */
    public static final String DIR = "META-INF/inspecto/industry-packs/";

    /** A pack that was not installed, and why. */
    public record Refused(String id, String reason) {}

    /** What one discovery found. */
    public record Catalogue(List<IndustryPack> installed, List<Refused> refused) {
        public Catalogue {
            installed = List.copyOf(installed);
            refused = List.copyOf(refused);
        }

        /** The installed pack {@code id}, or {@code null} (absent, refused, or {@code generic}). */
        public IndustryPack find(String id) {
            if (id == null) return null;
            for (IndustryPack p : installed) if (p.id().equals(id)) return p;
            return null;
        }
    }

    private static volatile Catalogue bundled;

    /** The packs this build ships, discovered from this class's loader on first use. */
    public static Catalogue bundled() {
        Catalogue c = bundled;
        if (c == null) {
            synchronized (IndustryPacks.class) {
                c = bundled;
                if (c == null) bundled = c = load(IndustryPacks.class.getClassLoader());
            }
        }
        return c;
    }

    /** Discover every pack {@code loader} can see; never throws — a pack that cannot be read is refused. */
    public static Catalogue load(ClassLoader loader) {
        Map<String, Integer> declared = new LinkedHashMap<>();
        List<Refused> refused = new ArrayList<>();
        Enumeration<URL> indexes;
        try {
            indexes = loader.getResources(INDEX);
        } catch (IOException e) {
            return new Catalogue(List.of(), List.of(new Refused("*", "the pack index cannot be listed: " + e.getMessage())));
        }
        for (URL url : Collections.list(indexes)) {
            List<?> ids;
            try (InputStream in = url.openStream()) {
                Object raw = ToonHelper.decode(new String(in.readAllBytes(), StandardCharsets.UTF_8)).get("packs");
                if (!(raw instanceof List<?> l)) throw new IllegalArgumentException("'packs' must be a list");
                ids = l;
            } catch (IOException | RuntimeException e) {
                refused.add(new Refused("*", "the pack index " + url + " is unreadable: " + e.getMessage()));
                continue;
            }
            for (Object o : ids) declared.merge(String.valueOf(o).trim(), 1, Integer::sum);
        }
        List<IndustryPack> installed = new ArrayList<>();
        for (Map.Entry<String, Integer> d : declared.entrySet()) {
            String id = d.getKey();
            if (d.getValue() > 1) {
                refused.add(new Refused(id, "declared by " + d.getValue() + " pack indexes - an id names one pack"));
                continue;
            }
            if (!IndustryPack.ID.matcher(id).matches()) {
                refused.add(new Refused(id, "pack id must match " + IndustryPack.ID.pattern()));
                continue;
            }
            try {
                installed.add(IndustryPack.parse(id, read(loader, id, "industry-pack.toon"), read(loader, id, "industry-pack-terms.toon"),
                        read(loader, id, "industry-pack-entity-types.toon"), read(loader, id, "industry-pack-link-kinds.toon")));
            } catch (IOException | RuntimeException e) {
                refused.add(new Refused(id, e.getMessage()));
            }
        }
        return new Catalogue(installed, refused);
    }

    private static Map<String, Object> read(ClassLoader loader, String id, String file) throws IOException {
        try (InputStream in = loader.getResourceAsStream(DIR + id + "/" + file)) {
            if (in == null) throw new IOException("pack '" + id + "' has no " + file);
            try {
                return ToonHelper.decode(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(file + " does not parse: " + e.getMessage(), e);
            }
        }
    }
}
