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
 *
 * <p><b>Investigations live here too</b> (LA-10, decision {@code D-E2}): an Investigation's op log and the
 * Working Set each step evaluates to persist in this store, under {@code investigations/<id>/}, with the same
 * write-once rules — a header and every per-step Working Set are {@code CREATE_NEW}, and the log only ever
 * appends. One durable mechanism, not two half-built stores with different semantics.
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
        try (java.io.BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {   // streamed: every snapshot shares this file
                if (line.contains(needle)) {
                    int i = line.indexOf("\"caseId\":\"");
                    if (i >= 0) {
                        int start = i + "\"caseId\":\"".length();
                        int end = line.indexOf('"', start);
                        if (end > start) out.add(line.substring(start, end));
                    }
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

    // ── Investigations (LA-10, decision D-E2: the op log and its Working Sets live in THIS store) ─────────
    //
    // Layout, under the same audit root and with the same write-once discipline as a snapshot:
    //   investigations/<id>/header.json          CREATE_NEW — bindings, owner, fork lineage; never rewritten
    //   investigations/<id>/log.jsonl            APPEND — one line per step; the source of truth
    //   investigations/<id>/sets/<step>.json     CREATE_NEW — the Working Set that step evaluated to
    // ⚠ The directory name "investigations" cannot collide with a snapshot: list() keeps only *.json files.

    private static final String INVESTIGATIONS = "investigations";

    /** One Investigation's directory. The id is SAFE_ID-checked by the caller; the route jails the result. */
    Path investigationDir(String id) {
        return dir.resolve(INVESTIGATIONS).resolve(id);
    }

    /** Create an Investigation's header. False when the id already exists — never an overwrite (409). */
    boolean createInvestigation(String id, String headerJson) throws IOException {
        Path d = investigationDir(id);
        Files.createDirectories(d);
        try {
            Files.writeString(d.resolve("header.json"), headerJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    /** The header's raw JSON, or null when the Investigation was never created. */
    String readInvestigation(String id) throws IOException {
        Path f = investigationDir(id).resolve("header.json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** The log's lines, in step order (empty for a fresh Investigation). */
    List<String> readLog(String id) throws IOException {
        Path f = investigationDir(id).resolve("log.jsonl");
        if (!Files.isRegularFile(f)) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line);
        return out;
    }

    /**
     * One step's sealed Working Set file, raw, or null when it was never written (LA-12 reads it for the
     * dossier's integrity check and manifest). Read-only — additive beside the write paths above.
     */
    String readSet(String id, int step) throws IOException {
        Path f = investigationDir(id).resolve("sets").resolve(step + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * Append one step, then seal the Working Set it evaluated to. ⚠ The ORDER is deliberate: the log line is the
     * source of truth and is written first; a crash before the set lands leaves a step whose set replay can
     * recompute, never a set with no step behind it.
     */
    void appendStep(String id, int step, String lineJson, String workingSetJson) throws IOException {
        Path d = investigationDir(id);
        Files.writeString(d.resolve("log.jsonl"), lineJson + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.createDirectories(d.resolve("sets"));
        Files.writeString(d.resolve("sets").resolve(step + ".json"), workingSetJson, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * Write a whole fork (header, log and sets) into a scratch directory, then MOVE it into place in one rename,
     * so a failed fork leaves nothing half-written under a real id. False when the id is already taken.
     */
    boolean createFork(String id, String headerJson, List<String> lines, List<String> sets) throws IOException {
        Path root = dir.resolve(INVESTIGATIONS);
        Files.createDirectories(root);
        // A leading '.' can never match SAFE_ID, so the scratch name cannot shadow a real Investigation.
        Path tmp = Files.createTempDirectory(root, ".fork-");
        Files.writeString(tmp.resolve("header.json"), headerJson, StandardCharsets.UTF_8);
        if (!lines.isEmpty()) Files.writeString(tmp.resolve("log.jsonl"), String.join("\n", lines) + "\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("sets"));
        for (int i = 0; i < sets.size(); i++)
            Files.writeString(tmp.resolve("sets").resolve((i + 1) + ".json"), sets.get(i), StandardCharsets.UTF_8);
        // ⚠ Checked before the move, not caught after it: moving a directory onto an existing one fails with a
        // platform-specific exception (AccessDenied on Windows), not reliably FileAlreadyExists. The route
        // serialises writers to one Investigation root, so the check and the move do not race each other.
        if (Files.exists(investigationDir(id))) {
            deleteTree(tmp);
            return false;
        }
        Files.move(tmp, investigationDir(id), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    // ── Investigation Templates (LA-23) — the method half of an Investigation, write-once like everything here ──
    //   investigation-templates/<id>.json        CREATE_NEW — never rewritten; a changed method is a new id, so an
    //                                            Investigation instantiated from a template always names exactly
    //                                            the method it ran.
    // ⚠ A directory, so it cannot collide with a snapshot: list() keeps only *.json FILES at this level.

    private static final String TEMPLATES = "investigation-templates";

    /** Where Investigation Templates live — for the path-jail assertion in the route. */
    Path templateDirectory() {
        return dir.resolve(TEMPLATES);
    }

    /** Save one template. False when the id is already taken — never an overwrite (409). */
    boolean createTemplate(String id, String json) throws IOException {
        Files.createDirectories(templateDirectory());
        try {
            Files.writeString(templateDirectory().resolve(id + ".json"), json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    /** One template's raw JSON, or null when it was never saved. */
    String readTemplate(String id) throws IOException {
        Path f = templateDirectory().resolve(id + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    // ── Alert Rule bindings (LA-23) — the owner's record that an Alert Rule may evaluate this Investigation ──
    //   investigations/<id>/alert-rules/<rule>.json   the bound rule's canonical hash, who bound it, when.
    // Written only by the owner-gated binding route; read by the alert sweep, which has no caller of its own and
    // so evaluates a rule only when a binding for exactly that rule exists here. Rewritten on re-binding (it is a
    // relationship, not evidence), so the write is atomic.

    /** Record (or replace) the binding of one Alert Rule to one Investigation. */
    void bindAlertRule(String investigationId, String rule, String json) throws IOException {
        Path d = investigationDir(investigationId).resolve("alert-rules");
        Files.createDirectories(d);
        Path tmp = Files.createTempFile(d, ".bind-", ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, d.resolve(rule + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** One Alert Rule's binding to an Investigation, raw, or null when that rule was never bound to it. */
    String readAlertRuleBinding(String investigationId, String rule) throws IOException {
        Path f = investigationDir(investigationId).resolve("alert-rules").resolve(rule + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    // ── Pending sensitive expands (LA-19, D-U7 four-eyes) — requests OUTSIDE the sealed log ──────────────
    //   investigations/<id>/pending/<rid>.json   status pending → approved | denied. A request is a workflow
    // record, not evidence: nothing was read while it waits, and only an APPROVED expand enters the log (carrying
    // who requested and who approved it). So it is rewritten in place — atomically — as its status moves, like
    // the Alert Rule binding above, and the log, its hashes, undo, fork and the Dossier never see a pending step.

    /** Write (or rewrite) one pending-expand record. */
    void writePending(String investigationId, String requestId, String json) throws IOException {
        Path d = investigationDir(investigationId).resolve("pending");
        Files.createDirectories(d);
        Path tmp = Files.createTempFile(d, ".req-", ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, d.resolve(requestId + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** One pending-expand record, raw, or null when it was never written. */
    String readPending(String investigationId, String requestId) throws IOException {
        Path f = investigationDir(investigationId).resolve("pending").resolve(requestId + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** Every pending-expand record of one Investigation, raw, in request-id order. */
    List<String> listPending(String investigationId) throws IOException {
        Path d = investigationDir(investigationId).resolve("pending");
        if (!Files.isDirectory(d)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(d)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
        }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString().length())
                .thenComparing(p -> p.getFileName().toString()));
        List<String> out = new ArrayList<>();
        for (Path p : files) out.add(Files.readString(p, StandardCharsets.UTF_8));
        return out;
    }

    private static void deleteTree(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }
}
