package com.gamma.la.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The filesystem {@link InvestigationStore}: a thin wrapper over {@link SnapshotStore}'s Investigation layout
 * ({@code <audit root>/investigations/<id>/...}) and {@link DraftStore}'s Draft log writer, so every byte written is exactly
 * what those wrote before the port existed (the sealed {@code workingSetHash}, the set file embedding its own hash,
 * {@code DraftStore.prefixHash} over the log lines).
 *
 * <p><b>Serialisation is this class's internal detail.</b> The JVM monitors that used to be keyed on a {@code Path} in the
 * routes live here, in ONE map ({@link #monitor}), so a "verify the version, then write" is atomic per Investigation (and per
 * Draft) within the JVM. A second JVM on the same volume is NOT protected: that is the multi-pod problem the Postgres
 * implementation exists to solve, and this class does not pretend otherwise.
 *
 * <p>⚠ TRANSITIONAL: the still Path-keyed Draft code (promote, rebase, lifecycle, admission) and a few reads
 * ({@link #investigationDir}) reach the directory through this class and take the SAME monitors via {@link #monitor}, so
 * their compound critical sections keep excluding the port's writers. Both go away with the Draft vertical.
 */
public final class FsInvestigationStore implements InvestigationStore {

    private static final ConcurrentHashMap<Path, Object> MONITORS = new ConcurrentHashMap<>();

    /** The JVM monitor serialising writers to {@code p} (an Investigation's directory, a Draft's, or the investigations root). */
    public static Object monitor(Path p) {
        return MONITORS.computeIfAbsent(p.toAbsolutePath().normalize(), k -> new Object());
    }

    private static final String MEMBERS = "members.jsonl";

    private final SnapshotStore snapshots;

    public FsInvestigationStore(Path writeRoot) {
        this.snapshots = new SnapshotStore(writeRoot);
    }

    /** TRANSITIONAL (Draft vertical): one Investigation's directory. Not part of the port. */
    public Path investigationDir(String id) {
        return snapshots.investigationDir(id);
    }

    /** TRANSITIONAL: the directory holding every Investigation. Not part of the port. */
    public Path investigationsRoot() {
        return snapshots.directory().resolve("investigations");
    }

    private Path draftDir(Scope s) {
        return DraftStore.draftDir(investigationDir(s.investigationId()), s.draftId());
    }

    /** A bare id can never leave the store (SAFE_ID is checked by the caller); this is the belt to that brace. */
    private void contained(String id) {
        Path root = investigationsRoot().normalize();
        Path target = investigationDir(id).normalize();
        if (!target.startsWith(root) || target.equals(root))
            throw new IllegalArgumentException("investigation id escapes the investigation store");
    }

    // ── identity ────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean create(String id, String headerJson) throws IOException {
        contained(id);
        synchronized (monitor(investigationsRoot())) {
            return snapshots.createInvestigation(id, headerJson);
        }
    }

    @Override
    public Optional<String> header(String id) throws IOException {
        return Optional.ofNullable(snapshots.readInvestigation(id));
    }

    @Override
    public List<String> ids() throws IOException {
        return snapshots.listInvestigations();
    }

    @Override
    public boolean createFork(String id, String headerJson, List<String> lines, List<String> sets) throws IOException {
        contained(id);
        synchronized (monitor(investigationsRoot())) {
            return snapshots.createFork(id, headerJson, lines, sets);
        }
    }

    // ── the sealed log ──────────────────────────────────────────────────────────────────────────────────

    @Override
    public long version(Scope scope) throws IOException {
        return log(scope).size();
    }

    @Override
    public List<String> log(Scope scope) throws IOException {
        return committedLines(logDir(scope).resolve("log.jsonl"));
    }

    private Path logDir(Scope scope) {
        return scope.isDraft() ? draftDir(scope) : investigationDir(scope.investigationId());
    }

    /**
     * The lines of a log file that end in a newline - the COMMITTED ones. A final fragment with no newline is a line being
     * appended right now and is not part of the log yet (a reader racing an append must not see half a line).
     */
    private static List<String> committedLines(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return List.of();
        byte[] bytes = Files.readAllBytes(f);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] != '\n') end--;
        List<String> out = new ArrayList<>();
        new String(bytes, 0, end, StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).forEach(out::add);
        return out;
    }

    @Override
    public Optional<String> set(String investigationId, int step) throws IOException {
        return Optional.ofNullable(snapshots.readSet(investigationId, step));
    }

    @Override
    public void append(Scope scope, long expectedVersion, int step, String lineJson, String setJson) throws IOException {
        Path dir = scope.isDraft() ? draftDir(scope) : investigationDir(scope.investigationId());
        synchronized (monitor(dir)) {
            if (scope.isDraft() && DraftStore.isClosed(dir)) throw new DraftClosedException(scope.draftId());
            long actual = committedLines(dir.resolve("log.jsonl")).size();
            if (actual != expectedVersion)
                throw new InvestigationVersionConflictException(scope.investigationId(), expectedVersion, actual);
            if (scope.isDraft()) DraftStore.appendStep(dir, step, lineJson, setJson);
            else snapshots.appendStep(scope.investigationId(), step, lineJson, setJson);
        }
    }

    // ── members and references ──────────────────────────────────────────────────────────────────────────

    @Override
    public List<String> members(String investigationId) throws IOException {
        return readLines(investigationDir(investigationId).resolve(MEMBERS));
    }

    @Override
    public void appendMember(String investigationId, long expectedCount, String lineJson) throws IOException {
        Path dir = investigationDir(investigationId);
        synchronized (monitor(dir)) {
            long actual = readLines(dir.resolve(MEMBERS)).size();
            if (actual != expectedCount) throw new InvestigationVersionConflictException(investigationId, expectedCount, actual);
            Files.writeString(dir.resolve(MEMBERS), lineJson + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    @Override
    public Appended appendReference(String investigationId, String key, String lineJson, int maxCount) throws IOException {
        return switch (snapshots.appendReference(investigationId, key, lineJson, maxCount)) {
            case ADDED -> Appended.ADDED;
            case DUPLICATE -> Appended.DUPLICATE;
            case FULL -> Appended.FULL;
        };
    }

    @Override
    public List<String> references(String investigationId) throws IOException {
        return snapshots.readReferences(investigationId);
    }

    private static List<String> readLines(Path f) throws IOException {
        if (!Files.isRegularFile(f)) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line);
        return out;
    }

    // ── workflow records ────────────────────────────────────────────────────────────────────────────────

    @Override
    public void bindAlertRule(String investigationId, String rule, String json) throws IOException {
        snapshots.bindAlertRule(investigationId, rule, json);
    }

    @Override
    public Optional<String> alertRuleBinding(String investigationId, String rule) throws IOException {
        return Optional.ofNullable(snapshots.readAlertRuleBinding(investigationId, rule));
    }

    @Override
    public void writePending(String investigationId, String requestId, String json) throws IOException {
        snapshots.writePending(investigationId, requestId, json);
    }

    @Override
    public Optional<String> pending(String investigationId, String requestId) throws IOException {
        return Optional.ofNullable(snapshots.readPending(investigationId, requestId));
    }

    @Override
    public List<String> listPending(String investigationId) throws IOException {
        return snapshots.listPending(investigationId);
    }

    @Override
    public void writeCaseLink(String investigationId, String json) throws IOException {
        snapshots.writeCaseLink(investigationId, json);
    }

    @Override
    public Optional<String> caseLink(String investigationId) throws IOException {
        return Optional.ofNullable(snapshots.readCaseLink(investigationId));
    }

    @Override
    public boolean deleteCaseLink(String investigationId) throws IOException {
        return snapshots.deleteCaseLink(investigationId);
    }

    @Override
    public boolean createTemplate(String id, String json) throws IOException {
        Path root = snapshots.templateDirectory().normalize();
        Path target = root.resolve(id + ".json").normalize();
        if (!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root))
            throw new IllegalArgumentException("investigation template id escapes the template store");
        return snapshots.createTemplate(id, json);
    }

    @Override
    public Optional<String> template(String id) throws IOException {
        return Optional.ofNullable(snapshots.readTemplate(id));
    }

    /**
     * TRANSITIONAL (Draft vertical): promote appends a step whose set is an existing sealed Draft set file, shared by hard
     * link (LA-DRAFT-PROMOTE-COST-1). Same order and CREATE_NEW refusal as {@link #append}; the caller holds the main
     * monitor ({@link #monitor}) across the whole promote.
     */
    public void appendStepSharingSet(String id, int step, String lineJson, Path sealedSet) throws IOException {
        snapshots.appendStepSharingSet(id, step, lineJson, sealedSet);
    }
}
