package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Space's data settings ({@code LA-ONBOARD-1} OB-7): today one switch, {@code data.self_import} - whether people
 * may bring their own files into this Space through <b>Add data</b> ({@code /onboard/files}, Add a file, Run now).
 * Persisted as {@code data.toon} ({@code self_import: true|false}) in the Space's config tree, mirroring
 * {@link TimezoneSettings}; {@code GET|PUT /settings/data}, and the value in force rides {@code /bootstrap}.
 *
 * <p><b>Unset means "follow who the user is"</b> (use cases §1.1, A-15): on with no sign-in (the one person who owns
 * everything), off with sign-in (an administrator turns it on per Space; data there comes from Pipelines). An
 * unreadable file reads as unset.
 */
record DataSettings(Boolean selfImport) {

    static final String FILE = "data.toon";
    static final DataSettings EMPTY = new DataSettings(null);

    /** The default when the Space states nothing: on with no sign-in, off with sign-in (the Authenticator SPI slot). */
    static boolean selfImportDefault() {
        return Authenticators.active().isEmpty();
    }

    /** The value in force: the stated one, else {@link #selfImportDefault()}. */
    boolean selfImportInForce() {
        return selfImport != null ? selfImport : selfImportDefault();
    }

    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (selfImport != null) m.put("self_import", selfImport);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".data-");
    }

    /** The settings of the Space whose config root is {@code writeRoot}; {@link #EMPTY} when there is none. */
    static DataSettings forRoot(Path writeRoot) {
        return writeRoot == null ? EMPTY : read(writeRoot.resolve(FILE));
    }

    static DataSettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            Object v = m.get("self_import");
            if (v instanceof Boolean b) return new DataSettings(b);
            if (v != null && ("true".equalsIgnoreCase(v.toString().trim()) || "false".equalsIgnoreCase(v.toString().trim())))
                return new DataSettings(Boolean.parseBoolean(v.toString().trim()));
        } catch (Exception unreadable) {
            // an unreadable settings file is unset: the default for who the user is applies
        }
        return EMPTY;
    }

    /** The wire shape of {@code GET|PUT /settings/data}. */
    Map<String, Object> shape() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selfImport", selfImport);
        m.put("selfImportDefault", selfImportDefault());
        m.put("selfImportInForce", selfImportInForce());
        return m;
    }
}
