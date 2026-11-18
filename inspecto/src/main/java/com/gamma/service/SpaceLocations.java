package com.gamma.service;

import com.gamma.config.safety.PathJail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where a Space folder or a data folder may be put over HTTP ({@code SPACE-LOCATIONS-1}, plan D1, D2, D7): the checks
 * the {@code POST /spaces/_locations/check} route reports and every write route (attach, change data folder, create into
 * folders) runs before it touches anything, refusing on the first that fails.
 *
 * <p><b>D1, fail closed.</b> Nothing is accepted unless the launch setting {@code spaces.allowed.roots}
 * ({@code -Dspaces.allowed.roots}, or env {@code SPACES_ALLOWED_ROOTS}; a list separated by the platform path separator)
 * names folders the path lies under, judged on the CANONICAL path ({@link PathJail#canonical}: {@code ..}, links and
 * junctions resolved), so a link inside an allowed root that leads out of it is refused. Unset, the routes answer
 * {@code 409 SPACE_LOCATIONS_DISABLED}: without it anyone holding {@code canAdminister} could point the server at any
 * folder on the host. A Space folder inside the Spaces root needs no path at all (a plain create), so a path there is
 * refused rather than allowed.
 */
public final class SpaceLocations {

    /** The launch setting; {@link #ENV} is its environment twin. */
    public static final String PROPERTY = "spaces.allowed.roots";
    public static final String ENV = "SPACES_ALLOWED_ROOTS";

    /** The deploy kit's free-space rule ({@code deploy/kit/bin/preflight.*}): each writable folder needs this many GB. */
    static final String MIN_FREE_GB = "MIN_FREE_GB";
    private static final long DEFAULT_MIN_FREE_GB = 5;
    /** Test seam: the free bytes a folder needs, or a negative value for the {@link #MIN_FREE_GB} rule. */
    static volatile long minFreeBytesForTest = -1;

    /** What a path is checked for. */
    public enum Use {
        /** {@code POST /spaces/_locations/check kind=space}: a Space folder to attach, or an empty one to create into. */
        SPACE,
        /** An existing Space folder to attach. */
        ATTACH,
        /** A Space folder a new Space is created into: absent or empty. */
        CREATE,
        /** A data folder for a Space: absent, empty, or already stamped for it. */
        DATA,
        /** The data folder an attached Space's {@code space.toon} names: its own data may be there. */
        ATTACHED_DATA
    }

    /** One check: a stable name, whether it passed, and a plain reason either way. {@code status} is the HTTP answer
     *  a write route gives when it is the first to fail: 403 for a path outside the allowed folders, else 409. */
    public record Check(String check, boolean ok, String reason, int status) {
        public Map<String, Object> toMap() {
            return Map.of("check", check, "ok", ok, "reason", reason);
        }
    }

    /** A write route's refusal: the first failing {@link Check}. */
    public static final class Refused extends RuntimeException {
        private final int status;
        private final String code;

        Refused(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }

        public String code() { return code; }
    }

    /** {@code 409}: the setting is unset, so no route that takes a folder works. */
    public static final String DISABLED = "SPACE_LOCATIONS_DISABLED";
    /** {@code 409}: the Space holds data, so its data folder cannot be changed here. */
    public static final String HOLDS_DATA = "SPACE_HOLDS_DATA";

    private SpaceLocations() {}

    /** The allowed folders, canonical; empty when the setting is unset (the routes are then disabled). */
    public static List<Path> allowedRoots() {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank()) v = System.getenv(ENV);
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(File.pathSeparator)).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> PathJail.canonical(AppRoot.resolve(s))).toList();
    }

    /** {@link #DISABLED} unless the setting names at least one folder. */
    public static void requireEnabled() {
        if (allowedRoots().isEmpty())
            throw new Refused(409, DISABLED, "Space folder and data folder locations are switched off on this server: set "
                    + "the launch setting " + PROPERTY + " (or " + ENV + ") to the folders they may be put under");
    }

    /**
     * A path as an operator wrote it: absolute, or relative to the app root ({@link AppRoot}). Refused (422 by the
     * caller) when blank, a network path or a URI - never a folder on this server's disk.
     */
    public static Path parse(String value, String field) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("'" + field + "' must name a folder");
        // Any two leading separators, in either direction or mixed ("/\host", "\/host"), name a network share on
        // Windows; refused before anything touches the disk, so no request opens a connection to another machine.
        if (s.length() > 1 && isSeparator(s.charAt(0)) && isSeparator(s.charAt(1)))
            throw new IllegalArgumentException("'" + field + "' is a network path; name a folder on this server");
        if (s.matches("^[A-Za-z][A-Za-z0-9+.-]+:.*"))
            throw new IllegalArgumentException("'" + field + "' is a URI; name a folder on this server");
        Path resolved;
        try {
            resolved = AppRoot.resolve(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + field + "' is not a valid folder path: " + e.getMessage());
        }
        // and whatever the spelling, a path whose root is a share (\\host\share\) is refused too
        Path root = resolved.getRoot();
        if (root != null && root.toString().length() > 1
                && isSeparator(root.toString().charAt(0)) && isSeparator(root.toString().charAt(1)))
            throw new IllegalArgumentException("'" + field + "' is a network path; name a folder on this server");
        return resolved;
    }

    private static boolean isSeparator(char c) {
        return c == '\\' || c == '/';
    }

    /**
     * How {@code p} is written into a {@code <id>.ref} or {@code space.toon data_root}: relative to the app root when
     * it lies under it (so the install moves as one), else absolute. Forward slashes, which every platform reads.
     */
    static String written(Path p) {
        Path a = p.toAbsolutePath().normalize();
        Path app = AppRoot.current();
        return (a.startsWith(app) && !a.equals(app) ? app.relativize(a) : a).toString().replace('\\', '/');
    }

    /**
     * Every check for {@code path} used as {@code use}, in the order a write route applies them.
     *
     * @param spacesRoot the Spaces root
     * @param hosted     every hosted Space: id to {base, data folder}
     * @param id         the Space the path is for (may be null for {@link Use#SPACE}: the folder name is then the id)
     * @param base       that Space's folder, for a data folder's stamp ({@code null} for a Space folder)
     * @param idTaken    whether an id is already hosted, or taken on disk by a folder or a {@code <id>.ref}
     */
    static List<Check> checks(Path path, Use use, Path spacesRoot, Map<String, Path[]> hosted, String id, Path base,
                              java.util.function.Predicate<String> idTaken) {
        boolean spaceFolder = use == Use.SPACE || use == Use.ATTACH || use == Use.CREATE;
        String what = spaceFolder ? "Space folder" : "data folder";
        Path p = PathJail.canonical(path);
        Path root = PathJail.canonical(spacesRoot);
        List<Check> out = new ArrayList<>();

        List<Path> allowed = allowedRoots();
        boolean inAllowed = allowed.stream().anyMatch(p::startsWith);
        out.add(check("allowed", inAllowed, inAllowed ? "lies under an allowed folder"
                : p + " is not under any folder " + PROPERTY + " allows (" + allowed + ")", 403));

        Path app = PathJail.canonical(AppRoot.current());
        String install = null;
        if (app.startsWith(p)) install = "it is or contains the install folder " + app;
        else if (spaceFolder && p.startsWith(root) && !p.equals(root))
            install = "it lies inside the Spaces root " + root + ", where a Space needs no folder path: leave the path out";
        else if (p.startsWith(root) || root.startsWith(p)) install = "it is, contains or lies inside the Spaces root " + root;
        out.add(check("install", install == null, install == null
                ? "outside the install folder and the Spaces root" : "the " + what + " is refused: " + install, 409));

        String overlap = null;
        for (Map.Entry<String, Path[]> h : hosted.entrySet()) {
            boolean self = h.getKey().equals(id);
            for (Path other : h.getValue()) {
                if (other == null || overlap != null) continue;
                Path o = PathJail.canonical(other);
                // a data folder may be the one its own Space already uses; a Space folder overlaps nothing of its own
                if (self && use == Use.DATA && !o.equals(PathJail.canonical(h.getValue()[0])) && o.equals(p)) continue;
                if (p.startsWith(o) || o.startsWith(p))
                    overlap = "it overlaps Space '" + h.getKey() + "' at " + other;
            }
        }
        if (overlap == null && base != null) {   // a data folder never sits in, or holds, its own Space folder
            Path b = PathJail.canonical(base);
            Path holder = b.getParent();   // nor the folder holding it: the boot refuses that too (SpaceDataRoot.stake)
            if (p.startsWith(b) || b.startsWith(p)) overlap = "it overlaps its own Space folder " + base;
            else if (holder != null && (p.startsWith(holder) || holder.startsWith(p)))
                overlap = "it lies in " + holder + ", the folder that holds its Space folder - pick a folder outside it";
        }
        out.add(check("overlap", overlap == null, overlap == null ? "overlaps no other Space"
                : "the " + what + " is refused: " + overlap, 409));

        if (spaceFolder) {
            Path fn = path.toAbsolutePath().normalize().getFileName();
            String name = fn == null ? "" : fn.toString();
            String why = !SpaceId.isValid(name) ? "the folder name '" + name + "' is not a valid Space id ([a-z0-9-], 1-63 chars)"
                    : id != null && !id.equals(name) ? "the folder name '" + name + "' must be the Space id '" + id + "'"
                    : idTaken.test(name) ? "a Space '" + name + "' already exists" : null;
            out.add(check("name", why == null, why == null ? "the folder name '" + name + "' is a free Space id" : why, 409));
        }

        boolean exists = Files.isDirectory(path);
        Path parent = path.toAbsolutePath().normalize().getParent();
        boolean reachable = use == Use.ATTACH ? exists : exists || (parent != null && Files.isDirectory(parent));
        out.add(check("reachable", reachable, reachable ? (exists ? "the folder exists" : "the folder can be created in " + parent)
                : use == Use.ATTACH ? "the folder " + path + " does not exist"
                : "neither the folder nor its parent " + parent + " exists (is the volume mounted?)", 409));

        Path existing = exists ? path : parent;
        boolean writable = reachable && existing != null && Files.isWritable(existing);
        out.add(check("writable", writable, writable ? "the server can write there"
                : "the server cannot write to " + existing, 409));

        long need = minFreeBytes();
        long free = reachable && existing != null ? usable(existing) : -1;
        boolean roomy = free >= need;
        out.add(check("free-space", roomy, free < 0 ? "free space cannot be read" : (free / (1024L * 1024 * 1024)) + " GB free"
                + (roomy ? "" : ", under " + MIN_FREE_GB + "=" + need / (1024L * 1024 * 1024)), 409));

        if (!spaceFolder) out.add(stamp(path, base));
        if (use != Use.ATTACHED_DATA) out.add(content(path, use, base));
        return out;
    }

    /** The data folder's stamp ({@code .inspecto-space}) must name {@code base} when there is one. */
    static Check stamp(Path dataFolder, Path base) {
        Path stamp = dataFolder.resolve(SpaceDataRoot.STAMP);
        if (!Files.exists(stamp)) return check("stamp", true, "no Space has claimed it", 409);
        boolean mine;
        try {
            mine = base != null && SpaceDataRoot.ownedBy(dataFolder, base);
        } catch (IOException e) {
            mine = false;
        }
        return check("stamp", mine, mine ? "it is already this Space's data folder"
                : "the data folder belongs to another Space (its " + SpaceDataRoot.STAMP + " names it)", 409);
    }

    private static Check content(Path path, Use use, Path base) {
        boolean empty = isEmpty(path);
        return switch (use) {
            case ATTACH -> {
                String why = Files.isDirectory(path) ? SpaceManager.notASpaceReason(path) : "the folder does not exist";
                yield check("content", why == null, why == null ? "it holds a Space" : "it is not a Space: " + why, 409);
            }
            case CREATE -> check("content", empty, empty ? "it is empty" : "the Space folder is not empty", 409);
            case SPACE -> {
                String why = Files.isDirectory(path) ? SpaceManager.notASpaceReason(path) : "absent";
                yield check("content", empty || why == null, empty ? "it is empty: a new Space can be created there"
                        : why == null ? "it holds a Space: it can be attached"
                        : "it is neither empty nor a Space (" + why + ")", 409);
            }
            case DATA -> {
                boolean owned = !empty && stamp(path, base).ok() && Files.exists(path.resolve(SpaceDataRoot.STAMP));
                yield check("content", empty || owned, empty ? "it is empty" : owned ? "it is already this Space's data folder"
                        : "the data folder is not empty and no Space has claimed it", 409);
            }
            case ATTACHED_DATA -> throw new IllegalArgumentException("an attached Space's own data may be in its data folder");
        };
    }

    /** Whether {@code dir} is absent or holds nothing. */
    static boolean isEmpty(Path dir) {
        if (!Files.exists(dir)) return true;
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> kids = Files.list(dir)) {
            return kids.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /** The first failing check as a refusal, or nothing when every check passed. */
    static void refuseFirst(List<Check> checks) {
        for (Check c : checks)
            if (!c.ok()) throw new Refused(c.status(), c.status() == 403 ? "PATH_JAIL_VIOLATION" : "CONFLICT", c.reason());
    }

    private static Check check(String name, boolean ok, String reason, int status) {
        return new Check(name, ok, reason, status);
    }

    private static long minFreeBytes() {
        if (minFreeBytesForTest >= 0) return minFreeBytesForTest;
        long gb = DEFAULT_MIN_FREE_GB;
        String v = System.getenv(MIN_FREE_GB);
        if (v != null && !v.isBlank()) {
            try {
                gb = Long.parseLong(v.trim());
            } catch (NumberFormatException ignored) {
                // the deploy kit reads it as an int too; a malformed value keeps the default
            }
        }
        return gb * 1024L * 1024 * 1024;
    }

    private static long usable(Path p) {
        try {
            return Files.getFileStore(p).getUsableSpace();
        } catch (IOException e) {
            return -1;
        }
    }
}
