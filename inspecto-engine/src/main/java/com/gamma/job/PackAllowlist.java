package com.gamma.job;

import com.gamma.config.safety.PathJail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Trust gate T1 for Job Packs ({@code docs/superpower/parser-plugins-trust-design.md} §3, slice P1): the
 * operator-owned {@code -Djobs.packs.allowlist} file of SHA-256 hashes a pack jar must match to load.
 *
 * <p>Format — one jar per line, {@code sha256sum} output works as-is:
 * <pre>{@code <64 hex sha256>  <file name>  [note ...]}</pre>
 * Only the hash decides; the file name and note are for the human reading the file. Blank lines and lines
 * starting with {@code #} are skipped. ⚠ ONE malformed line refuses EVERY jar — a half-readable allowlist is
 * not a trust decision.
 *
 * <p>Fail closed throughout: no property ⇒ every jar refused; unreadable file ⇒ every jar refused. The cause
 * each refusal carries is the {@code job.pack.rejected} signal's {@code cause} and the inventory row's.
 */
final class PackAllowlist {

    static final String PROPERTY = "jobs.packs.allowlist";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final Set<String> hashes;
    /** Non-null ⇒ the allowlist as a whole is unusable, and every jar is refused with this cause. */
    private final String refusal;

    private PackAllowlist(Set<String> hashes, String refusal) {
        this.hashes = hashes;
        this.refusal = refusal;
    }

    /**
     * The configured allowlist file, or {@code null} when the property is unset. 🔴 Refuses (throws) when the
     * file lies inside the packs dir or under any root the control plane can write — {@code assist.write.root},
     * {@code spaces.root}, or a {@link PathJail#allowedRoots()} root — because a writer who can both drop a jar
     * and approve it defeats the gate (threat A3). Called once at construction, so a mis-placed allowlist fails
     * the boot loudly instead of quietly trusting whatever lands beside it.
     */
    static Path configuredFile(Path packsDir) {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) return null;
        Path file = Path.of(v.trim()).toAbsolutePath().normalize();
        List<Path> writable = new ArrayList<>();
        writable.add(packsDir);
        for (String prop : List.of("assist.write.root", "spaces.root")) {
            String root = System.getProperty(prop);
            if (root != null && !root.isBlank()) writable.add(Path.of(root.trim()));
        }
        writable.addAll(PathJail.allowedRoots());
        for (Path root : writable)
            if (PathJail.contains(root, file))
                throw new IllegalStateException(PROPERTY + " '" + file + "' lies under '" + root.toAbsolutePath()
                        + "', which a Job Pack dropper or a control-plane write can reach; keep the allowlist in an "
                        + "operator-only location outside the packs dir and every write root");
        return file;
    }

    /** Read (or re-read, on every rescan) the allowlist. Never throws: an unusable file refuses every jar. */
    static PackAllowlist read(Path file) {
        if (file == null) return new PackAllowlist(Set.of(), "not trusted: no " + PROPERTY + " configured");
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | RuntimeException e) {
            return new PackAllowlist(Set.of(), "not trusted: " + PROPERTY + " " + file + " is unreadable: " + e);
        }
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String first = line.split("\\s+", 2)[0];
            if (!SHA256.matcher(first).matches())
                return new PackAllowlist(Set.of(), "not trusted: " + PROPERTY + " line " + (i + 1)
                        + " is not '<sha256> <file> [note]'");
            hashes.add(first.toLowerCase(Locale.ROOT));
        }
        return new PackAllowlist(Set.copyOf(hashes), null);
    }

    boolean allows(String sha256) {
        return refusal == null && sha256 != null && hashes.contains(sha256.toLowerCase(Locale.ROOT));
    }

    /** Throws the named refusal unless {@code sha256} — the hash of the STAGED bytes — is listed. */
    void check(String sha256) {
        if (refusal != null) throw new SecurityException(refusal);
        if (!allows(sha256)) throw new SecurityException("not trusted: sha256 " + sha256 + " is not in " + PROPERTY);
    }
}
