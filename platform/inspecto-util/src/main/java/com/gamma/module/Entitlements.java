package com.gamma.module;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Signed entitlements (PRODUCT-ASSEMBLY-1 PA-7a; decisions D-PA22 offline signed file, D-PA23 no file = today's behaviour).
 *
 * <p>The install's {@code conf/entitlement.toon} (or {@code -Dentitlement.file}) and its detached signature
 * {@code entitlement.sig} - base64 Ed25519 over the file's exact bytes - are verified against the issuer keys the core jar
 * carries ({@value #KEYS_RESOURCE}; a key beside an install cannot be trusted by editing a file). {@code tools/entitle.mjs}
 * issues them.
 *
 * <ul>
 *   <li><b>No file:</b> {@link #current()} is empty and every check here answers "entitled" - today's behaviour.</li>
 *   <li><b>A file that does not verify</b> (no signature, a changed byte, an unknown key id, a bad field) throws
 *       {@link Invalid}: the boot fails naming the file. It is never treated as "no file".</li>
 *   <li><b>A verified file narrows</b> the Installed gate: an OPTIONAL or PROVIDER module it does not list is not entitled,
 *       and so are the features and Job types only such modules provide. Base and internal modules always are.</li>
 * </ul>
 *
 * <p>PA-7b (D-PA24): {@link #state()} is OK / EXPIRING ({@value #WARN_DAYS} days left) / GRACE ({@value #GRACE_DAYS} days
 * past expiry, warnings only) / READ_ONLY (after the grace, or a trial build without its file). Read-only is enforced by the
 * control plane's dispatch (423), the Pipeline scheduler and {@code JobService}. A renewed file is picked up without a
 * restart ({@link #current()}).
 */
public final class Entitlements {

    /** Where the install's entitlement is: {@code -Dentitlement.file}, else {@value #DEFAULT_FILE} under the working directory. */
    public static final String FILE_PROPERTY = "entitlement.file";
    public static final String DEFAULT_FILE = "conf/entitlement.toon";
    /** The issuer public keys this build trusts: {@code keyId=base64(SPKI DER)}, written by {@code entitle.mjs keygen}. */
    public static final String KEYS_RESOURCE = "META-INF/inspecto/entitlement-keys.properties";
    public static final String SCHEMA = "inspecto.entitlement/1";
    /** The words a refusal and {@code /bootstrap.featureReasons} use for a feature the entitlement leaves out. */
    public static final String NOT_ENTITLED = "not in this installation's entitlement";

    /** PA-7b (D-PA24): the days after expiry that only warn; read-only starts the day after the last of them. */
    public static final int GRACE_DAYS = 14;
    /** PA-7b: an administrator is warned this many days before expiry. */
    public static final int WARN_DAYS = 30;
    /** PA-7b: how often a running server looks for a renewed file (a stat of two files, never a re-verify unless changed). */
    static final long RELOAD_CHECK_MS = 5_000;

    /** What the entitlement allows today (PA-7b, D-PA24). */
    public enum State {
        /** No file, in a build that does not need one (D-PA23: today's behaviour). */
        NONE,
        /** Valid, and not near its expiry (or no expiry). */
        OK,
        /** Valid, {@value #WARN_DAYS} days or fewer left. */
        EXPIRING,
        /** Expired, within the {@value #GRACE_DAYS}-day grace: everything still runs, administrators are warned. */
        GRACE,
        /** Past the grace, or a trial build without its file: reads only. */
        READ_ONLY
    }

    private static final Set<String> ALWAYS = Set.of("base", "internal");

    /** null = not read yet; empty = no file. */
    private static volatile Optional<Entitlement> current;
    private static volatile Map<String, PublicKey> trustOverride;
    private static volatile List<ModuleManifest> manifests;
    private static volatile java.time.Clock clock = java.time.Clock.systemDefaultZone();
    /** The two files' size + mtime when last read, and when they were last looked at. */
    private static volatile String stamp = "";
    private static volatile long checkedAt;
    /** Why a replacement file was not taken (the last verified one keeps running), or null. */
    private static volatile String problem;
    /** Why this build needs a file (a trial Distribution), or null. Set once at boot by the control plane. */
    private static volatile String requiredBecause;

    private Entitlements() {}

    /** An entitlement file that is present but cannot be trusted. The message names the file and why. */
    public static final class Invalid extends IllegalStateException {
        public Invalid(String message) { super(message); }
    }

    /**
     * The install's entitlement, verified, or empty when there is no file. Throws {@link Invalid} for a file that does not
     * verify on the FIRST read (the boot). Afterwards (PA-7b) a changed file is picked up within {@link #RELOAD_CHECK_MS}
     * without a restart: a replacement that verifies takes over; one that does not, or a removed file, leaves the last
     * verified entitlement in force and names the {@link #problem()} - a running server never drops to "no file".
     */
    public static Optional<Entitlement> current() {
        Optional<Entitlement> c = current;
        if (c == null) {
            synchronized (Entitlements.class) {
                c = current;
                if (c == null) {
                    Path f = file();
                    c = read(f);
                    stamp = stampOf(f);
                    checkedAt = System.currentTimeMillis();
                    current = c;
                }
            }
            return c;
        }
        long now = System.currentTimeMillis();
        if (now - checkedAt >= RELOAD_CHECK_MS) refresh(now);
        Optional<Entitlement> r = current;
        return r != null ? r : current();   // expectBuild cleared it meanwhile: read it again
    }

    private static synchronized void refresh(long now) {
        if (now - checkedAt < RELOAD_CHECK_MS) return;
        checkedAt = now;
        Path f = file();
        String s = stampOf(f);
        if (s.equals(stamp)) return;
        stamp = s;
        Optional<Entitlement> before = current;
        try {
            Optional<Entitlement> next = read(f);
            if (next.isEmpty() && before != null && before.isPresent()) {
                problem = f + " was removed; the entitlement read earlier stays in force until the server restarts";
                return;
            }
            current = next;
            problem = null;
            org.slf4j.LoggerFactory.getLogger(Entitlements.class).info("entitlement reloaded from {}: {}", f,
                    next.map(e -> e.id() + ", expires " + (e.expires() == null ? "never" : e.expires())).orElse("none"));
        } catch (Invalid bad) {
            problem = bad.getMessage() + " - the entitlement read earlier stays in force";
            org.slf4j.LoggerFactory.getLogger(Entitlements.class).error("entitlement not reloaded: {}", problem);
        }
    }

    private static String stampOf(Path file) {
        StringBuilder b = new StringBuilder();
        for (Path p : List.of(file, signatureOf(file))) {
            try {
                b.append(Files.size(p)).append('@').append(Files.getLastModifiedTime(p).toMillis()).append(';');
            } catch (IOException absent) {
                b.append("-;");
            }
        }
        return b.toString();
    }

    /** Why a renewed file was not taken (the last verified one is still in force), or null. */
    public static String problem() { return problem; }

    /** Today, by the server's clock (a test pins it with {@link #clockForTests}). */
    public static LocalDate today() { return LocalDate.now(clock); }

    /** A trial build needs its file: without one it runs read-only, naming {@code why}. Called once at boot. */
    public static void requireFile(String why) { requiredBecause = why; }

    /** The Product (null when the build carries no descriptor) and Edition this build is; every read refuses a file for others. */
    private static volatile String buildProduct;
    private static volatile String buildEdition;

    /**
     * Called once at boot by the control plane, BEFORE the first {@link #current()}: a file issued for another Product or Edition
     * is then {@link Invalid} - at boot (the boot fails) and on a renewal (the last verified file stays in force).
     */
    public static synchronized void expectBuild(String product, String edition) {
        buildProduct = product;
        buildEdition = edition;
        current = null;   // an engine service may have read it already: read it again under the expectation
    }

    /** The state today. */
    public static State state() {
        Optional<Entitlement> e = current();
        if (e.isEmpty()) return requiredBecause == null ? State.NONE : State.READ_ONLY;
        LocalDate exp = e.get().expires();
        if (exp == null) return State.OK;
        LocalDate t = today();
        if (!t.isAfter(exp)) return t.plusDays(WARN_DAYS).isBefore(exp) ? State.OK : State.EXPIRING;
        return t.isAfter(exp.plusDays(GRACE_DAYS)) ? State.READ_ONLY : State.GRACE;
    }

    /** True when only reads are allowed (D-PA24): ingest, Jobs, Pipeline runs and config saves are refused. */
    public static boolean readOnly() { return state() == State.READ_ONLY; }

    /** The first day the install is read-only (the day after the grace), or null when it never expires / has no file. */
    public static LocalDate readOnlyFrom() {
        return current().map(Entitlement::expires).map(x -> x.plusDays(GRACE_DAYS + 1L)).orElse(null);
    }

    /** One line saying why the install is read-only, or null when it is not. */
    public static String readOnlyReason() {
        if (!readOnly()) return null;
        Optional<Entitlement> e = current();
        if (e.isEmpty()) return "this " + requiredBecause;
        return "this installation's entitlement expired on " + e.get().expires() + " and its " + GRACE_DAYS
                + "-day grace ended - install a renewed entitlement (conf/entitlement.toon and .sig) to resume; reads continue";
    }

    /** What every signed-in caller may see: the state and, when it applies, when or why reads only. */
    public static Map<String, Object> publicSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        State s = state();
        m.put("installed", current().isPresent());
        m.put("state", s.name().toLowerCase(java.util.Locale.ROOT).replace("_", "-"));
        LocalDate from = readOnlyFrom();
        if (from != null && s != State.OK) m.put("readOnlyFrom", from.toString());
        String why = readOnlyReason();
        if (why != null) m.put("reason", why);
        return m;
    }

    /** The administrator's summary: {@link #publicSummary} plus the file's own fields and any reload {@link #problem}. */
    public static Map<String, Object> adminSummary() {
        Map<String, Object> m = new LinkedHashMap<>(current().map(e -> e.summary(today())).orElse(Map.of()));
        m.putAll(publicSummary());
        if (problem != null) m.put("problem", problem);
        m.put("forAdministrator", true);   // the shell warns an administrator only (D-PA23 Home line, expiring, grace)
        return m;
    }

    /** The entitlement file this install reads. */
    public static Path file() {
        String p = System.getProperty(FILE_PROPERTY);
        return Path.of(p == null || p.isBlank() ? DEFAULT_FILE : p.trim()).toAbsolutePath().normalize();
    }

    /** {@code entitlement.toon} -> {@code entitlement.sig}, beside it. */
    public static Path signatureOf(Path file) {
        String n = file.getFileName().toString();
        return file.resolveSibling((n.endsWith(".toon") ? n.substring(0, n.length() - 5) : n) + ".sig");
    }

    private static Optional<Entitlement> read(Path file) {
        if (!Files.exists(file)) return Optional.empty();
        Path sig = signatureOf(file);
        try {
            if (!Files.exists(sig)) throw new Invalid(file + ": an entitlement needs its signature " + sig.getFileName() + " beside it");
            Entitlement e = verify(Files.readAllBytes(file), Files.readString(sig, StandardCharsets.US_ASCII), trustedKeys(), file);
            if (today().isBefore(e.notBefore()))
                throw new Invalid(file + ": entitlement '" + e.id() + "' is not valid before " + e.notBefore());
            String p = buildProduct, ed = buildEdition;
            if (p != null && !p.equals(e.product()))
                throw new Invalid(file + ": entitlement '" + e.id() + "' is for Product '" + e.product() + "', but this build is Product '" + p + "'");
            String wanted = "preview".equals(e.edition()) ? "advanced" : e.edition();   // a Preview bundle reports Advanced
            if (ed != null && !ed.equals(wanted))
                throw new Invalid(file + ": entitlement '" + e.id() + "' is for the " + e.edition() + " Edition, but this build is " + ed);
            return Optional.of(e);
        } catch (IOException e) {
            throw new Invalid(file + ": cannot be read (" + e.getMessage() + ")");
        }
    }

    /**
     * Verify {@code toon} against its base64 signature and {@code trust}, then parse it. Pure. Throws {@link Invalid} naming
     * {@code file} and the reason.
     */
    public static Entitlement verify(byte[] toon, String signatureBase64, Map<String, PublicKey> trust, Path file) {
        Map<String, Object> root;
        try {
            root = ToonHelper.decode(new String(toon, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new Invalid(file + ": not a readable entitlement (" + e.getMessage() + ")");
        }
        if (!SCHEMA.equals(text(root, "schema"))) throw new Invalid(file + ": schema is '" + text(root, "schema") + "', not " + SCHEMA);
        String keyId = text(root, "keyId");
        PublicKey key = trust.get(keyId);
        if (key == null) throw new Invalid(file + ": key id '" + keyId + "' is not an issuer key this build trusts " + new TreeMap<>(trust).keySet());
        boolean ok;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(toon);
            ok = s.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            ok = false;
        }
        if (!ok) throw new Invalid(file + ": the signature does not match - the file was changed after signing, or signed with another key");
        try {
            int nodes = Integer.parseInt(text(root, "nodes"));
            if (nodes < 1) throw new Invalid(file + ": nodes must be at least 1");
            String exp = text(root, "expires");
            return new Entitlement(required(root, "id", file), required(root, "customer", file), text(root, "title"),
                    required(root, "product", file), required(root, "edition", file).toLowerCase(), list(root, "apps"),
                    list(root, "addons"), list(root, "modules"), nodes, LocalDate.parse(required(root, "notBefore", file)),
                    exp.isEmpty() ? null : LocalDate.parse(exp), LocalDate.parse(required(root, "issued", file)), keyId, file);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new Invalid(file + ": a field does not read (" + e.getMessage() + ")");
        }
    }

    /** The issuer keys the core jar carries (or a test's). */
    public static Map<String, PublicKey> trustedKeys() {
        Map<String, PublicKey> o = trustOverride;
        if (o != null) return o;
        Map<String, PublicKey> out = new LinkedHashMap<>();
        try (InputStream in = Entitlements.class.getClassLoader().getResourceAsStream(KEYS_RESOURCE)) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(in);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            for (String id : new TreeMap<>(p).keySet().stream().map(String::valueOf).toList())
                out.put(id, kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(p.getProperty(id).trim()))));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(KEYS_RESOURCE + " is unreadable: " + e.getMessage(), e);
        }
        return out;
    }

    /** Whether this install may run module {@code m}: always for base and internal modules, and for every module without a file. */
    public static boolean moduleEntitled(ModuleManifest m) {
        Optional<Entitlement> e = current();
        return e.isEmpty() || ALWAYS.contains(m.offeringRole()) || e.get().modules().contains(m.id());
    }

    /** The ids of the given modules this install may not run (empty without a file). */
    public static Set<String> unentitledModules(Collection<ModuleManifest> all) {
        Set<String> out = new LinkedHashSet<>();
        if (current().isEmpty()) return out;
        for (ModuleManifest m : all) if (!moduleEntitled(m)) out.add(m.id());
        return out;
    }

    /** False only when a file is present and every module that provides {@code feature} is one it leaves out. */
    public static boolean featureEntitled(String feature) {
        if (current().isEmpty()) return true;
        List<ModuleManifest> providers = manifests().stream().filter(m -> m.provides().features().contains(feature)).toList();
        return providers.isEmpty() || providers.stream().anyMatch(Entitlements::moduleEntitled);
    }

    /** Every feature this build's modules provide that {@link #featureEntitled} answers false for (empty without a file). */
    public static Set<String> unentitledFeatures() {
        Set<String> out = new java.util.TreeSet<>();
        if (current().isEmpty()) return out;
        for (ModuleManifest m : manifests())
            for (String f : m.provides().features()) if (!featureEntitled(f)) out.add(f);
        return out;
    }

    /** As {@link #featureEntitled}, for a Job type id ({@code provides.jobTypes}). */
    public static boolean jobTypeEntitled(String jobType) {
        if (current().isEmpty()) return true;
        List<ModuleManifest> providers = manifests().stream().filter(m -> m.provides().jobTypes().contains(jobType)).toList();
        return providers.isEmpty() || providers.stream().anyMatch(Entitlements::moduleEntitled);
    }

    /** Whether the entitlement lists Add-on {@code addon}; true without a file. */
    public static boolean addonEntitled(String addon) {
        return current().map(e -> e.addons().contains(addon)).orElse(true);
    }

    /** True when an entitlement file is installed. */
    public static boolean installed() { return current().isPresent(); }

    private static List<ModuleManifest> manifests() {
        List<ModuleManifest> m = manifests;
        if (m == null) manifests = m = ModuleManifests.load(Entitlements.class.getClassLoader()).manifests();
        return m;
    }

    // ── test seams ──

    /** Test seam: trust exactly {@code keys} (null restores the jar's), and forget the cached file. */
    public static void trustForTests(Map<String, PublicKey> keys) {
        trustOverride = keys == null ? null : Map.copyOf(keys);
        reset();
    }

    /** Test seam: read the file again on the next call. */
    public static void reset() {
        current = null;
        stamp = "";
        checkedAt = 0;
        problem = null;
        requiredBecause = null;
        buildProduct = null;
        buildEdition = null;
    }

    /** Test seam: the server's clock (null restores the system clock). Never a system property: a customer could pin it. */
    public static void clockForTests(java.time.Clock c) {
        clock = c == null ? java.time.Clock.systemDefaultZone() : c;
    }

    /** Test seam: look for a renewed file on the next call instead of waiting {@link #RELOAD_CHECK_MS}. */
    public static void recheckNow() {
        checkedAt = 0;
    }

    private static String required(Map<String, Object> m, String key, Path file) {
        String v = text(m, key);
        if (v.isEmpty()) throw new Invalid(file + ": no " + key);
        return v;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || v instanceof Map<?, ?> || v instanceof Collection<?> ? "" : String.valueOf(v).trim();
    }

    private static List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Collection<?> c) return c.stream().map(x -> String.valueOf(x).trim()).filter(s -> !s.isEmpty()).toList();
        if (v == null || v instanceof Map<?, ?>) return List.of();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }
}
