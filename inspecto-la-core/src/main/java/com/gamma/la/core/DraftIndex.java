package com.gamma.la.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * D7-6 - a small, REBUILDABLE listing index for {@code drafts/}, so listing 50 Drafts does not read 50 headers. {@code drafts/index.json}
 * holds, per draft id, a compact copy of the header (everything but {@code baseLogHash} and the growing {@code rebases[]}) and the
 * header file's identity ({@code size:mtime}). A listing stats each header file and re-reads ONLY one whose identity changed (a
 * rebase rewrites it); a missing or unreadable index is rebuilt from the headers. It is a cache, never a record: the headers stay
 * authoritative, the state markers are checked on disk each time, and a stale entry cannot survive a stat.
 */
public final class DraftIndex {

    private DraftIndex() {}

    public static final String FILE = "index.json";

    /** draftId -> compact header, for every Draft that has a header. Writes the index back when it changed. */
    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Map<String, Object>> headers(Path investigationDir) throws IOException {
        Path root = DraftStore.draftsDir(investigationDir);
        Map<String, Map<String, Object>> out = new TreeMap<>();
        if (!Files.isDirectory(root)) return out;
        Map<String, Object> known = load(root.resolve(FILE));
        Map<String, Object> next = new LinkedHashMap<>();
        java.util.List<String> ids = DraftStore.listIds(investigationDir);
        boolean changed = known.size() != ids.size();
        for (String id : ids) {
            Path header = DraftStore.draftDir(investigationDir, id).resolve(DraftStore.HEADER);
            String sig = Files.size(header) + ":" + Files.getLastModifiedTime(header).toMillis();
            Map<String, Object> entry = known.get(id) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            if (entry == null || !sig.equals(entry.get("sig")) || !(entry.get("header") instanceof Map<?, ?>)) {
                String raw = DraftStore.readHeader(investigationDir, id);
                if (raw == null) continue;
                Map<String, Object> h = new LinkedHashMap<>(InvestigationEvaluator.CANONICAL.readValue(raw, Map.class));
                h.remove("baseLogHash");
                h.remove("rebases");
                entry = new LinkedHashMap<>();
                entry.put("sig", sig);
                entry.put("header", h);
                changed = true;
            }
            next.put(id, entry);
            out.put(id, (Map<String, Object>) entry.get("header"));
        }
        if (changed) store(root, next);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(Path file) {
        try {
            if (Files.isRegularFile(file)
                    && InvestigationEvaluator.CANONICAL.readValue(Files.readString(file, StandardCharsets.UTF_8), Map.class).get("drafts") instanceof Map<?, ?> m)
                return (Map<String, Object>) m;
        } catch (IOException | RuntimeException corrupt) {
            // a damaged index is rebuilt from the headers
        }
        return Map.of();
    }

    private static void store(Path root, Map<String, Object> drafts) {
        try {
            Path tmp = Files.createTempFile(root, ".index-", ".tmp");
            Files.writeString(tmp, InvestigationEvaluator.CANONICAL.writeValueAsString(Map.of("drafts", drafts)), StandardCharsets.UTF_8);
            Files.move(tmp, root.resolve(FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // the index is an accelerator: the next listing rebuilds it
        }
    }
}
