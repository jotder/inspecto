package com.gamma.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Which folders under the Spaces root are Spaces — the one rule, for every reader that must agree with what
 * {@code SpaceManager.discover} boots.
 *
 * <p><b>Why this lives in {@code inspecto-util}.</b> {@code SpaceManager} (in {@code inspecto-processor}) applies it at
 * boot, and the demo sign-in ({@code inspecto-demo-auth}, which may not depend on the processor) reads Demo User
 * rosters from the same folders. A copy of the rule in demo-auth let a stale {@code backup-acme/} roster sign a user
 * in (DEMO-AUTH-STALE-ROSTER-1); both modules already depend on this one, so both call the same code.
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
}
