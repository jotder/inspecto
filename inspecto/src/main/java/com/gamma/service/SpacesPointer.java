package com.gamma.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The on-disk pointer to the spaces root ({@code SPACE-RELOCATABLE-ROOTS-1}): {@code <app root>/spaces.ref}, one line
 * holding a path, absolute or relative to {@link AppRoot}. Absent ⇒ {@code <app root>/spaces}.
 *
 * <p>Consulted only when neither {@code -Dspaces.root} nor {@code SPACES_ROOT} is set — the launch flag wins, so no
 * existing deployment changes behaviour. Same name and shape as the per-Space {@code <spaces root>/<id>.ref}.
 *
 * <p>⛔ A pointer that is present but unusable is a LOUD failure, never a fall-through to {@code <app root>/spaces}.
 * The operator wrote that file on purpose; booting with "no Spaces attached" instead would read as a configuration
 * they never made.
 */
public final class SpacesPointer {

    /** The pointer file's name, directly under the install root. */
    public static final String FILE = "spaces.ref";

    private SpacesPointer() {}

    /** The spaces root {@code <app root>/spaces.ref} names, else {@code <app root>/spaces}. */
    public static Path resolve() {
        Path root = AppRoot.current();
        Path pointer = root.resolve(FILE);
        if (!Files.isRegularFile(pointer)) return root.resolve("spaces");
        String text;
        try {
            text = Files.readString(pointer).trim();
        } catch (IOException e) {
            throw new IllegalStateException(pointer + " is unreadable: " + e.getMessage(), e);
        }
        if (text.isEmpty())
            throw new IllegalStateException(pointer + " is empty - it must hold one path to the spaces root"
                    + " (absolute, or relative to " + root + "); delete it to use " + root.resolve("spaces"));
        Path target = AppRoot.resolve(text);
        if (!Files.isDirectory(target))
            throw new IllegalStateException(pointer + " points at " + target + ", which is not a directory");
        return target;
    }
}
