package com.gamma.module;

import com.gamma.util.ToonHelper;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.util.jar.JarFile;
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
    public record Loaded(List<ModuleManifest> manifests, List<String> diagnostics, String hostBuildId) {
        public Loaded(List<ModuleManifest> manifests, List<String> diagnostics) { this(manifests, diagnostics, null); }
    }

    /** The manifest attribute every module jar carries (MODULE-REORG-1 P3a); {@code dev} = an unstamped local build. */
    public static final String BUILD_ID_ATTRIBUTE = "Inspecto-Build-Id";
    public static final String DEV_BUILD_ID = "dev";

    /** No host: the build-id check is skipped (see {@link #load(ClassLoader, Class)}). */
    public static Loaded load(ClassLoader loader) { return load(loader, null); }

    /**
     * @param host a class of the host jar (the processor): its jar's {@value #BUILD_ID_ATTRIBUTE} is the install's
     *             build id. {@code null}, an exploded directory or an unstamped/{@code dev} jar = unknown = no check.
     *             Likewise a module whose own jar is unstamped: absent stamp is never a mismatch.
     */
    public static Loaded load(ClassLoader loader, Class<?> host) {
        List<ModuleManifest> out = new ArrayList<>();
        List<String> diag = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Enumeration<URL> urls;
        try {
            urls = loader.getResources(RESOURCE);
        } catch (IOException e) {
            return new Loaded(List.of(), List.of("cannot enumerate " + RESOURCE + ": " + e.getMessage()), null);
        }
        String hostStamp = host == null ? null : hostStamp(host);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String text;
            try (var in = openUncached(url)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                diag.add(url + ": unreadable — " + e.getMessage());
                continue;
            }
            String stamp = jarStamp(url);
            for (String chunk : text.split("(?m)^" + SEPARATOR + "[ \\t]*\\r?$")) {
                if (chunk.isBlank()) continue;
                try {
                    ModuleManifest m = parse(chunk.strip());
                    if (!seen.add(m.id())) diag.add(url + ": duplicate module id '" + m.id() + "' ignored (first one wins)");
                    else out.add(m.withBuildId(stamp));
                } catch (RuntimeException e) {
                    diag.add(url + ": " + e.getMessage());
                }
            }
        }
        for (ModuleManifest m : out)
            if (ModuleActivator.mismatch(m, hostStamp))
                diag.add("module '" + m.id() + "': " + ModuleActivator.mismatchReason(m, hostStamp));
        return new Loaded(List.copyOf(out), List.copyOf(diag), hostStamp);
    }

    /** Uncached, so reading a manifest never leaves its jar open (a locked file on Windows). */
    private static java.io.InputStream openUncached(URL url) throws IOException {
        java.net.URLConnection c = url.openConnection();
        c.setUseCaches(false);
        return c.getInputStream();
    }

    /** The build stamp of the jar a resource URL points into; {@code null} for a directory, no manifest or {@code dev}. */
    static String jarStamp(URL url) {
        if (!"jar".equals(url.getProtocol())) return null;
        try {
            File jar = new File(((JarURLConnection) url.openConnection()).getJarFileURL().toURI());
            return stampOf(jar);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostStamp(Class<?> host) {
        try {
            var src = host.getProtectionDomain().getCodeSource();
            return src == null ? null : stampOf(new File(src.getLocation().toURI()));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stampOf(File f) throws IOException {
        if (!f.isFile()) return null;
        try (JarFile jf = new JarFile(f)) {
            var mf = jf.getManifest();
            String v = mf == null ? null : mf.getMainAttributes().getValue(BUILD_ID_ATTRIBUTE);
            return v == null || v.isBlank() || DEV_BUILD_ID.equals(v.trim()) ? null : v.trim();
        }
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
