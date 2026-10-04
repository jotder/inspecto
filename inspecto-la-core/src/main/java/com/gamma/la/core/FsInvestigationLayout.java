package com.gamma.la.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The on-disk layout of an Investigation's records ({@code <audit root>/investigations/<id>/...}), as {@code SnapshotStore} wrote it
 * (LA-10, decision D-E2) before the {@link InvestigationStore} port existed. An implementation detail of
 * {@link FsInvestigationStore}: nothing else may name it. Every method is the unchanged code it was, so each byte on disk is too.
 *
 * <p>Write-once discipline: a header and every per-step Working Set are {@code CREATE_NEW}, and the log only ever appends.
 */
final class FsInvestigationLayout {

    private final Path dir;

    FsInvestigationLayout(Path writeRoot) {
        this.dir = writeRoot.resolve("audit").resolve("snapshots");
    }

    Path directory() {
        return dir;
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

    // ── Investigations (LA-10, decision D-E2: the op log and its Working Sets live in THIS store) ─────────
    //
    // Layout, under the same audit root and with the same write-once discipline as a snapshot:
    //   investigations/<id>/header.json          CREATE_NEW — bindings, owner, fork lineage; never rewritten
    //   investigations/<id>/log.jsonl            APPEND — one line per step; the source of truth
    //   investigations/<id>/sets/<step>.json     CREATE_NEW — the Working Set that step evaluated to
    // ⚠ The directory name "investigations" cannot collide with a snapshot: list() keeps only *.json files.

    private static final String INVESTIGATIONS = "investigations";

    /** One Investigation's directory. The id is SAFE_ID-checked by the caller; the route jails the result. */
    public Path investigationDir(String id) {
        return dir.resolve(INVESTIGATIONS).resolve(id);
    }

    /** Create an Investigation's header. False when the id already exists — never an overwrite (409). */
    public boolean createInvestigation(String id, String headerJson) throws IOException {
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

    /**
     * Every Investigation id with a header, sorted — for {@code GET /inv/investigations}, which judges each one
     * through the read gate. A directory whose name is not a safe id, or with no header yet, is skipped.
     */
    public List<String> listInvestigations() throws IOException {
        Path root = dir.resolve(INVESTIGATIONS);
        if (!Files.isDirectory(root)) return List.of();
        List<String> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                String id = d.getFileName().toString();
                if (SnapshotStore.SAFE_ID.matcher(id).matches() && Files.isRegularFile(d.resolve("header.json"))) out.add(id);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** The header's raw JSON, or null when the Investigation was never created. */
    public String readInvestigation(String id) throws IOException {
        Path f = investigationDir(id).resolve("header.json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** The log's lines, in step order (empty for a fresh Investigation). */
    public List<String> readLog(String id) throws IOException {
        return readLogAt(investigationDir(id));
    }

    /** The log lines of whatever directory holds a {@code log.jsonl} - an Investigation's, or a Draft's (D7-3). */
    public static List<String> readLogAt(Path dir) throws IOException {
        Path f = dir.resolve("log.jsonl");
        if (!Files.isRegularFile(f)) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line);
        return out;
    }

    /**
     * One step's sealed Working Set file, raw, or null when it was never written (LA-12 reads it for the
     * dossier's integrity check and manifest). Read-only — additive beside the write paths above.
     */
    public String readSet(String id, int step) throws IOException {
        Path f = investigationDir(id).resolve("sets").resolve(step + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * Append one step, then seal the Working Set it evaluated to. ⚠ The ORDER is deliberate: the log line is the
     * source of truth and is written first; a crash before the set lands leaves a step whose set replay can
     * recompute, never a set with no step behind it.
     */
    public void appendStep(String id, int step, String lineJson, String workingSetJson) throws IOException {
        appendStepAt(investigationDir(id), step, lineJson, workingSetJson);
    }

    /** {@link #appendStep} over any directory holding a log and {@code sets/} - the ONE writer a Draft (D7-3) shares with the main log. */
    public static void appendStepAt(Path d, int step, String lineJson, String workingSetJson) throws IOException {
        Files.writeString(d.resolve("log.jsonl"), lineJson + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.createDirectories(d.resolve("sets"));
        Files.writeString(d.resolve("sets").resolve(step + ".json"), workingSetJson, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * {@link #appendStep} whose Working Set is an EXISTING sealed set file (a Draft's, at promote - LA-DRAFT-PROMOTE-COST-1): the
     * set lands as a hard link to {@code sealedSet} (O(1) - a Draft's sets grow with its state, so copying them all is quadratic in
     * its steps), or as a byte copy where links are unsupported. Same order and same CREATE_NEW refusal as {@link #appendStep}.
     */
    public void appendStepSharingSet(String id, int step, String lineJson, Path sealedSet) throws IOException {
        Path d = investigationDir(id);
        Files.writeString(d.resolve("log.jsonl"), lineJson + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.createDirectories(d.resolve("sets"));
        Path target = d.resolve("sets").resolve(step + ".json");
        try {
            Files.createLink(target, sealedSet);
        } catch (java.nio.file.FileAlreadyExistsException taken) {
            throw taken;
        } catch (IOException | UnsupportedOperationException noLink) {
            Files.copy(sealedSet, target);
        }
    }

    /**
     * Write a whole fork (header, log and sets) into a scratch directory, then MOVE it into place in one rename,
     * so a failed fork leaves nothing half-written under a real id. False when the id is already taken.
     */
    public boolean createFork(String id, String headerJson, List<String> lines, List<String> sets) throws IOException {
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
    public Path templateDirectory() {
        return dir.resolve(TEMPLATES);
    }

    /** Save one template. False when the id is already taken — never an overwrite (409). */
    public boolean createTemplate(String id, String json) throws IOException {
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
    public String readTemplate(String id) throws IOException {
        Path f = templateDirectory().resolve(id + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    // ── Alert Rule bindings (LA-23) — the owner's record that an Alert Rule may evaluate this Investigation ──
    //   investigations/<id>/alert-rules/<rule>.json   the bound rule's canonical hash, who bound it, when.
    // Written only by the owner-gated binding route; read by the alert sweep, which has no caller of its own and
    // so evaluates a rule only when a binding for exactly that rule exists here. Rewritten on re-binding (it is a
    // relationship, not evidence), so the write is atomic.

    /** Record (or replace) the binding of one Alert Rule to one Investigation. */
    public void bindAlertRule(String investigationId, String rule, String json) throws IOException {
        Path d = investigationDir(investigationId).resolve("alert-rules");
        Files.createDirectories(d);
        Path tmp = Files.createTempFile(d, ".bind-", ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, d.resolve(rule + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** One Alert Rule's binding to an Investigation, raw, or null when that rule was never bound to it. */
    public String readAlertRuleBinding(String investigationId, String rule) throws IOException {
        Path f = investigationDir(investigationId).resolve("alert-rules").resolve(rule + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    // ── Pending sensitive expands (LA-19, D-U7 four-eyes) — requests OUTSIDE the sealed log ──────────────
    //   investigations/<id>/pending/<rid>.json   status pending → approved | denied. A request is a workflow
    // record, not evidence: nothing was read while it waits, and only an APPROVED expand enters the log (carrying
    // who requested and who approved it). So it is rewritten in place — atomically — as its status moves, like
    // the Alert Rule binding above, and the log, its hashes, undo, fork and the Dossier never see a pending step.

    /** Write (or rewrite) one pending-expand record. */
    public void writePending(String investigationId, String requestId, String json) throws IOException {
        Path d = investigationDir(investigationId).resolve("pending");
        Files.createDirectories(d);
        Path tmp = Files.createTempFile(d, ".req-", ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, d.resolve(requestId + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** One pending-expand record, raw, or null when it was never written. */
    public String readPending(String investigationId, String requestId) throws IOException {
        Path f = investigationDir(investigationId).resolve("pending").resolve(requestId + ".json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** Every pending-expand record of one Investigation, raw, in request-id order. */
    public List<String> listPending(String investigationId) throws IOException {
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

    // ── Case link (LA-24) — the OPTIONAL Investigation ↔ Case relationship, OUTSIDE the sealed header ─────────
    //   investigations/<id>/case-link.json   {caseRef, linkedBy, linkedAt}. A relationship, not evidence: set at
    // create or later, removable, so rewritten atomically (like the Alert Rule binding) and never hashed into the
    // header or the Dossier manifest.

    /** Write (or replace) the Investigation's Case link. */
    public void writeCaseLink(String investigationId, String json) throws IOException {
        Path d = investigationDir(investigationId);
        Path tmp = Files.createTempFile(d, ".case-", ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, d.resolve("case-link.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** The Investigation's Case link, raw, or null when it has none. */
    public String readCaseLink(String investigationId) throws IOException {
        Path f = investigationDir(investigationId).resolve("case-link.json");
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** Remove the Investigation's Case link; false when it had none. */
    public boolean deleteCaseLink(String investigationId) throws IOException {
        return Files.deleteIfExists(investigationDir(investigationId).resolve("case-link.json"));
    }

    // ── External references (D-6) — append-only pointers OUTSIDE the sealed header ──────────────────────────
    //   investigations/<id>/references.jsonl   one JSON line per reference; only ever appended, never rewritten.
    // A pointer to something in another system, not evidence: it is not in the header (write-once) nor in the
    // Dossier manifest, so adding one never invalidates an issued Dossier.

    /** Outcomes of {@link #appendReference}. */
    public enum Appended { ADDED, DUPLICATE, FULL }

    private static final Object REFERENCES_LOCK = new Object();

    /**
     * Append one reference line unless its {@code key} is already present (DUPLICATE) or {@code maxCount} lines
     * exist (FULL). The line must start {@code {"key":<key>,}}. Check and append happen under one lock, so two
     * racing callers cannot both pass the limit.
     */
    public Appended appendReference(String investigationId, String key, String lineJson, int maxCount) throws IOException {
        synchronized (REFERENCES_LOCK) {
            List<String> have = readReferences(investigationId);
            if (have.size() >= maxCount) return Appended.FULL;
            String prefix = "{\"key\":" + quote(key) + ",";
            for (String line : have) if (line.startsWith(prefix)) return Appended.DUPLICATE;
            Files.createDirectories(investigationDir(investigationId));
            Files.writeString(investigationDir(investigationId).resolve("references.jsonl"), lineJson + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return Appended.ADDED;
        }
    }

    /** The Investigation's reference lines, raw, in the order they were added (empty when none). */
    public List<String> readReferences(String investigationId) throws IOException {
        Path f = investigationDir(investigationId).resolve("references.jsonl");
        if (!Files.isRegularFile(f)) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line);
        return out;
    }

    private static void deleteTree(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }
}
