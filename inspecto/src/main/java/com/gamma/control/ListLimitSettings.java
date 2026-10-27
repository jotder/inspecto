package com.gamma.control;

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
 * The Space's list limit ({@code LIST-LIMIT-1}): the most rows a Dashboard Widget reads from a Dataset when it does not
 * ask for a number itself ({@code POST /bi/query} and the public share read). Persisted as {@code list-limit.toon}
 * ({@code rows: N}) in the Space's config tree, mirroring {@link PipelineHistorySettings};
 * {@code GET|PUT /settings/list-limit}.
 *
 * <p><b>{@code null} means "the shipped default"</b> ({@value #DEFAULT_ROWS}; it was 500 before D9 of the MFS review).
 * ⚠ A stored value outside {@code 1..}{@value #MAX_ROWS} reads as the default, and the PUT refuses one (422). The ceiling
 * is the BI query's own hard cap, so a Widget that asks for more is still clamped there.
 */
public record ListLimitSettings(Integer rows) {

    static final String FILE = "list-limit.toon";
    static final int DEFAULT_ROWS = 1_000;
    /** The BI query's hard cap ({@code MeasureCompiler.parse}'s max): a larger default could never be honoured. */
    public static final int MAX_ROWS = 10_000;
    static final ListLimitSettings EMPTY = new ListLimitSettings(null);

    /** The limit in force: the stated value, else {@link #DEFAULT_ROWS}. */
    int effectiveRows() {
        return rows != null ? rows : DEFAULT_ROWS;
    }

    /** The limit in force for the Space whose config root is {@code writeRoot} (the default when there is none). */
    public static int effectiveRows(Path writeRoot) {
        return forRoot(writeRoot).effectiveRows();
    }

    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rows != null) m.put("rows", rows);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".list-limit-");
    }

    static ListLimitSettings forRoot(Path writeRoot) {
        return writeRoot == null ? EMPTY : read(writeRoot.resolve(FILE));
    }

    /** Missing, unreadable or out of range → {@link #EMPTY} (the default). */
    static ListLimitSettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            String raw = ToonHelper.opt(m, "rows", "");
            if (raw.isBlank()) return EMPTY;
            int v = Integer.parseInt(raw.trim());
            return v >= 1 && v <= MAX_ROWS ? new ListLimitSettings(v) : EMPTY;
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /** The wire shape of {@code GET|PUT /settings/list-limit}. */
    Map<String, Object> shape() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows);
        m.put("effectiveRows", effectiveRows());
        m.put("defaultRows", DEFAULT_ROWS);
        m.put("maxRows", MAX_ROWS);
        return m;
    }
}
