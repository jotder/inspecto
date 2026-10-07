package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Space's <b>module settings</b> (MODULE-REORG-1 P2b, D-MR10) — the middle gate
 * <em>Installed -&gt; Enabled (per Space) -&gt; Permitted</em>. Persisted as {@code modules.toon} in the Space's config
 * root: {@code disabled: [exchange, geoLink]}, a DISABLED list, so an absent file means everything is enabled and a
 * module installed later defaults to enabled.
 *
 * <p><b>Inert ids.</b> A stored id that no installed module declares (the module was removed later) is kept as it is:
 * it disables nothing, is reported by {@code GET /settings/modules} as {@code inert}, and survives every save
 * ({@link #write}), so re-installing the module finds the Space's choice intact (plan section 2.5). Unmodelled keys of
 * the file survive a save as well.
 *
 * <p><b>A file that cannot be read</b> reads as "nothing disabled" and is logged: the disabled list narrows the
 * product surface only, and the Permitted gate (capabilities) is untouched by it.
 *
 * <p>The file is written here, not in {@link ModuleSettingsRoutes}, so the route class holds no TOON write of its own
 * (a settings document that writes itself, like the other Space settings records).
 */
final class ModuleSettings {

    static final String FILE = "modules.toon";
    static final String KEY = "disabled";

    private ModuleSettings() {}

    /** One parsed file, cached by its stamp so the dispatch path does not re-parse it on every request. */
    private record Cached(long modified, long size, Set<String> disabled) {}

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /** The disabled feature ids of the Space whose config root is {@code root} (all stored ids, inert ones included). */
    static Set<String> disabled(Path root) {
        if (root == null) return Set.of();
        Path f = root.resolve(FILE).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(f)) {
                CACHE.remove(f);
                return Set.of();
            }
            long modified = Files.getLastModifiedTime(f).toMillis();
            long size = Files.size(f);
            Cached c = CACHE.get(f);
            if (c != null && c.modified == modified && c.size == size) return c.disabled;
            Set<String> ids = strings(ToonHelper.load(f.toString()).get(KEY));
            CACHE.put(f, new Cached(modified, size, ids));
            return ids;
        } catch (RuntimeException | IOException unreadable) {
            org.slf4j.LoggerFactory.getLogger(ModuleSettings.class)
                    .warn("{} is unreadable, so no module is disabled in that Space until it is rewritten: {}", f, unreadable.toString());
            return Set.of();
        }
    }

    /** Whether the Space's file exists but cannot be parsed (reported by the GET, never silently). */
    static boolean unreadable(Path root) {
        if (root == null) return false;
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return false;
        try {
            ToonHelper.load(f.toString());
            return false;
        } catch (RuntimeException | IOException bad) {
            return true;
        }
    }

    /**
     * Replace the disabled list. {@code wanted} must already be validated against the installed features; every stored
     * id that is NOT installed is carried over untouched. Other keys of the existing file are kept.
     *
     * @return the list now stored
     */
    static List<String> write(Path root, Set<String> installed, Set<String> wanted) throws IOException {
        Path f = root.resolve(FILE);
        Map<String, Object> doc = new LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            try {
                doc.putAll(ToonHelper.load(f.toString()));
            } catch (RuntimeException | IOException unreadable) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, FILE + " exists but cannot be read ("
                        + unreadable.getMessage() + ") - fix or remove it before saving, a save would drop what it holds");
            }
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(wanted);
        for (String stored : strings(doc.get(KEY))) if (!installed.contains(stored)) next.add(stored);
        List<String> out = new ArrayList<>(next);
        doc.put(KEY, out);
        AtomicFiles.write(f, JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".modules-");
        CACHE.remove(f.toAbsolutePath().normalize());
        return out;
    }

    private static Set<String> strings(Object o) {
        Set<String> out = new LinkedHashSet<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null && !String.valueOf(x).isBlank()) out.add(String.valueOf(x).trim());
        return out;
    }
}
