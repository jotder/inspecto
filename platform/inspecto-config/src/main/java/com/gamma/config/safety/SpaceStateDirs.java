package com.gamma.config.safety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The state zone of a Space, step 1: <b>readers only</b> (SE-4, D-SE13 — {@code docs/superpower/2026-11-11-space-evolution-plan.md}
 * §4.4). What the server writes about config moves, in a later release, from {@code spaces/<id>/config/<dir>} to
 * {@code spaces/<id>/state/<dir>}. This release only <i>reads</i> there, so a Space a newer release has moved (or half
 * moved) still reads every record:
 * <ul>
 *   <li><b>Read:</b> a record is read from {@code state/<dir>/<file>} when that file exists, else from
 *       {@code config/<dir>/<file>} ({@link #readable}); a listing holds both, the state one first, one entry per
 *       name ({@link #list}).</li>
 *   <li><b>Write:</b> always {@code config/<dir>} ({@link #writeDir}) — nothing is ever written under {@code state/}
 *       in this release. A record that also has a state twin is one a newer release owns: its store refuses to
 *       rewrite it ({@link #shadowed}), because the write would land in {@code config/} where no read ever sees it.</li>
 *   <li><b>Which roots:</b> only a Space's {@code config/} (a directory named {@code config} with a parent, the rule
 *       {@link PathJail#spaceDirOf} and {@link SpaceAuditDir} use). The single-tenant default Space's write root
 *       ({@code -Dassist.write.root}, no Space folder) is not one, so it reads {@code config/} only.</li>
 * </ul>
 * Nothing moves at boot and nothing here creates a directory. No link below the Space folder is followed:
 * a {@code state/}, {@code state/<dir>} or file that is a symlink or junction is never read ({@link #linkFree}).
 */
public final class SpaceStateDirs {

    private static final System.Logger log = System.getLogger(SpaceStateDirs.class.getName());

    /** The zone's folder name, beside {@code config/} (D-SE5). */
    public static final String STATE = "state";

    /**
     * The directories whose stores read through this class. Two of the zone's target directories are not here, for
     * reasons recorded in the Space anatomy page (§4.7): {@code publication-approvals/} (its deletes are its security,
     * and they cannot reach {@code state/} yet) and the sealed {@code audit/} (its stores write where they read).
     */
    public static final Set<String> DIRS = Set.of("pending-changes", "action-requests", "regulatory-reports",
            "screening-hits", "recon-state", "expectation-baselines", "agent");

    private SpaceStateDirs() {}

    /** {@code <space>/state} of the Space whose config root is {@code configRoot}, or {@code null} when it is not a Space's {@code config/}. */
    public static Path stateRoot(Path configRoot) {
        if (configRoot == null) return null;
        Path c = configRoot.toAbsolutePath().normalize();
        Path name = c.getFileName();
        if (name == null || !name.toString().equals("config") || c.getParent() == null) return null;
        return c.getParent().resolve(STATE);
    }

    /** Where the store of {@code dir} writes in this release: always {@code <config root>/<dir>}. */
    public static Path writeDir(Path configRoot, String dir) {
        return configRoot.toAbsolutePath().normalize().resolve(dir);
    }

    /**
     * The state twin of {@code p}, a path under {@code <space>/config/<dir>} for one of {@link #DIRS}:
     * {@code <space>/state/<dir>/…}; {@code null} for any other path.
     */
    public static Path twin(Path p) {
        Path[] located = locate(p);
        return located == null ? null : located[1];
    }

    /** {@code {<space>/state, twin of p}} for a path under {@code <space>/config/<dir>} of {@link #DIRS}, else {@code null}. */
    private static Path[] locate(Path p) {
        if (p == null) return null;
        Path n = p.toAbsolutePath().normalize();
        for (Path a = n.getParent(); a != null; a = a.getParent()) {   // the NEAREST config/ wins, as PathJail.spaceDirOf
            Path name = a.getFileName();
            if (name == null || !name.toString().equals("config")) continue;
            Path state = stateRoot(a);
            Path rel = a.relativize(n);
            if (state == null || !DIRS.contains(rel.getName(0).toString())) return null;
            return new Path[] {state, state.resolve(rel.toString())};
        }
        return null;
    }

    /** {@code p}'s state twin when that exists (and stays inside the Space's {@code state/}), else {@code p} itself. */
    public static Path readable(Path p) {
        Path t = existingTwin(p);
        return t != null ? t : p;
    }

    /** Whether {@code p} (a write target under {@code config/<dir>}) has a state twin — a record a newer release owns. */
    public static boolean shadowed(Path p) {
        return existingTwin(p) != null;
    }

    /**
     * The entries of {@code d} (a directory under {@code config/<dir>}) and of its state twin: the state ones first,
     * then those of {@code d} whose names the state twin does not hold. An absent directory lists nothing.
     */
    public static List<Path> list(Path d) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        Path t = existingTwin(d);
        if (t != null) for (Path p : listOne(t)) out.put(p.getFileName().toString(), p);
        for (Path p : listOne(d)) out.putIfAbsent(p.getFileName().toString(), p);
        return new ArrayList<>(out.values());
    }

    private static Path existingTwin(Path p) {
        Path[] located = locate(p);
        if (located == null || !Files.exists(located[1])) return null;
        Path t = located[1];
        if (!linkFree(located[0].getParent(), t)) {
            log.log(System.Logger.Level.WARNING, t + " passes through a link (state/ or a folder under it) and is not read");
            return null;
        }
        return t;
    }

    /**
     * Whether no folder or file from {@code base} (exclusive) down to {@code p} is a link (symlink or junction): the
     * real path of the deepest existing one of them equals {@code base}'s real path plus the same relative path. A
     * {@code state/} that is itself a link to outside the Space fails, which a containment check that resolves both
     * sides would let through. {@code base} itself may be a link (a Space kept elsewhere). Nothing existing below
     * {@code base} is link-free; an unanswerable filesystem is not.
     */
    public static boolean linkFree(Path base, Path p) {
        Path b = base.toAbsolutePath().normalize();
        Path e = p.toAbsolutePath().normalize();
        if (!e.startsWith(b)) return false;
        while (!e.equals(b) && !Files.exists(e, java.nio.file.LinkOption.NOFOLLOW_LINKS)) e = e.getParent();
        if (e.equals(b)) return true;
        try {
            return e.toRealPath().equals(b.toRealPath().resolve(b.relativize(e).toString()));
        } catch (IOException | RuntimeException unanswerable) {
            return false;
        }
    }

    private static List<Path> listOne(Path d) throws IOException {
        if (!Files.isDirectory(d)) return List.of();
        try (Stream<Path> s = Files.list(d)) {
            return s.toList();
        }
    }
}
