package com.gamma.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which folders under the Spaces root are Spaces — the one rule, for every reader that must agree with what
 * {@code SpaceManager.discover} boots.
 *
 * <p><b>Why this lives in {@code inspecto-util}.</b> {@code SpaceManager} (in {@code inspecto-processor}) applies it at
 * boot, and the demo sign-in ({@code inspecto-demo-auth}, which may not depend on the processor) reads Demo User
 * rosters from the same folders. A copy of the rule in demo-auth let a stale {@code backup-acme/} roster sign a user
 * in (DEMO-AUTH-STALE-ROSTER-1); both modules already depend on this one, so both call the same code. For the same
 * reason the core publishes here the Spaces it actually booted ({@link #hosted}), which a name rule alone cannot know.
 */
public final class SpaceFolders {

    private SpaceFolders() {}

    /** Name tokens (split on {@code - _ . space}) that mark a folder as a copy, never a live Space. */
    private static final Set<String> COPY_TOKENS =
            Set.of("bak", "snapshot", "scratch");   // plus any token starting "backup"; "old"/"copy"/"temp" are legitimate Space words

    /**
     * Why {@code dir} (a child of the Spaces root) is NOT a Space, or {@code null} when it is one. A Space is a folder
     * that holds {@code config/} and whose name is not hidden ({@code .}), reserved ({@code _}) or a copy
     * ({@code backup}, {@code snapshot}…). A copied Space carries {@code space.toon} and {@code config/} just like
     * the original, so only the name can keep a backup from booting; {@code space.toon} is not required.
     */
    public static String notASpaceReason(Path dir) {
        String name = dir.getFileName().toString();
        if (name.startsWith(".")) return "hidden folder";
        if (name.startsWith("_")) return "reserved '_' folder";
        for (String t : name.toLowerCase(Locale.ROOT).split("[-_. ]+"))
            if (COPY_TOKENS.contains(t) || t.startsWith("backup"))
                return "name looks like a backup/scratch copy ('" + t + "') — rename it or move it outside the Spaces root";
        if (!Files.isDirectory(dir.resolve("config"))) return "no config/ folder";
        return null;   // space.toon stays optional: pre-manifest Spaces still boot (display name defaults to the id)
    }

    /** The Spaces the running core hosts (id to folder, sorted by id), or {@code null} while no core publishes. */
    private static volatile Map<String, Path> hosted;
    private static volatile Object publisher;

    /**
     * The core ({@code SpaceManager.discover}) publishes the Spaces it actually booted, and again after every runtime
     * create or delete. A name rule cannot see the rest of its verdict: a {@code <id>.ref} Space kept outside the
     * Spaces root, a folder refused for overlapping another Space, owned by another pod or failing to boot
     * (DEMO-AUTH-ROSTER-REACH-1). {@code owner} is the publishing manager, so closing a stale one cannot withdraw a
     * newer one's list.
     */
    public static void publishHosted(Object owner, Map<String, Path> spaces) {
        synchronized (SpaceFolders.class) {
            hosted = Collections.unmodifiableMap(new TreeMap<>(spaces));
            publisher = owner;
        }
    }

    /** The core stopped hosting: readers fall back to their own reading of the Spaces root. */
    public static void withdrawHosted(Object owner) {
        synchronized (SpaceFolders.class) {
            if (publisher != owner) return;
            hosted = null;
            publisher = null;
        }
    }

    /** What the running core hosts (id to folder), or empty when no core has published (a CLI, a unit test). */
    public static Optional<Map<String, Path>> hosted() {
        return Optional.ofNullable(hosted);
    }
}
