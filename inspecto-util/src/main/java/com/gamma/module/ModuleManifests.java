package com.gamma.module;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads every {@value #RESOURCE} on a class loader (MODULE-REORG-1 P2a). Fail-soft PER FILE: a bad file yields a
 * diagnostic naming it and never aborts the others — a broken manifest must not take the boot down.
 *
 * <p>⚠ <b>Shaded jars.</b> Every module ships a resource of the same name, so a shade without a merging
 * transformer keeps only the FIRST. The processor's shade therefore uses an {@code AppendingTransformer} on this
 * resource, and each file starts with a {@value #SEPARATOR} line; this loader splits on that line and treats each
 * chunk as one manifest. A chunk with no content is skipped.
 */
public final class ModuleManifests {
    private ModuleManifests() {}

    public static final String RESOURCE = "META-INF/inspecto/module.toon";
    public static final String SEPARATOR = "---";

    /** The manifests that parsed and validated, plus one diagnostic per problem. */
    public record Loaded(List<ModuleManifest> manifests, List<String> diagnostics) {}

    public static Loaded load(ClassLoader loader) {
        List<ModuleManifest> out = new ArrayList<>();
        List<String> diag = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Enumeration<URL> urls;
        try {
            urls = loader.getResources(RESOURCE);
        } catch (IOException e) {
            return new Loaded(List.of(), List.of("cannot enumerate " + RESOURCE + ": " + e.getMessage()));
        }
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String text;
            try (var in = url.openStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                diag.add(url + ": unreadable — " + e.getMessage());
                continue;
            }
            for (String chunk : text.split("(?m)^" + SEPARATOR + "[ \\t]*\\r?$")) {
                if (chunk.isBlank()) continue;
                try {
                    ModuleManifest m = parse(chunk.strip());
                    if (!seen.add(m.id())) diag.add(url + ": duplicate module id '" + m.id() + "' ignored (first one wins)");
                    else out.add(m);
                } catch (RuntimeException e) {
                    diag.add(url + ": " + e.getMessage());
                }
            }
        }
        return new Loaded(List.copyOf(out), List.copyOf(diag));
    }

    /** Parse and validate ONE manifest; throws IllegalArgumentException naming the problem. */
    public static ModuleManifest parse(String toon) {
        Map<String, Object> root = ToonHelper.decode(toon);
        String id = text(root, "id");
        if (id.isEmpty()) throw new IllegalArgumentException("id is required");
        String title = text(root, "title");
        Map<String, Object> p = section(root, "provides");
        Map<String, Object> r = section(root, "requires");
        String ent = text(root, "entitlementKey");
        return new ModuleManifest(id, title.isEmpty() ? id : title,
                oneOf(root, "buildRole", ModuleManifest.BUILD_ROLES),
                oneOf(root, "offeringRole", ModuleManifest.OFFERING_ROLES),
                oneOf(root, "bindingTime", ModuleManifest.BINDING_TIMES),
                new ModuleManifest.Provides(list(p, "features"), list(p, "contracts"), list(p, "capabilities"),
                        list(p, "configKinds"), list(p, "storeFamilies")),
                new ModuleManifest.Requires(list(r, "modules"), list(r, "contracts")),
                ent.isEmpty() ? null : ent);
    }

    private static String oneOf(Map<String, Object> m, String key, List<String> allowed) {
        String v = text(m, key);
        if (!allowed.contains(v)) throw new IllegalArgumentException(key + " must be one of " + allowed + ", was '" + v + "'");
        return v;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || v instanceof Map<?, ?> || v instanceof Collection<?> ? "" : String.valueOf(v).trim();
    }

    /** An empty TOON key can arrive as {@code {}} (memory: empty-toon-key-arrives-as-empty-object) — never null-check alone. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
    }

    private static List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Collection<?> c) return c.stream().map(x -> String.valueOf(x).trim()).filter(s -> !s.isEmpty()).toList();
        if (v == null || v instanceof Map<?, ?>) return List.of();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }
}
