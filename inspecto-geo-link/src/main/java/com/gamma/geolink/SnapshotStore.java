package com.gamma.geolink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Durable storage for Link Analysis evidence snapshots (LA-03, decisions {@code D-S1}/{@code D-E2}/{@code D-E3}
 * in {@code docs/superpower/link-analysis-backlog-plan.md}).
 *
 * <p><b>Why a new store rather than an existing one.</b> {@code ComponentStore} was rejected on SEMANTICS,
 * not size: it keeps a single <em>overwritable</em> document per id, and evidence must never be silently
 * replaced. The shape reused here is {@code RunArtifactStore}'s — a plain append-only file per id under the
 * space's audit directory, with no size ceiling, which suits a snapshot that serialises to roughly
 * 250–300 KB at the 500-node projection cap. ⚠ {@code RunArtifactStore} itself was deliberately NOT rewired
 * onto a shared abstraction: it is package-private in {@code com.gamma.job}, bound to its own record, and on
 * the Run path — generalising it would disturb working code to serve one new caller.
 *
 * <p>⛔ <b>A snapshot is IMMUTABLE once written.</b> Creation uses {@link StandardOpenOption#CREATE_NEW}, so a
 * re-POST of an existing id is refused (409) rather than replacing the sealed record. That is the whole point
 * of the object: a saved view is not evidence precisely because reopening it re-projects live data, and an
 * evidence store that overwrote itself would inherit the same defect.
 *
 * <p>🔴 <b>Attachment does not touch the snapshot.</b> Recording that a snapshot was attached to a Case is a
 * relationship, not part of the sealed content — writing it into the snapshot would mutate a sealed record and
 * invalidate the fingerprint that makes it evidence. Attachments therefore go to a separate append-only
 * {@code attachments.jsonl}, and the snapshot file is never reopened for writing.
 *
 * <p>⚠ What is stored is what the SPA already materialises: full {@code nodes} and {@code edges}, not id
 * references. Storing ids only would mean re-reading labels and amounts from live data on reopen, which is the
 * defect this object exists to avoid (plan §5.4, corrected 2026-09-22).
 */
final class SnapshotStore {

    /** A snapshot id is a bare key, never a path: separators and traversal are refused outright. */
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private static final String ATTACHMENTS = "attachments.jsonl";

    private final Path dir;

    SnapshotStore(Path writeRoot) {
        this.dir = writeRoot.resolve("audit").resolve("snapshots");
    }

    /**
     * Seal one snapshot. Returns false when the id already exists — the caller turns that into a 409, because
     * an existing piece of evidence is never replaced.
     */
    boolean create(String id, String json) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(id + ".json");
        try {
            Files.writeString(f, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    /** One sealed snapshot's raw JSON, or null when the id was never written. */
    String read(String id) throws IOException {
        Path f = dir.resolve(id + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * Sealed snapshot ids, newest first, bounded by {@code limit}. The true total is reported separately so a
     * bounded read never looks like a complete one.
     */
    List<String> list(int limit, int[] totalOut) throws IOException {
        if (!Files.isDirectory(dir)) {
            totalOut[0] = 0;
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        }
        totalOut[0] = files.size();
        files.sort(Comparator.comparing((Path p) -> {
            try {
                return Files.getLastModifiedTime(p);
            } catch (IOException e) {
                return java.nio.file.attribute.FileTime.fromMillis(0);
            }
        }).reversed());
        List<String> out = new ArrayList<>();
        for (Path p : files.subList(0, Math.min(limit, files.size()))) {
            String n = p.getFileName().toString();
            out.add(n.substring(0, n.length() - ".json".length()));
        }
        return out;
    }

    /** Append one attachment record. The sealed snapshot is not reopened. */
    void attach(String snapshotId, String caseId, String at) throws IOException {
        Files.createDirectories(dir);
        String line = "{\"snapshotId\":" + quote(snapshotId) + ",\"caseId\":" + quote(caseId)
                + ",\"at\":" + quote(at) + "}\n";
        Files.writeString(dir.resolve(ATTACHMENTS), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The Case ids one snapshot has been attached to, in the order they were recorded. */
    List<String> attachmentsOf(String snapshotId) throws IOException {
        Path f = dir.resolve(ATTACHMENTS);
        if (!Files.isRegularFile(f)) return List.of();
        List<String> out = new ArrayList<>();
        String needle = "\"snapshotId\":" + quote(snapshotId) + ",";
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.contains(needle)) {
                int i = line.indexOf("\"caseId\":\"");
                if (i >= 0) {
                    int start = i + "\"caseId\":\"".length();
                    int end = line.indexOf('"', start);
                    if (end > start) out.add(line.substring(start, end));
                }
            }
        }
        return out;
    }

    /** Minimal JSON string escaping — ids are already constrained, but a Case id is caller text. */
    private static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /** Where the sealed records live — for the path-jail assertion in the route. */
    Path directory() {
        return dir;
    }
}
