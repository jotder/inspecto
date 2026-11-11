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
 * <p>Read once and cached; PA-7b adds renewal without a restart. Expiry is carried and reported here; what it does is PA-7b.
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

    private static final Set<String> ALWAYS = Set.of("base", "internal");

    /** null = not read yet; empty = no file. */
    private static volatile Optional<Entitlement> current;
    private static volatile Map<String, PublicKey> trustOverride;
    private static volatile List<ModuleManifest> manifests;

    private Entitlements() {}

    /** An entitlement file that is present but cannot be trusted. The message names the file and why. */
    public static final class Invalid extends IllegalStateException {
        public Invalid(String message) { super(message); }
    }

    /** The install's entitlement, verified, or empty when there is no file. Throws {@link Invalid} for a file that does not verify. */
    public static Optional<Entitlement> current() {
        Optional<Entitlement> c = current;
        if (c == null) {
            synchronized (Entitlements.class) {
                c = current;
                if (c == null) current = c = read(file());
            }
        }
        return c;
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
            return Optional.of(verify(Files.readAllBytes(file), Files.readString(sig, StandardCharsets.US_ASCII), trustedKeys(), file));
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
