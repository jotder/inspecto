package com.gamma.alert;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The output-store mechanics a scoring Job shares (ANOMALY-DETECTION-1 D-AD5, operator 2026-10-10): the ownership
 * marker, the atomic {@code _latest} swap and the reserved-prefix test. Moved here from the Risk Score module so the
 * {@code risk.score} and {@code anomaly.score} Jobs write through ONE implementation — no copy, and neither optional
 * module depends on the other. Each caller passes its own marker file name and prefix.
 */
public final class ScoreOutputDirs {
    private ScoreOutputDirs() {}

    /** Whether {@code dir} is an output directory THIS model created ({@code marker} names the model). */
    public static boolean ownedBy(Path dir, String marker, String modelId) {
        Path m = dir.resolve(marker);
        try {
            return Files.isRegularFile(m) && modelId.equals(Files.readString(m).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Create {@code dir} with {@code marker} holding {@code modelId}, or accept it when the marker already names this
     * model. Anything else — a directory with no marker, or another model's — is refused, untouched.
     *
     * @param kind the component kind named in the refusal ({@code risk-score}, {@code anomaly-model})
     */
    public static void claim(Path dir, String marker, String kind, String modelId) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(marker), modelId);
            return;
        }
        if (!ownedBy(dir, marker, modelId))
            throw new IllegalStateException(kind + " '" + modelId + "' refuses to write into '" + dir.getFileName()
                    + "': the directory exists and was not created by this model");
    }

    /**
     * Replace every {@code glob} file in {@code dir} with {@code tmp} (renamed {@code name}): the old files are hidden
     * first by an atomic rename, the new one revealed by an atomic move, then the hidden ones deleted.
     */
    public static void swapIn(Path dir, Path tmp, String name, String glob) throws IOException {
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, glob)) {
            for (Path p : old) {
                Path hidden = p.resolveSibling(p.getFileName() + ".stale");
                Files.move(p, hidden, StandardCopyOption.ATOMIC_MOVE);
                stale.add(hidden);
            }
        }
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        for (Path p : stale) Files.deleteIfExists(p);
    }

    /** The first path segment of a store reference, normalised ({@code ./a/../x_y/z} → {@code x_y}). */
    public static String firstSegment(String ref) {
        String n = ref.trim().replace('\\', '/');
        Deque<String> parts = new ArrayDeque<>();
        for (String p : n.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); continue; }
            parts.addLast(p);
        }
        return parts.isEmpty() ? "" : parts.getFirst();
    }

    /**
     * The names a component uses for a store: its {@code id} plus every non-blank {@code storeKeys} value of its
     * content — the list a reserved-prefix check runs over.
     */
    public static List<String> namedStores(String id, Map<String, Object> content, List<String> storeKeys) {
        List<String> named = new ArrayList<>();
        named.add(id);
        for (String k : storeKeys) if (content.get(k) instanceof String v && !v.isBlank()) named.add(v);
        return named;
    }

    /** Whether any of {@code named} starts with {@code prefix} on its normalised first segment, case-insensitively. */
    public static boolean anyReserved(List<String> named, String prefix) {
        return named.stream().map(ScoreOutputDirs::firstSegment)
                .anyMatch(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix));
    }
}
