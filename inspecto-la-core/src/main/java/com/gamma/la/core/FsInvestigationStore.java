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
 * The filesystem {@link InvestigationStore}: a thin wrapper over the Investigation layout {@code FsInvestigationLayout} (moved out of {@code SnapshotStore})
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

    private final FsInvestigationLayout snapshots;

    public FsInvestigationStore(Path writeRoot) {
        this.snapshots = new FsInvestigationLayout(writeRoot);
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

    @Override
    public boolean replacePending(String investigationId, String requestId, String expectedJson, String newJson) throws IOException {
        synchronized (monitor(investigationDir(investigationId))) {
            String current = snapshots.readPending(investigationId, requestId);
            if (current == null || !current.equals(expectedJson)) return false;
            snapshots.writePending(investigationId, requestId, newJson);
            return true;
        }
    }

    @Override
    public byte[] maskKey(String investigationId) throws IOException {
        return com.gamma.entitylist.MaskTokens.key(investigationDir(investigationId));
    }

    // ── per-pod cache identity ──────────────────────────────────────────────────────────────────────────

    @Override
    public String cacheKey(Scope scope) {
        return DraftCheckpoints.keyOf(logDir(scope));
    }

    @Override
    public String logToken(Scope scope) {
        return DraftCheckpoints.sig(logDir(scope).resolve("log.jsonl"));
    }

    // ── Drafts ──────────────────────────────────────────────────────────────────────────────────────────

    /** Held while a create checks the Space-wide cap and takes its seat (D21). */
    private static final Object CAP = new Object();

    private static final java.time.Duration SCRATCH_GRACE = java.time.Duration.ofHours(1);

    /** The marker a promote leaves in the Draft before it touches the main log, so a crash can be finished or undone. */
    static final String PROMOTING = "promoting.json";

    /** TEST SEAM: called after the last main step landed and before the Draft is marked promoted; a test throws an Error to simulate a crash there. */
    public static volatile Runnable promoteBeforeMarkHook = () -> { };

    private Path draftDirOf(String investigationId, String draftId) {
        return DraftStore.draftDir(investigationDir(investigationId), draftId);
    }

    @Override
    public DraftCreation createDraft(String investigationId, String draftId, String headerJson, String actor, int spaceCap)
            throws IOException {
        Path inv = investigationDir(investigationId);
        synchronized (CAP) {   // the Space-wide cap is checked and taken as one step
            synchronized (monitor(DraftStore.draftsDir(inv))) {
                for (var other : DraftIndex.headers(inv).entrySet())   // D17: ONE live Draft per member per Investigation
                    if (actor.equals(other.getValue().get("actor")) && !DraftStore.isClosed(DraftStore.draftDir(inv, other.getKey())))
                        return new DraftCreation(DraftCreation.Created.ACTOR_HAS_LIVE, other.getKey());
                int open = DraftStore.countOpen(investigationsRoot());
                if (open >= spaceCap) return new DraftCreation(DraftCreation.Created.SPACE_FULL, Integer.toString(open));
                if (!DraftStore.create(inv, draftId, headerJson)) return new DraftCreation(DraftCreation.Created.ID_TAKEN, draftId);
                return new DraftCreation(DraftCreation.Created.CREATED, draftId);
            }
        }
    }

    @Override
    public Optional<String> draftHeader(String investigationId, String draftId) throws IOException {
        return Optional.ofNullable(DraftStore.readHeader(investigationDir(investigationId), draftId));
    }

    @Override
    public java.util.Map<String, java.util.Map<String, Object>> draftHeaders(String investigationId) throws IOException {
        return DraftIndex.headers(investigationDir(investigationId));
    }

    @Override
    public List<String> openDraftIds(String investigationId) {
        return DraftStore.openIds(investigationDir(investigationId));
    }

    @Override
    public int openDraftCount() {
        return DraftStore.countOpen(investigationsRoot());
    }

    @Override
    public DraftState draftState(String investigationId, String draftId) {
        return switch (DraftLifecycle.state(draftDirOf(investigationId, draftId))) {
            case "promoted" -> DraftState.PROMOTED;
            case "discarded" -> DraftState.DISCARDED;
            case "hibernated" -> DraftState.HIBERNATED;
            default -> DraftState.OPEN;
        };
    }

    @Override
    public boolean draftExpired(String investigationId, String draftId) {
        return DraftLifecycle.wasExpired(draftDirOf(investigationId, draftId));
    }

    @Override
    public Optional<String> discardMarker(String investigationId, String draftId) throws IOException {
        return Optional.ofNullable(DraftStore.readDiscarded(draftDirOf(investigationId, draftId)));
    }

    @Override
    public Optional<String> promoteMarker(String investigationId, String draftId) throws IOException {
        return Optional.ofNullable(DraftStore.readPromoted(draftDirOf(investigationId, draftId)));
    }

    @Override
    public Optional<String> draftSet(String investigationId, String draftId, int step) throws IOException {
        Path f = draftDirOf(investigationId, draftId).resolve("sets").resolve(step + ".json");
        return Files.isRegularFile(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
    }

    @Override
    public void touchDraft(String investigationId, String draftId) {
        DraftLifecycle.touch(draftDirOf(investigationId, draftId));
    }

    @Override
    public boolean rehydrateDraft(String investigationId, String draftId) throws IOException {
        return DraftLifecycle.rehydrate(draftDirOf(investigationId, draftId));
    }

    @Override
    public java.time.Instant draftLastAccess(String investigationId, String draftId) {
        return DraftLifecycle.lastAccess(draftDirOf(investigationId, draftId));
    }

    @Override
    public java.time.Duration draftIdle(String investigationId, String draftId) {
        return DraftLifecycle.idle(draftDirOf(investigationId, draftId));
    }

    @Override
    public boolean hibernateDraft(String investigationId, String draftId, java.time.Duration after) throws IOException {
        Path dir = draftDirOf(investigationId, draftId);
        synchronized (monitor(dir)) {
            if (DraftStore.isClosed(dir) || DraftLifecycle.idle(dir).compareTo(after) < 0) return false;
            return DraftLifecycle.hibernate(dir);
        }
    }

    @Override
    public Optional<Boolean> closeDraft(String investigationId, String draftId, java.time.Duration idleAtLeast,
                                        java.util.function.Function<List<String>, String> marker) throws IOException {
        Path dir = draftDirOf(investigationId, draftId);
        synchronized (monitor(dir)) {   // serialised with appends: none lands after the marker
            if (DraftStore.isPromoted(dir)) return Optional.empty();
            if (idleAtLeast != null && (DraftStore.isClosed(dir) || DraftLifecycle.idle(dir).compareTo(idleAtLeast) < 0))
                return Optional.empty();
            if (idleAtLeast != null && !Files.isRegularFile(dir.resolve(DraftStore.HEADER))) return Optional.empty();
            boolean first = DraftStore.markDiscarded(dir, marker.apply(committedLines(dir.resolve("log.jsonl"))));
            DraftCheckpoints.forget(dir);
            return Optional.of(first);
        }
    }

    @Override
    public void promoteDraft(String investigationId, String draftId, long expectedMainVersion, String expectedMainHash,
                             String expectedDraftLogHash, List<String> lines, List<String> sets, String markerJson) throws IOException {
        Path invDir = investigationDir(investigationId);
        Path draftDir = draftDirOf(investigationId, draftId);
        synchronized (monitor(invDir)) {   // the main log, THEN the Draft: the one order everywhere
            synchronized (monitor(draftDir)) {
                if (DraftStore.isClosed(draftDir)) throw new DraftClosedException(draftId);
                List<String> main = committedLines(invDir.resolve("log.jsonl"));
                if (main.size() != expectedMainVersion || !DraftStore.prefixHash(main, main.size()).equals(expectedMainHash))
                    throw new InvestigationVersionConflictException(investigationId, expectedMainVersion, main.size());
                List<String> own = committedLines(draftDir.resolve("log.jsonl"));
                if (!DraftStore.prefixHash(own, own.size()).equals(expectedDraftLogHash))
                    throw new InvestigationVersionConflictException(investigationId, own.size(), own.size());
                Path log = invDir.resolve("log.jsonl");
                long before = Files.isRegularFile(log) ? Files.size(log) : 0;
                int from = main.size();
                // the intent first: a crash from here until the Draft is marked is finished or undone by recoverDrafts
                Files.writeString(draftDir.resolve(PROMOTING), InvestigationEvaluator.CANONICAL.writeValueAsString(java.util.Map.of(
                        "from", from, "to", from + lines.size(), "linesHash", DraftStore.prefixHash(lines, lines.size()),
                        "marker", markerJson)), StandardCharsets.UTF_8);
                try {
                    for (int i = 0; i < lines.size(); i++) {
                        DraftStore.promoteHook.accept(from + i + 1);
                        if (sets.get(i) == null)
                            snapshots.appendStepSharingSet(investigationId, from + i + 1, lines.get(i),
                                    draftDir.resolve("sets").resolve((from + i + 1) + ".json"));
                        else snapshots.appendStep(investigationId, from + i + 1, lines.get(i), sets.get(i));
                    }
                    promoteBeforeMarkHook.run();
                    if (!DraftStore.markPromoted(draftDir, markerJson)) throw new DraftClosedException(draftId);
                } catch (IOException | RuntimeException failed) {
                    truncateMain(invDir, before, from, lines.size());
                    Files.deleteIfExists(draftDir.resolve(PROMOTING));
                    throw failed;
                }
                Files.deleteIfExists(draftDir.resolve(PROMOTING));
                DraftCheckpoints.forget(draftDir);
            }
        }
    }

    /** Put the main log back as it was: truncate to {@code before} bytes and remove the set files of the steps this attempt wrote. */
    private static void truncateMain(Path invDir, long before, int from, int count) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(invDir.resolve("log.jsonl"), StandardOpenOption.WRITE)) {
            ch.truncate(before);
        } catch (IOException ignored) {
            // the caller reports the original failure
        }
        deleteSets(invDir, from, from + count);
    }

    private static void deleteSets(Path invDir, int afterStep, int throughStep) {
        for (int step = afterStep + 1; step <= throughStep; step++)
            try {
                Files.deleteIfExists(invDir.resolve("sets").resolve(step + ".json"));
            } catch (IOException ignored) {
                // best effort
            }
    }

    @Override
    public void replaceDraft(String investigationId, String draftId, long expectedMainVersion, String expectedMainHash,
                             String expectedDraftLogHash, String headerJson, List<String> lines, List<String> sets,
                             List<Integer> setSteps) throws IOException {
        Path invDir = investigationDir(investigationId);
        Path draftDir = draftDirOf(investigationId, draftId);
        synchronized (monitor(invDir)) {
            synchronized (monitor(draftDir)) {
                if (DraftStore.isClosed(draftDir)) throw new DraftClosedException(draftId);
                List<String> main = committedLines(invDir.resolve("log.jsonl"));
                if (main.size() != expectedMainVersion || !DraftStore.prefixHash(main, main.size()).equals(expectedMainHash))
                    throw new InvestigationVersionConflictException(investigationId, expectedMainVersion, main.size());
                List<String> own = committedLines(draftDir.resolve("log.jsonl"));
                if (!DraftStore.prefixHash(own, own.size()).equals(expectedDraftLogHash))
                    throw new InvestigationVersionConflictException(investigationId, own.size(), own.size());
                DraftStore.replaceRebased(draftDir, headerJson, lines, sets, setSteps);
                DraftCheckpoints.forget(draftDir);
            }
        }
    }

    @Override
    public void recoverDrafts(String investigationId) throws IOException {
        Path invDir = investigationDir(investigationId);
        if (!Files.isDirectory(DraftStore.draftsDir(invDir))) return;
        DraftStore.recover(invDir, DraftLifecycle.now(), SCRATCH_GRACE, FsInvestigationStore::monitor);
        for (String id : DraftStore.openIds(invDir)) {
            Path draftDir = DraftStore.draftDir(invDir, id);
            if (!Files.isRegularFile(draftDir.resolve(PROMOTING))) continue;
            synchronized (monitor(invDir)) {
                synchronized (monitor(draftDir)) {
                    Path intentFile = draftDir.resolve(PROMOTING);
                    if (!Files.isRegularFile(intentFile) || DraftStore.isClosed(draftDir)) continue;
                    @SuppressWarnings("unchecked") java.util.Map<String, Object> intent = InvestigationEvaluator.CANONICAL
                            .readValue(Files.readString(intentFile, StandardCharsets.UTF_8), java.util.Map.class);
                    int from = ((Number) intent.get("from")).intValue(), to = ((Number) intent.get("to")).intValue();
                    List<String> main = committedLines(invDir.resolve("log.jsonl"));
                    boolean landed = main.size() >= to && DraftStore.prefixHash(main.subList(from, to), to - from).equals(intent.get("linesHash"));
                    if (landed) {   // every main step is there: the promote happened, only the Draft's marker is missing
                        DraftStore.markPromoted(draftDir, String.valueOf(intent.get("marker")));
                        DraftCheckpoints.forget(draftDir);
                    } else if (main.size() > from) {   // partial: put the main log back to the `from` entries it held
                        long keep = 0;
                        for (int i = 0; i < from; i++) keep += main.get(i).getBytes(StandardCharsets.UTF_8).length + 1L;
                        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(invDir.resolve("log.jsonl"), StandardOpenOption.WRITE)) {
                            ch.truncate(keep);
                        }
                        deleteSets(invDir, from, Math.max(to, main.size()));
                    }
                    Files.deleteIfExists(intentFile);
                }
            }
        }
    }
}
