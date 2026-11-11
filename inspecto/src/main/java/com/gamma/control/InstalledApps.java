package com.gamma.control;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Apps installed into a running server (PRODUCT-ASSEMBLY-1 PA-7d; decisions D-PA22..D-PA25).
 *
 * <p><b>Where they live.</b> Each App unpacks into the install's own {@code apps/<id>/} ({@code -Dapps.root}, else {@code apps}
 * under the working directory - the bundle root), with {@code installed.toon} recording its version, signer and templates.
 * Its Space Templates are copied into {@code <spaces-root>/_templates/<template>/}, each marked {@value #MARKER} so a shipped
 * template is never replaced and removal takes only what the App brought.
 *
 * <p><b>What changes and what never does.</b> An install or upgrade changes the App's templates (so NEW Spaces get the new
 * version) and its nav (served by {@code /bootstrap} at once). A Space already made from a template is never touched: an
 * upgrade answers, per such Space, the file-by-file difference an administrator may apply through the normal config routes
 * (D-PA25). Removal takes the App's templates and nav away; its Spaces keep every file.
 */
public final class InstalledApps {

    public static final String ROOT_PROPERTY = "apps.root";
    public static final String DEFAULT_ROOT = "apps";
    /** Inside a template an installed App owns: {@code app=<id>} and {@code version=<v>}. */
    public static final String MARKER = ".installed-by";
    /** Inside a Space made from an installed App's template: {@code template}, {@code app}, {@code version}. */
    public static final String PROVENANCE = ".inspecto/created-from.toon";
    /** Beside it: {@code <sha256>  <config-relative path>} per config file, as the Space was created. */
    public static final String HASHES = ".inspecto/created-from.sha256";
    static final String RECORD = "installed.toon";
    /**
     * One change at a time across the whole install: a request builds its own {@code InstalledApps}, so the lock is the class's.
     * The routes hold it across plan and apply, so what was planned is what is applied.
     */
    static final Object LOCK = new Object();

    private final Path root;
    private final Path templates;
    private final Path spaces;

    /** @param spacesRoot the container root (Spaces and {@code _templates} live under it) */
    public InstalledApps(Path appsRoot, Path spacesRoot) {
        this.root = appsRoot;
        this.spaces = spacesRoot;
        this.templates = spacesRoot.resolve("_templates");
    }

    /** The install's apps root. */
    public static Path defaultRoot() {
        String p = System.getProperty(ROOT_PROPERTY);
        return Path.of(p == null || p.isBlank() ? DEFAULT_ROOT : p.trim()).toAbsolutePath().normalize();
    }

    /** One installed App. */
    public record Installed(String id, String title, String version, String keyId, String installedAt, List<String> templates,
                            Map<String, Object> app) {
        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", title);
            m.put("version", version);
            m.put("signedBy", keyId);
            m.put("installedAt", installedAt);
            m.put("templates", templates);
            return m;
        }
    }

    /** Every installed App, in id order. */
    public List<Installed> list() {
        if (!Files.isDirectory(root)) return List.of();
        List<Installed> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                if (d.getFileName().toString().startsWith(".")) continue;
                read(d.getFileName().toString()).ifPresent(out::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public Optional<Installed> read(String id) {
        Path d = root.resolve(id);
        if (!Files.isRegularFile(d.resolve(RECORD)) || !Files.isRegularFile(d.resolve("app.toon"))) return Optional.empty();
        try {
            Map<String, Object> rec = ToonHelper.decode(Files.readString(d.resolve(RECORD)));
            Map<String, Object> app = ToonHelper.decode(Files.readString(d.resolve("app.toon")));
            return Optional.of(new Installed(id, AppPackage.text(app, "title"), AppPackage.text(rec, "version"), AppPackage.text(rec, "keyId"),
                    AppPackage.text(rec, "installedAt"), AppPackage.list(rec, "templates"), app));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The App that owns template {@code t} ({@value #MARKER}), or empty for a shipped template or none. */
    Optional<String> ownerOf(String t) {
        Path m = templates.resolve(t).resolve(MARKER);
        if (!Files.isRegularFile(m)) return Optional.empty();
        try {
            for (String line : Files.readAllLines(m)) if (line.startsWith("app=")) return Optional.of(line.substring(4).trim());
        } catch (IOException ignored) {
            // an unreadable marker reads as no owner: refused as a shipped template, never replaced
        }
        return Optional.empty();
    }

    // ── plan (the dry run) ──

    /** Which kind of config a template file is, for the dry-run diff ({@code null} = a file that is not a component). */
    static String kindOf(String rel) {
        String n = rel.substring(rel.lastIndexOf('/') + 1);
        if (!rel.startsWith("config/")) return rel.startsWith("data/samples/") ? "Sample data" : null;
        if (n.endsWith("_pipeline.toon")) return "Pipeline";
        if (n.endsWith("_job.toon")) return "Job";
        if (rel.contains("/registry/datasets/")) return "Dataset";
        if (rel.contains("/registry/alert-rules/")) return "Alert Rule";
        if (n.endsWith("_schema.toon")) return "Schema";
        return "Config";
    }

    /** The template files a package brings: {@code template -> (path under the template -> bytes)}. */
    static Map<String, Map<String, byte[]>> templateFiles(AppPackage pkg) {
        Map<String, Map<String, byte[]>> out = new TreeMap<>();
        for (String t : pkg.templates()) out.put(t, new TreeMap<>());
        for (Map.Entry<String, byte[]> f : pkg.files().entrySet()) {
            String p = f.getKey();
            if (!p.startsWith("templates/")) continue;
            String rest = p.substring("templates/".length());
            int slash = rest.indexOf('/');
            if (slash < 0) continue;
            Map<String, byte[]> t = out.get(rest.substring(0, slash));
            if (t != null) t.put(rest.substring(slash + 1), f.getValue());
        }
        return out;
    }

    /**
     * What installing ({@code upgrade == false}) or upgrading {@code pkg} would do, and the refusals that stop it. Writes
     * nothing. {@code findings} non-empty = refused (409).
     */
    public Map<String, Object> plan(AppPackage pkg, boolean upgrade, List<String> shippedApps) {
        List<String> findings = new ArrayList<>();
        Optional<Installed> current = read(pkg.id());
        if (shippedApps.contains(pkg.id())) findings.add("App '" + pkg.id() + "' ships with this build; it is upgraded with the Platform, not here");
        else if (!upgrade && current.isPresent()) findings.add("App '" + pkg.id() + "' is installed already (version " + current.get().version() + ") - upgrade it with PUT /apps/" + pkg.id());
        else if (upgrade && current.isEmpty()) findings.add("App '" + pkg.id() + "' is not installed - install it with POST /apps");
        Map<String, Map<String, byte[]>> tf = templateFiles(pkg);
        for (Map.Entry<String, Map<String, byte[]>> t : tf.entrySet()) {
            if (!t.getKey().matches("[a-z0-9][a-z0-9-]{0,62}")) findings.add("template '" + t.getKey() + "' is not a valid id");
            else if (t.getValue().isEmpty() || !t.getValue().containsKey("template.toon"))
                findings.add("template '" + t.getKey() + "' is listed in app.toon but the package has no templates/" + t.getKey() + "/template.toon");
            else if (Files.exists(templates.resolve(t.getKey()))) {
                Optional<String> owner = ownerOf(t.getKey());
                if (owner.isEmpty()) findings.add("template '" + t.getKey() + "' is a template this build ships - an App never replaces it");
                else if (!owner.get().equals(pkg.id())) findings.add("template '" + t.getKey() + "' belongs to the installed App '" + owner.get() + "'");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", pkg.id());
        out.put("version", pkg.version());
        out.put("signedBy", pkg.keyId());
        current.ifPresent(c -> out.put("installedVersion", c.version()));
        List<Object> templatesOut = new ArrayList<>();
        for (Map.Entry<String, Map<String, byte[]>> t : tf.entrySet()) {
            Map<String, byte[]> before = upgrade ? filesUnder(templates.resolve(t.getKey())) : Map.of();
            templatesOut.add(Map.of("template", t.getKey(), "files", fileDiff(before, t.getValue())));
        }
        if (upgrade && current.isPresent())   // a template the old version shipped and the new one drops
            for (String gone : current.get().templates())
                if (!tf.containsKey(gone)) templatesOut.add(Map.of("template", gone, "removed", true));
        out.put("templates", templatesOut);
        out.put("nav", ProductDescriptor.navOf(pkg.app()).stream().map(n -> Map.of("id", n.id(), "path", n.path(), "label", n.label())).toList());
        if (upgrade) out.put("spaces", spaceOffers(pkg.id(), tf));
        out.put("findings", findings);
        return out;
    }

    /** {@code [{path, kind, change}]} - change is added / changed / removed; unchanged files are left out. */
    static List<Object> fileDiff(Map<String, byte[]> before, Map<String, byte[]> after) {
        List<Object> out = new ArrayList<>();
        TreeSet<String> all = new TreeSet<>(before.keySet());
        all.addAll(after.keySet());
        for (String p : all) {
            if (p.equals(MARKER)) continue;
            byte[] a = before.get(p), b = after.get(p);
            String change = a == null ? "added" : b == null ? "removed" : java.util.Arrays.equals(a, b) ? null : "changed";
            if (change == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p);
            String kind = kindOf(p);
            if (kind != null) m.put("kind", kind);
            m.put("change", change);
            out.add(m);
        }
        return out;
    }

    /**
     * D-PA25: for each Space made from one of the App's templates, what the upgrade would change in its config if an
     * administrator applied it: {@code new} (the template adds a file the Space lacks), {@code update} (the Space has not
     * changed the file since it was created, so taking the new one loses nothing), {@code customised} (the Space changed it -
     * left alone), {@code dropped} (the new template no longer ships a file the Space has - the Space keeps it).
     *
     * <p>"Not changed since created" reads the SHA-256 each config file had when the Space was created ({@value #HASHES}, written
     * by {@code SpaceManager}), so it holds whichever version made the Space and whatever the server stamped at creation. A
     * Space without hashes falls back to comparing with the template installed now. A Space whose record is unreadable is left
     * out, never a reason to refuse the upgrade.
     */
    List<Object> spaceOffers(String appId, Map<String, Map<String, byte[]>> next) {
        List<Object> out = new ArrayList<>();
        if (!Files.isDirectory(spaces)) return out;
        List<Path> all;
        try (Stream<Path> s = Files.list(spaces)) {
            all = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path space : all) {
            String sid = space.getFileName().toString();
            if (sid.startsWith("_") || sid.startsWith(".")) continue;
            Map<String, Object> p = provenance(space);
            if (p == null) continue;
            String tpl = AppPackage.text(p, "template");
            if (!appId.equals(AppPackage.text(p, "app")) || !next.containsKey(tpl)) continue;
            Map<String, String> seeded = hashes(space);
            Map<String, byte[]> old = filesUnder(templates.resolve(tpl).resolve("config"));
            Map<String, byte[]> fresh = new TreeMap<>();
            next.get(tpl).forEach((k, v) -> { if (k.startsWith("config/")) fresh.put(k.substring("config/".length()), v); });
            Map<String, byte[]> mine = filesUnder(space.resolve("config"));
            List<Object> files = new ArrayList<>();
            TreeSet<String> names = new TreeSet<>(fresh.keySet());
            names.addAll(old.keySet());
            for (String f : names) {
                byte[] o = render(f, old.get(f), sid), n = render(f, fresh.get(f), sid), m = mine.get(f);
                boolean untouched = m != null && (seeded != null ? AppPackage.sha256(m).equals(seeded.get(f)) : o != null && sameConfig(f, m, o));
                String change;
                if (n == null) change = m == null ? null : "dropped";
                else if (m == null) change = "new";
                else if (sameConfig(f, m, n)) change = null;
                else change = untouched ? "update" : "customised";
                if (change == null) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("path", "config/" + f);
                String kind = kindOf("config/" + f);
                if (kind != null) e.put("kind", kind);
                e.put("change", change);
                files.add(e);
            }
            out.add(Map.of("space", sid, "template", tpl, "fromVersion", AppPackage.text(p, "version"), "files", files));
        }
        return out;
    }

    /** A Space's {@value #PROVENANCE}, or null when it has none or it does not read. */
    private static Map<String, Object> provenance(Path space) {
        Path prov = space.resolve(PROVENANCE);
        if (!Files.isRegularFile(prov)) return null;
        try {
            return ToonHelper.decode(Files.readString(prov));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** {@code config-relative path -> sha256} as the Space was created ({@value #HASHES}), or null when it has none. */
    private static Map<String, String> hashes(Path space) {
        Path h = space.resolve(HASHES);
        if (!Files.isRegularFile(h)) return null;
        Map<String, String> out = new TreeMap<>();
        try {
            for (String line : Files.readAllLines(h)) if (line.length() > 66 && line.charAt(64) == ' ') out.put(line.substring(66), line.substring(0, 64));
        } catch (IOException e) {
            return null;
        }
        return out;
    }

    /**
     * Whether a Space's file holds what a template file says. Byte-equal, or - for TOON - equal once decoded, setting aside the
     * keys the server stamps onto every seeded Job when a Space is created ({@code TemplateSeedGate} -> {@code JobAuthority}):
     * who created and last updated it, their roles, and the Link Analysis owner. Without that, every Job a template seeds would
     * read as "customised" the moment the Space exists.
     */
    static boolean sameConfig(String path, byte[] space, byte[] template) {
        if (java.util.Arrays.equals(space, template)) return true;
        if (!path.endsWith(".toon") || space == null || template == null) return false;
        try {
            return unstamped(ToonHelper.decode(new String(space, StandardCharsets.UTF_8)))
                    .equals(unstamped(ToonHelper.decode(new String(template, StandardCharsets.UTF_8))));
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static final java.util.Set<String> STAMPED = java.util.Set.of(com.gamma.job.JobConfig.CREATED_BY,
            com.gamma.job.JobConfig.UPDATED_BY, com.gamma.job.JobConfig.UPDATED_BY_ROLES, JobAuthority.LA_OWNER);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unstamped(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>(doc);
        STAMPED.forEach(out::remove);
        if (out.get("job") instanceof Map<?, ?> j) {
            Map<String, Object> job = new LinkedHashMap<>((Map<String, Object>) j);
            STAMPED.forEach(job::remove);
            out.put("job", job);
        }
        return out;
    }

    /** A template file as a Space made from it holds it: a {@code .toon} file with {@code ${SPACE}} replaced, any other as is (as SpaceManager copies). */
    private static byte[] render(String path, byte[] b, String spaceId) {
        if (b == null || !path.endsWith(".toon")) return b;
        return new String(b, StandardCharsets.UTF_8).replace("${SPACE}", spaceId).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> filesUnder(Path dir) {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList())
                out.put(dir.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    // ── act ──

    /**
     * Install or upgrade {@code pkg} (the plan's findings were empty), as nearly all-or-nothing as a filesystem allows: every
     * directory - each template and the App folder - is written to a staging folder first, so a file that cannot be written
     * changes nothing; only then are they swapped in, each by a rename, with the previous copy put back if a swap fails.
     */
    public Installed apply(AppPackage pkg, boolean upgrade) throws IOException {
        Optional<Installed> before = read(pkg.id());
        Map<String, Map<String, byte[]>> tf = templateFiles(pkg);
        Map<String, byte[]> app = new TreeMap<>(pkg.files());
        String rec = String.join("\n", "id: " + pkg.id(), "version: " + pkg.version(), "keyId: " + pkg.keyId(),
                "installedAt: \"" + Instant.now() + "\"", "templates[" + tf.size() + "]:" + (tf.isEmpty() ? "" : " " + String.join(",", tf.keySet())), "");
        app.put(RECORD, rec.getBytes(StandardCharsets.UTF_8));
        List<Path[]> staged = new ArrayList<>();   // {staging, parent, name}
        try {
            for (Map.Entry<String, Map<String, byte[]>> t : tf.entrySet()) {
                Map<String, byte[]> files = new TreeMap<>(t.getValue());
                files.put(MARKER, ("app=" + pkg.id() + "\nversion=" + pkg.version() + "\n").getBytes(StandardCharsets.UTF_8));
                staged.add(new Path[] {stage(templates, t.getKey(), files), templates, Path.of(t.getKey())});
            }
            staged.add(new Path[] {stage(root, pkg.id(), app), root, Path.of(pkg.id())});
        } catch (IOException | RuntimeException e) {
            for (Path[] s : staged) deleteQuietly(s[0]);
            throw e;
        }
        for (Path[] s : staged) swapIn(s[1], s[2].toString(), s[0]);
        if (upgrade && before.isPresent())
            for (String gone : before.get().templates()) if (!tf.containsKey(gone) && ownerOf(gone).filter(pkg.id()::equals).isPresent()) deleteQuietly(templates.resolve(gone));
        return read(pkg.id()).orElseThrow();
    }

    /** Remove an installed App: its templates (only those it owns) and its folder. Spaces made from it keep every file. */
    public List<String> remove(String id) throws IOException {
        Installed app = read(id).orElseThrow();
        for (String t : app.templates()) if (ownerOf(t).filter(id::equals).isPresent()) deleteDir(templates.resolve(t));
        deleteDir(root.resolve(id));
        return spacesMadeFrom(id);
    }

    /** The Spaces whose provenance names App {@code id} (a Space whose record does not read is left out). */
    public List<String> spacesMadeFrom(String id) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(spaces)) return out;
        try (Stream<Path> s = Files.list(spaces)) {
            for (Path space : s.filter(Files::isDirectory).sorted().toList()) {
                Map<String, Object> p = provenance(space);
                if (p != null && id.equals(AppPackage.text(p, "app"))) out.add(space.getFileName().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Write {@code files} into a fresh {@code parent/.staging-<name>-<n>}; returns it. Nothing outside it is touched. */
    private static Path stage(Path parent, String name, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(parent);
        Path target = parent.resolve(name).normalize();
        if (!parent.normalize().equals(target.getParent())) throw new IllegalArgumentException("'" + name + "' escapes " + parent);
        Path staging = parent.resolve(".staging-" + name + "-" + System.nanoTime());
        try {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                Path p = staging.resolve(f.getKey()).normalize();
                if (!p.startsWith(staging)) throw new IllegalArgumentException("'" + f.getKey() + "' escapes its folder");
                Files.createDirectories(p.getParent());
                Files.write(p, f.getValue());
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(staging);
            throw e;
        }
        return staging;
    }

    /** Swap {@code staging} in for {@code parent/<name>}; the previous copy is put back if the swap fails, and deleted after. */
    private static void swapIn(Path parent, String name, Path staging) throws IOException {
        Path target = parent.resolve(name);
        Path old = parent.resolve(".old-" + name + "-" + System.nanoTime());
        try {
            if (Files.exists(target)) move(target, old);
            move(staging, target);
        } catch (IOException | RuntimeException e) {
            if (!Files.exists(target) && Files.exists(old)) move(old, target);
            deleteQuietly(staging);
            throw e;
        }
        deleteQuietly(old);   // a leftover .old-* folder is harmless (the gallery and the registry skip dot names)
    }

    private static void deleteQuietly(Path dir) {
        try {
            deleteDir(dir);
        } catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(InstalledApps.class).warn("could not delete {}: {}", dir, e.toString());
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** Whether {@code version} satisfies a space-separated range such as {@code >=4.0 <5} ({@code >= > <= < =}). "dev" admits any. */
    static boolean satisfies(String version, String range) {
        if (range == null || range.isBlank() || version == null || version.isBlank() || "dev".equals(version)) return true;
        int[] v = parts(version);
        for (String tok : range.trim().split("\\s+")) {
            String op = tok.startsWith(">=") ? ">=" : tok.startsWith("<=") ? "<=" : tok.startsWith(">") ? ">" : tok.startsWith("<") ? "<" : tok.startsWith("=") ? "=" : "=";
            int c = compare(v, parts(tok.substring(tok.startsWith(op) ? op.length() : 0)));
            boolean ok = switch (op) { case ">=" -> c >= 0; case "<=" -> c <= 0; case ">" -> c > 0; case "<" -> c < 0; default -> c == 0; };
            if (!ok) return false;
        }
        return true;
    }

    private static int[] parts(String v) {
        String[] s = v.replaceFirst("^v", "").split("[-+]")[0].split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < s.length; i++) {
            try { out[i] = Integer.parseInt(s[i]); } catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }
}
