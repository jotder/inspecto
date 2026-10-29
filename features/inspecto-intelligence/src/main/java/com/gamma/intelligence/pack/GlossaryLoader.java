package com.gamma.intelligence.pack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the canonical-term definitions out of {@code docs/GLOSSARY.md} (binding vocabulary, see
 * repo {@code CLAUDE.md}) into a term→definition map — feeds both {@link InspectoPromptProfile}
 * and the {@code glossary_lookup} tool ({@link InspectoTools}).
 *
 * <p>An entry starts a line as {@code **Term** — definition}, optionally with an annotation between
 * the term and the dash: {@code **Entity / Link** *(business graphs only)* — …}. The definition is the
 * WHOLE entry: the first line plus its continuation lines, up to a blank line, a heading or a rule
 * (INTELLIGENCE-GLOSSARY-ANNOTATED-1 — the first line alone stops mid-sentence, e.g.
 * "…(SFTP/FTP/FTPS, a database, cloud"). Lines are joined with one space; bold markers, link targets
 * and table-escaped pipes ({@code \|}) are dropped. Everything else (code spans, ⛔ notes) stays — the
 * console's {@code cleanGlossaryDefinition} decides what an analyst reads. An entry starts a paragraph: a
 * hard-wrapped line that happens to begin {@code **Annotation Target** — …} inside another entry is that
 * entry's text, not a new one.
 *
 * <p>A term written as {@code A / B} is also findable as {@code A} and as {@code B}, unless another
 * entry already owns that word. Missing/unreadable file → empty map (never throws — the pack must
 * still assemble offline).
 */
final class GlossaryLoader {

    private static final Logger log = LoggerFactory.getLogger(GlossaryLoader.class);
    /** {@code **Term**}, an optional {@code *(annotation)*} (which may hold dashes and links), the dash, the text. */
    private static final Pattern ENTRY =
            Pattern.compile("^\\*\\*([^*]+)\\*\\*\\s*(?:\\*\\(.*?\\)\\*\\s*)?[—–-]+\\s*(.*)$");

    private GlossaryLoader() {
    }

    /** {@code <repo root>/docs/GLOSSARY.md}, or the CWD-relative default when the repo root can't be found. */
    static Path glossaryPath() {
        return RepoPaths.root().map(r -> r.resolve("docs").resolve("GLOSSARY.md"))
                .orElse(Path.of("docs", "GLOSSARY.md"));
    }

    static Map<String, String> load() {
        return load(glossaryPath());
    }

    static Map<String, String> load(Path glossaryPath) {
        List<String> lines;
        try {
            lines = Files.readAllLines(glossaryPath);
        } catch (IOException e) {
            log.warn("Could not read {} for the domain glossary: {}", glossaryPath, e.getMessage());
            return Map.of();
        }
        return parse(lines);
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> terms = new LinkedHashMap<>();
        boolean paragraphStart = true;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            boolean atStart = paragraphStart;
            paragraphStart = !isContinuation(line);
            Matcher m = ENTRY.matcher(line);
            if (!atStart || !m.matches()) continue;
            StringBuilder text = new StringBuilder(m.group(2).trim());
            while (i + 1 < lines.size() && isContinuation(lines.get(i + 1))) {
                text.append(' ').append(lines.get(++i).trim());
            }
            paragraphStart = false;
            terms.putIfAbsent(m.group(1).trim(), stripMarkdown(text.toString().trim()));
        }
        // "A / B" entries answer for A and for B too, never displacing an entry of their own.
        Map<String, String> aliases = new LinkedHashMap<>();
        terms.forEach((term, definition) -> {
            if (!term.contains(" / ")) return;
            for (String part : term.split(" / ")) {
                if (!part.isBlank() && !terms.containsKey(part.trim())) aliases.putIfAbsent(part.trim(), definition);
            }
        });
        aliases.forEach(terms::putIfAbsent);
        return Map.copyOf(terms);
    }

    /** A line that carries a paragraph on: not blank, and not a heading, rule, quote or table row. */
    private static boolean isContinuation(String line) {
        String t = line.trim();
        return !t.isEmpty() && !t.startsWith("#") && !t.startsWith("---") && !t.startsWith(">")
                && !t.startsWith("|");
    }

    /** Drops bold markers and table-escaped pipes; inline links keep their bracket text, target dropped. */
    private static String stripMarkdown(String text) {
        return text.replace("**", "")
                .replace("\\|", "|")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1");
    }
}
