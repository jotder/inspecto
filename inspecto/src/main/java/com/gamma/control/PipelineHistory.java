package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A Pipeline's persisted config history ({@code PIPELINE-CONFIG-HISTORY-1}, operator decision 2026-09-24):
 * a snapshot of the {@code *_pipeline.toon} bytes after every successful save, the newest {@value #KEEP}
 * kept per Pipeline (the editor's in-session undo cap), oldest pruned.
 *
 * <p><b>Layout</b> — {@code <write-root>/.history/pipelines/<pipelineId>/v<N>.toon}, {@code N} monotonic
 * per Pipeline (never reused after a prune), {@code savedAt} = the snapshot file's modification time.
 * The house convention is {@code ComponentStore}'s MET-5 {@code .history/} with {@code v<N>} numbering;
 * the per-Pipeline directory is what lets a rename move the whole history in one step. ⚠ The names are
 * chosen so no config walker can mistake a snapshot for a live config: every Pipeline discovery keys on
 * the {@code _pipeline.toon} suffix, and the satellite scan ({@code ConfigFileSupport}) stops at depth 3
 * while a snapshot sits at depth 4.
 *
 * <p><b>What a snapshot is</b> — the bytes on disk AFTER the write, read back, so it is exactly what the
 * next read serves. It is taken only once a save has succeeded; a refused save never reaches it. A
 * snapshot failure is logged and swallowed: the save already landed, and answering it with an error
 * would tell the author their config was not written when it was.
 *
 * <p><b>Rename</b> moves the directory ({@link #rename}); <b>delete</b> purges it ({@link #purge}) — the
 * posture {@code ComponentStore.delete} takes for component history: the history belongs to the config,
 * and the data and audit trail a Pipeline delete keeps are what record what happened.
 */
final class PipelineHistory {

    private static final Logger log = LoggerFactory.getLogger(PipelineHistory.class);

    /** Versions kept per Pipeline — the editor's in-session undo cap (operator 2026-09-24). */
    static final int KEEP = 50;

    private static final Pattern FILE = Pattern.compile("v(\\d{1,9})\\.toon");

    /** One serialisation point: numbering, pruning and moves must not interleave across saves. */
    private static final Object LOCK = new Object();

    private PipelineHistory() {}

    /** One kept snapshot. */
    record Version(int version, Instant savedAt, long bytes, Path file) {}

    /** {@code <writeRoot>/.history/pipelines/<id>} — {@code id} must already have passed {@link WriteGates#isSafeName}. */
    static Path dirFor(Path writeRoot, String id) {
        return writeRoot.resolve(".history").resolve("pipelines").resolve(id);
    }

    /**
     * Snapshot the Pipeline config just written at {@code target}. The Pipeline is identified from the
     * written content, by the parser's own rule ({@link ConfigReadRoutes#pipelineIdOf}), so every save path
     * files under the id the read routes resolve. Never throws.
     */
    static void record(Path writeRoot, Path target) {
        if (writeRoot == null || target == null) return;
        try {
            byte[] bytes = Files.readAllBytes(target);
            String id = ConfigReadRoutes.pipelineIdOf(ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8)), null);
            if (!WriteGates.isSafeName(id)) {
                log.warn("[PIPELINE-HISTORY] not recorded: pipeline id '{}' in {} is not a safe name", id, target);
                return;
            }
            synchronized (LOCK) {
                Path dir = dirFor(writeRoot, id.trim());
                int next = versions(dir).stream().mapToInt(Version::version).max().orElse(0) + 1;
                AtomicFiles.write(dir.resolve("v" + next + ".toon"), bytes, ".hist-");
                List<Version> kept = versions(dir);
                for (int i = KEEP; i < kept.size(); i++) Files.deleteIfExists(kept.get(i).file());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[PIPELINE-HISTORY] snapshot of {} failed — the save stands, this version is not in history: {}",
                    target, e.getMessage());
        }
    }

    /** The kept snapshots in {@code dir}, newest first (empty when there is no history, or no {@code dir}). */
    static List<Version> versions(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Version> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                Matcher m = FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                out.add(new Version(Integer.parseInt(m.group(1)), Files.getLastModifiedTime(p).toInstant(),
                        Files.size(p), p));
            }
        }
        out.sort(Comparator.comparingInt(Version::version).reversed());
        return out;
    }

    /**
     * Move {@code oldId}'s history to {@code newId} (identity rename). Idempotent — a resumed rename whose
     * history already moved finds nothing to move. ⛔ Never merges or overwrites: when {@code newId} already
     * has a history the move is skipped and said so, rather than one history silently destroying another.
     *
     * @return the outcome, worded for {@code rename.journal}
     */
    static String rename(Path writeRoot, String oldId, String newId) throws IOException {
        synchronized (LOCK) {
            Path from = dirFor(writeRoot, oldId);
            if (!Files.isDirectory(from)) return "config history: none to move";
            Path to = dirFor(writeRoot, newId);
            if (Files.exists(to)) return "config history NOT moved: .history/pipelines/" + newId + " already exists";
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            return "config history moved";
        }
    }

    /**
     * Delete {@code id}'s snapshots, then its directory if that left it empty — only {@code v<N>.toon}
     * files, never a recursive delete of whatever else an operator put there.
     */
    static void purge(Path writeRoot, String id) throws IOException {
        if (writeRoot == null || !WriteGates.isSafeName(id)) return;
        synchronized (LOCK) {
            Path dir = dirFor(writeRoot, id.trim());
            for (Version v : versions(dir)) Files.deleteIfExists(v.file());
            try (Stream<Path> rest = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
                if (rest.findAny().isEmpty()) Files.deleteIfExists(dir);
            }
        }
    }

    // ── diff ─────────────────────────────────────────────────────────────────────

    /** Above this many cells the middle of a diff is reported as one replacement instead of an LCS. */
    private static final long LCS_CELL_CAP = 4_000_000L;

    /**
     * A line diff of {@code a} → {@code b}: {@code {op: context|remove|add, text}} rows in order, plus the
     * counts. Common head and tail are trimmed first, the middle is an LCS; a middle too large to tabulate
     * is reported as a whole replacement with {@code coarse: true} — still a correct diff, just not minimal.
     */
    static Map<String, Object> diff(List<String> a, List<String> b) {
        int head = 0;
        while (head < a.size() && head < b.size() && a.get(head).equals(b.get(head))) head++;
        int tail = 0;
        while (tail < a.size() - head && tail < b.size() - head
                && a.get(a.size() - 1 - tail).equals(b.get(b.size() - 1 - tail))) tail++;
        List<String> am = a.subList(head, a.size() - tail), bm = b.subList(head, b.size() - tail);

        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < head; i++) lines.add(line("context", a.get(i)));
        boolean coarse = (long) am.size() * bm.size() > LCS_CELL_CAP;
        if (coarse) {
            for (String s : am) lines.add(line("remove", s));
            for (String s : bm) lines.add(line("add", s));
        } else {
            int n = am.size(), m = bm.size();
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--)
                for (int j = m - 1; j >= 0; j--)
                    lcs[i][j] = am.get(i).equals(bm.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            int i = 0, j = 0;
            while (i < n && j < m) {
                if (am.get(i).equals(bm.get(j))) { lines.add(line("context", am.get(i))); i++; j++; }
                else if (lcs[i + 1][j] >= lcs[i][j + 1]) lines.add(line("remove", am.get(i++)));
                else lines.add(line("add", bm.get(j++)));
            }
            while (i < n) lines.add(line("remove", am.get(i++)));
            while (j < m) lines.add(line("add", bm.get(j++)));
        }
        for (int i = a.size() - tail; i < a.size(); i++) lines.add(line("context", a.get(i)));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("added", lines.stream().filter(l -> "add".equals(l.get("op"))).count());
        r.put("removed", lines.stream().filter(l -> "remove".equals(l.get("op"))).count());
        r.put("coarse", coarse);
        r.put("lines", lines);
        return r;
    }

    private static Map<String, Object> line(String op, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("text", text);
        return m;
    }
}
