package com.gamma.la.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable pins on the published versions of ONE index (D-7 design 5, step D7-2): a Draft pins, at fork, the version it read, and
 * {@link IndexStore#gc} never deletes a pinned version while the pin is unexpired.
 *
 * <p><b>Storage.</b> One small file, {@code pins.json}, inside the index directory ({@code <root>/<dataset>/<mappingHash>/}). It
 * is REWRITTEN whole (temp name, fsync, ATOMIC_MOVE), not appended, because unpin, expiry and moving a pin are all removals and
 * the set is tiny: a reader sees the old or the new set, never a torn one. Every read-modify-write, and the whole of a gc sweep,
 * runs under one per-directory lock (a JVM monitor plus a {@code .pins.lock} file lock for other processes), so a pin and a gc
 * can never interleave: either the pin lands first and gc sees it, or gc ran first and the pin is refused (version gone).
 *
 * <p><b>Fail closed.</b> A missing file is the empty set; a file that cannot be read or parsed THROWS, and gc then deletes nothing.
 */
public final class IndexPins {

    public static final String FILE_NAME = "pins.json";
    /** D-7 Q3 (signed 2026-10-03): a pinned version expires after this many days; the Draft then rebases. */
    public static final int PIN_TTL_DAYS = 30;
    /** A pin is reported by {@link #expiresSoon} once it is within this many days of expiry. */
    public static final int PIN_WARN_DAYS = 7;
    static final int FORMAT_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** One pin: {@code pinId} is opaque to this class (a Draft id); {@code version} is the NNNNNN of vNNNNNN. */
    public record Pin(String pinId, long version, Instant pinnedAt, Instant expiresAt) { }

    private final Path dir;
    private final Clock clock;

    public IndexPins(Path indexDirectory, Clock clock) {
        this.dir = indexDirectory.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** Pins {@code version} for {@link #PIN_TTL_DAYS} days from now; re-pinning the same id MOVES the pin. */
    public Pin pin(long version, String pinId) throws IOException {
        return pin(version, pinId, PIN_TTL_DAYS);
    }

    /** As {@link #pin(long, String)} with the caller's TTL. Refuses a version that is not a published directory (a stage never is one). */
    public Pin pin(long version, String pinId, int ttlDays) throws IOException {
        if (pinId == null || pinId.isBlank()) throw new IllegalArgumentException("pinId must not be blank");
        if (ttlDays < 1) throw new IllegalArgumentException("ttlDays must be >= 1");
        return locked(() -> {
            if (!Files.isDirectory(dir.resolve(String.format("v%06d", version))))
                throw new IllegalArgumentException("no published version v" + String.format("%06d", version) + " to pin in " + dir.getFileName());
            Instant now = clock.instant();
            Pin p = new Pin(pinId, version, now, now.plus(Duration.ofDays(ttlDays)));
            Map<String, Pin> all = readAll();
            all.put(pinId, p);
            write(all.values());
            return p;
        });
    }

    /** Releases a pin; false when there was none. */
    public boolean unpin(String pinId) throws IOException {
        return locked(() -> {
            Map<String, Pin> all = readAll();
            if (all.remove(pinId) == null) return false;
            write(all.values());
            return true;
        });
    }

    /** Every pin on file, expired ones included. */
    public List<Pin> listPins() throws IOException {
        return locked(() -> new ArrayList<>(readAll().values()));
    }

    /** Removes every pin expired at {@code now}; returns them. */
    public List<Pin> expire(Instant now) throws IOException {
        return locked(() -> {
            Map<String, Pin> all = readAll();
            List<Pin> gone = all.values().stream().filter(p -> !p.expiresAt().isAfter(now)).toList();
            if (gone.isEmpty()) return gone;
            gone.forEach(p -> all.remove(p.pinId()));
            write(all.values());
            return gone;
        });
    }

    /** The version numbers held by an UNEXPIRED pin at {@code now}. */
    public Set<Long> pinnedVersions(Instant now) throws IOException {
        return locked(() -> pinnedLocked(now));
    }

    /** Unexpired pins whose expiry is within {@code warnDays} of {@code now} (what a Draft's admission warns about). */
    public List<Pin> expiresSoon(Instant now, int warnDays) throws IOException {
        Instant horizon = now.plus(Duration.ofDays(warnDays));
        return locked(() -> readAll().values().stream()
                .filter(p -> p.expiresAt().isAfter(now) && !p.expiresAt().isAfter(horizon))
                .sorted(Comparator.comparing(Pin::expiresAt)).toList());
    }

    /** As {@link #expiresSoon(Instant, int)} with {@link #PIN_WARN_DAYS}. */
    public List<Pin> expiresSoon(Instant now) throws IOException {
        return expiresSoon(now, PIN_WARN_DAYS);
    }

    // internals, shared with IndexStore.gc (which holds the lock for its whole sweep)

    Set<Long> pinnedLocked(Instant now) throws IOException {
        Set<Long> out = new TreeSet<>();
        for (Pin p : readAll().values()) if (p.expiresAt().isAfter(now)) out.add(p.version());
        return out;
    }

    @FunctionalInterface
    interface Body<T> { T run() throws IOException; }

    <T> T locked(Body<T> body) throws IOException {
        synchronized (LOCKS.computeIfAbsent(dir, k -> new Object())) {
            if (!Files.isDirectory(dir)) return body.run();          // nothing published: reads see no file, a pin is refused
            try (FileChannel ch = FileChannel.open(dir.resolve(".pins.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = ch.lock()) {
                return body.run();
            }
        }
    }

    private Map<String, Pin> readAll() throws IOException {
        Map<String, Pin> out = new LinkedHashMap<>();
        String text;
        try {
            text = Files.readString(dir.resolve(FILE_NAME), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return out;
        }
        try {
            JsonNode n = JSON.readTree(text);
            if (n == null || !n.isObject() || n.path("formatVersion").asInt(-1) != FORMAT_VERSION || !n.path("pins").isArray())
                throw new IllegalArgumentException("unrecognised layout");
            for (JsonNode x : n.get("pins")) {
                String id = x.path("pinId").asText("");
                if (id.isBlank() || !x.path("version").canConvertToLong()) throw new IllegalArgumentException("bad pin entry");
                out.put(id, new Pin(id, x.get("version").asLong(), Instant.parse(x.path("pinnedAt").asText()), Instant.parse(x.path("expiresAt").asText())));
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("cannot read " + FILE_NAME + " of " + dir.getFileName() + " (refusing to guess which versions are pinned): " + e.getMessage(), e);
        }
        return out;
    }

    private void write(java.util.Collection<Pin> pins) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        ArrayNode arr = root.putArray("pins");
        for (Pin p : pins) {
            ObjectNode o = arr.addObject();
            o.put("pinId", p.pinId());
            o.put("version", p.version());
            o.put("pinnedAt", p.pinnedAt().toString());
            o.put("expiresAt", p.expiresAt().toString());
        }
        Path tmp = dir.resolve(FILE_NAME + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(root.toPrettyString().getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        Files.move(tmp, dir.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The unexpired pinned version DIRECTORIES of the index at {@code indexDir}, or null when the pins cannot be read (callers treat that as "all pinned"). */
    static Set<Path> pinnedDirectories(Path indexDir, Clock clock) {
        try {
            Path base = indexDir.toAbsolutePath().normalize();
            Set<Path> out = new HashSet<>();
            for (long v : new IndexPins(base, clock).pinnedVersions(clock.instant())) out.add(base.resolve(String.format("v%06d", v)));
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
