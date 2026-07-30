package com.gamma.asn.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Legacy tx.json loader: the legacy Transformer strips blank lines and {@code --} comment
 * lines, cuts inline {@code --} suffixes, and collapses runs of whitespace BEFORE parsing
 * — reproduced verbatim (an inline {@code --} inside a JSON string would be cut there
 * too; that is legacy behaviour, not a bug to fix here).
 */
public final class TxConfig {

    private final Map<String, Object> root;

    @SuppressWarnings("unchecked")
    private TxConfig(Object parsed) {
        this.root = (Map<String, Object>) parsed;
    }

    public static TxConfig load(Path path) throws IOException {
        return fromText(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static TxConfig fromText(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("--")) {
                continue;
            }
            l = l.replaceAll("\\s+", " ").trim();
            int c = l.indexOf("--");
            if (c >= 0) {
                l = l.substring(0, c).trim();
            }
            lines.add(l);
        }
        return new TxConfig(Json.parse(String.join("\n", lines)));
    }

    /** Top-level config map: record-type sections, shared type sections, @simpleLookup. */
    public Map<String, Object> root() {
        return root;
    }

    public Object section(String name) {
        return root.get(name);
    }
}
