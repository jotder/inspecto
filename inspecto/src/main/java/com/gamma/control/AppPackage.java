package com.gamma.control;

import com.gamma.util.ToonHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A signed App package, verified (PRODUCT-ASSEMBLY-1 PA-7d; the format is {@code tools/inspecto-app.mjs package --key}, PA-7c).
 * One {@code <id>/} folder holding the App, {@code MANIFEST.sha256} (one {@code <sha256>  <id>/<path>} line per file) and
 * {@code MANIFEST.sig} ({@code keyId=…}, {@code signature=<base64 Ed25519 over MANIFEST.sha256>}).
 *
 * <p>{@link #verify} refuses, naming why: a malformed or oversized archive, an entry that escapes its folder, a duplicate
 * entry, more than one App folder, no manifest or no signature, a key not in {@code trust}, a signature that does not match,
 * a file missing from the package or not matching its hash, a file the manifest does not list, an {@code app.toon} whose id
 * is not the folder name. Pure: nothing is written.
 *
 * @param files {@code path under the App folder -> bytes}, the manifest and signature excluded
 */
public record AppPackage(String id, String version, String keyId, Map<String, Object> app, Map<String, byte[]> files) {

    /** A package that cannot be installed; the message names why. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) { super(message); }
    }

    /** Hard caps, so an upload cannot exhaust the heap: the whole archive is bounded by the route, these by entry. */
    static final int MAX_ENTRIES = 5_000;
    static final long MAX_UNPACKED = 256L * 1024 * 1024;

    /** The App signer allowlist an install keeps ({@code keyId=base64 SPKI}): {@code -Dapps.signers}, else this, under the working directory. */
    public static final String SIGNERS_PROPERTY = "apps.signers";
    public static final String DEFAULT_SIGNERS = "conf/app-signers.properties";

    /** The keys this install trusts to sign an App: the issuer keys the core jar carries plus the install's signer allowlist. */
    public static Map<String, PublicKey> trustedSigners() {
        Map<String, PublicKey> out = new LinkedHashMap<>(com.gamma.module.Entitlements.trustedKeys());
        String p = System.getProperty(SIGNERS_PROPERTY);
        Path file = Path.of(p == null || p.isBlank() ? DEFAULT_SIGNERS : p.trim()).toAbsolutePath().normalize();
        if (!Files.exists(file)) return out;
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            for (String id : new TreeMap<>(props).keySet().stream().map(String::valueOf).toList())
                out.putIfAbsent(id, kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(props.getProperty(id).trim()))));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(file + " (the App signer allowlist) is unreadable: " + e.getMessage(), e);
        }
        return out;
    }

    /** Read and verify {@code zip} against {@code trust}. */
    public static AppPackage verify(byte[] zip, Map<String, PublicKey> trust) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")
                        || List.of(name.split("/", -1)).stream().anyMatch(s -> s.equals("..") || s.equals(".") || s.isEmpty()))
                    throw new Refused("the package entry '" + name + "' escapes its folder");
                for (String seg : name.split("/")) {
                    String why = unsafeSegment(seg);
                    if (why != null) throw new Refused("the package entry '" + name + "' " + why);
                }
                if (entries.containsKey(name)) throw new Refused("the package holds '" + name + "' twice");
                if (entries.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(name)))
                    throw new Refused("the package holds '" + name + "' twice (names that differ only in case are one file on Windows)");
                if (entries.size() >= MAX_ENTRIES) throw new Refused("the package holds more than " + MAX_ENTRIES + " files");
                byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_UNPACKED - total + 1));
                total += data.length;
                if (total > MAX_UNPACKED) throw new Refused("the package unpacks to more than " + (MAX_UNPACKED >> 20) + " MB");
                entries.put(name, data);
            }
        } catch (IOException bad) {
            throw new Refused("not a readable ZIP archive (" + bad.getMessage() + ")");
        }
        if (entries.isEmpty()) throw new Refused("not a package: the archive is empty, or not a ZIP archive");
        List<String> roots = entries.keySet().stream().map(n -> n.substring(0, n.indexOf('/') < 0 ? n.length() : n.indexOf('/'))).distinct().toList();
        if (roots.size() != 1 || entries.keySet().stream().anyMatch(n -> n.indexOf('/') < 0))
            throw new Refused("a package holds one App folder, found " + roots);
        String id = roots.get(0);
        byte[] manifest = entries.get(id + "/MANIFEST.sha256");
        if (manifest == null) throw new Refused("no MANIFEST.sha256 - package the App with tools/inspecto-app.mjs package");
        byte[] sig = entries.get(id + "/MANIFEST.sig");
        if (sig == null) throw new Refused("the package is not signed (no MANIFEST.sig) - package it with --key");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : new String(sig, StandardCharsets.UTF_8).split("\r?\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            if (fields.containsKey(k)) throw new Refused("MANIFEST.sig names '" + k + "' twice");
            fields.put(k, line.substring(eq + 1).trim());
        }
        String keyId = fields.getOrDefault("keyId", "");
        PublicKey key = trust.get(keyId);
        if (key == null) throw new Refused("signed by key '" + keyId + "', which this install does not trust " + new TreeMap<>(trust).keySet()
                + " - add the signer's line to " + DEFAULT_SIGNERS);
        boolean ok;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(manifest);
            ok = s.verify(Base64.getDecoder().decode(fields.getOrDefault("signature", "")));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            ok = false;
        }
        if (!ok) throw new Refused("the manifest signature does not match - the package was changed after signing, or signed with another key");
        Map<String, String> listed = new LinkedHashMap<>();
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            if (line.length() < 67 || line.charAt(64) != ' ' || line.charAt(65) != ' ') throw new Refused("a malformed MANIFEST.sha256 line: " + line);
            String name = line.substring(66);
            if (listed.put(name, line.substring(0, 64)) != null) throw new Refused("MANIFEST.sha256 lists '" + name + "' twice");
        }
        Map<String, byte[]> files = new TreeMap<>();
        for (Map.Entry<String, String> l : listed.entrySet()) {
            byte[] data = entries.get(l.getKey());
            if (data == null) throw new Refused(l.getKey() + " is in the manifest but not in the package");
            if (!sha256(data).equals(l.getValue())) throw new Refused(l.getKey() + " does not match its manifest hash");
            files.put(l.getKey().substring(id.length() + 1), data);
        }
        for (String name : entries.keySet())
            if (!listed.containsKey(name) && !name.equals(id + "/MANIFEST.sha256") && !name.equals(id + "/MANIFEST.sig"))
                throw new Refused(name + " is in the package but not in the manifest");
        byte[] appToon = files.get("app.toon");
        if (appToon == null) throw new Refused("the package has no app.toon");
        Map<String, Object> app;
        try {
            app = ToonHelper.decode(new String(appToon, StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            throw new Refused("app.toon does not read: " + bad.getMessage());
        }
        if (!id.equals(text(app, "id"))) throw new Refused("the package folder is '" + id + "' but app.toon names '" + text(app, "id") + "'");
        if (!id.matches("[a-z][a-z0-9-]{1,40}")) throw new Refused("App id '" + id + "' is not a valid id");
        return new AppPackage(id, text(app, "version"), keyId, app, files);
    }

    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /**
     * Why one path segment cannot be written as named on every filesystem a bundle runs on, or null. Windows drops a trailing dot
     * or space ({@code .installed-by.} lands as {@code .installed-by}), refuses {@code ?*<>|"} and control characters, and maps
     * device names; a hidden file (a dot name) is refused too, except {@code .gitkeep}, so a package can never write the marker
     * or record the server keeps beside it.
     */
    static String unsafeSegment(String seg) {
        if (seg.endsWith(".") || seg.endsWith(" ")) return "ends with a dot or a space";
        for (char c : seg.toCharArray()) if (c < 0x20 || "?*<>|\"".indexOf(c) >= 0) return "holds a character a filename cannot carry";
        if (RESERVED.matcher(seg).matches()) return "is a device name on Windows";
        if (seg.startsWith(".") && !seg.equals(".gitkeep")) return "is a hidden file, which a package does not carry";
        return null;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || v instanceof Map<?, ?> || v instanceof Collection<?> ? "" : String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> m, String key) {
        return m.get(key) instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
    }

    static List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Collection<?> c) return c.stream().map(x -> String.valueOf(x).trim()).filter(s -> !s.isEmpty()).toList();
        if (v == null || v instanceof Map<?, ?>) return List.of();
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    /** The App's templates, from {@code app.toon}. */
    public List<String> templates() { return list(app, "templates"); }

    /** The App's Add-ons, from {@code app.toon requires.addons}. */
    public List<String> requiredAddons() { return list(section(app, "requires"), "addons"); }
}
