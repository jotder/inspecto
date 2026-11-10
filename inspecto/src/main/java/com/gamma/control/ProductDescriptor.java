package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Product this server was built as (PRODUCT-ASSEMBLY-1 PA-3; plan docs/superpower/2026-11-10-product-assembly-plan.md §11,
 * words in GLOSSARY.md §15 "Product assembly"). {@code tools/assemble.mjs} resolves it at build time and {@code package.ps1}
 * writes it to the bundle's {@code lib/product.json}; nothing here decides which Apps a Product has or which modules they need.
 *
 * <p><b>Where it is read from.</b> {@code -Dproduct.descriptor=<path>} when set (tests); otherwise {@value #FILE} in the
 * folder of the jar that holds this class - {@code lib/} of a bundle. A {@code target/classes} run, a test and a bundle built
 * before PA-3 have none: the server then reports no Product ({@code /bootstrap.product} is {@code null}).
 *
 * <p><b>A descriptor that does not parse fails the boot</b> ({@link #current()} throws, naming the file). It can only come
 * from a broken build, and serving half a Product - a nav with an App missing - is worse than serving none.
 *
 * <p><b>What it decides.</b> {@link #resolve} turns the declared Apps and nav into what this caller, in this Space, sees: an
 * App is installed when every module it needs is ACTIVE, and enabled when this Space has switched none of them off; a nav
 * entry is drawn when its App is installed and enabled, its {@code feature}
 * is true in the response's feature map (installed, enabled in this Space, in this Edition) and - only where an
 * Authenticator is active - the caller holds its {@code capability}. Pure: the caller supplies all four signals.
 */
public record ProductDescriptor(String id, String title, String shape, String host, String brand, String edition,
                                String distribution, List<App> apps) {

    /** The descriptor's schema id; anything else is refused. */
    public static final String SCHEMA = "inspecto.product/1";
    /** The system property that points at a descriptor (tests). */
    public static final String PROPERTY = "product.descriptor";
    /** The file name beside the jar. */
    public static final String FILE = "product.json";

    /** One App of the Product build: the modules its Add-ons need in this Edition and its nav in drawing order. */
    public record App(String id, String title, String version, List<String> modules, List<NavEntry> nav) {}

    /** One area a shell draws. {@code path} is relative to where the host mounts the App. */
    public record NavEntry(String id, String path, String label, String icon, String blurb, String feature, String capability) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private record Cached(String key, Optional<ProductDescriptor> value) {}
    private static volatile Cached cache;

    /**
     * The descriptor this server runs as, or empty when there is none.
     *
     * @throws IllegalStateException when a descriptor exists but cannot be read or does not parse, naming the file
     */
    public static Optional<ProductDescriptor> current() {
        Optional<Path> where = locate();
        String key = where.map(Path::toString).orElse("");
        Cached c = cache;
        if (c != null && c.key().equals(key)) return c.value();
        Optional<ProductDescriptor> v = where.filter(Files::isRegularFile).map(ProductDescriptor::read);
        cache = new Cached(key, v);
        return v;
    }

    /** {@code -Dproduct.descriptor}, else {@value #FILE} beside this class's jar; empty for an exploded class directory. */
    static Optional<Path> locate() {
        String p = System.getProperty(PROPERTY);
        if (p != null && !p.isBlank()) return Optional.of(Path.of(p.trim()).toAbsolutePath().normalize());
        try {
            var src = ProductDescriptor.class.getProtectionDomain().getCodeSource();
            if (src == null) return Optional.empty();
            Path jar = Path.of(src.getLocation().toURI());
            return Files.isRegularFile(jar) ? Optional.of(jar.toAbsolutePath().getParent().resolve(FILE)) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ProductDescriptor read(Path file) {
        try {
            return parse(Files.readString(file), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the Product descriptor " + file + ": " + e.getMessage(), e);
        }
    }

    /** Parse a descriptor; {@code where} names it in the error. */
    static ProductDescriptor parse(String json, String where) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the Product descriptor " + where + " is not JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) throw bad(where, "is not a JSON object");
        if (!SCHEMA.equals(text(root, "schema"))) throw bad(where, "has schema '" + text(root, "schema") + "', expected '" + SCHEMA + "'");
        String id = required(root, "id", where);
        List<App> apps = new ArrayList<>();
        JsonNode appsNode = root.path("apps");
        if (!appsNode.isArray()) throw bad(where, "has no apps array");
        for (JsonNode a : appsNode) {
            String appId = required(a, "id", where + " app");
            List<String> modules = new ArrayList<>();
            for (JsonNode m : a.path("modules")) modules.add(m.asText());
            List<NavEntry> nav = new ArrayList<>();
            for (JsonNode n : a.path("nav")) {
                String at = where + " App '" + appId + "' nav entry";
                nav.add(new NavEntry(required(n, "id", at), required(n, "path", at), required(n, "label", at),
                        text(n, "icon"), text(n, "blurb"), text(n, "feature"), text(n, "capability")));
            }
            apps.add(new App(appId, text(a, "title"), text(a, "version"), List.copyOf(modules), List.copyOf(nav)));
        }
        return new ProductDescriptor(id, required(root, "title", where), text(root, "shape"), text(root, "host"),
                text(root.path("brand"), "name"), text(root, "edition"), text(root, "distribution"), List.copyOf(apps));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String required(JsonNode n, String field, String where) {
        String v = text(n, field);
        if (v == null || v.isBlank()) throw bad(where, "has no " + field);
        return v;
    }

    private static IllegalStateException bad(String where, String what) {
        return new IllegalStateException("the Product descriptor " + where + " " + what);
    }

    /** What every caller may see, signed in or not - the sign-in page shows the Product's name. */
    public Map<String, Object> identity() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("shape", shape);
        m.put("host", host);
        m.put("brand", Map.of("name", brand == null ? title : brand));
        return m;
    }

    /**
     * The Apps and the nav this caller sees: {@code {apps: [{id, title, version, installed, enabled, missing, switchedOff}],
     * nav: [{app, id, path, label, icon?, blurb?}]}}. An App draws nav only when it is installed AND enabled - an entry with no
     * {@code feature} of its own still disappears when this Space switches the App's modules off.
     *
     * @param activeModules     ids of the modules this server runs ACTIVE (the set {@code GET /modules} reports)
     * @param enabledModules    ids of the ACTIVE modules this Space has not switched off (none of their features is disabled in
     *                          {@code modules.toon} - {@code GET /modules}' {@code enabledInSpace})
     * @param features          the response's feature map: a {@code feature} is on when its value is {@code Boolean.TRUE}
     * @param capabilities      the caller's capabilities
     * @param capabilitiesApply whether an Authenticator is active - without one there is no caller to check, as everywhere
     */
    public Map<String, Object> resolve(Set<String> activeModules, Set<String> enabledModules, Map<String, Object> features,
                                       Collection<String> capabilities, boolean capabilitiesApply) {
        List<Object> appsOut = new ArrayList<>();
        List<Object> navOut = new ArrayList<>();
        for (App a : apps) {
            List<String> missing = a.modules().stream().filter(mod -> !activeModules.contains(mod)).toList();
            List<String> switchedOff = a.modules().stream()
                    .filter(mod -> activeModules.contains(mod) && !enabledModules.contains(mod)).toList();
            boolean installed = missing.isEmpty();
            boolean enabled = installed && switchedOff.isEmpty();
            Map<String, Object> ao = new LinkedHashMap<>();
            ao.put("id", a.id());
            ao.put("title", a.title());
            ao.put("version", a.version());
            ao.put("installed", installed);
            ao.put("enabled", enabled);
            ao.put("missing", missing);
            ao.put("switchedOff", switchedOff);
            appsOut.add(ao);
            if (!enabled) continue;
            for (NavEntry n : a.nav()) {
                if (n.feature() != null && !Boolean.TRUE.equals(features.get(n.feature()))) continue;
                if (n.capability() != null && capabilitiesApply && !capabilities.contains(n.capability())) continue;
                Map<String, Object> no = new LinkedHashMap<>();
                no.put("app", a.id());
                no.put("id", n.id());
                no.put("path", n.path());
                no.put("label", n.label());
                if (n.icon() != null) no.put("icon", n.icon());
                if (n.blurb() != null) no.put("blurb", n.blurb());
                navOut.add(no);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apps", appsOut);
        out.put("nav", navOut);
        return out;
    }
}
