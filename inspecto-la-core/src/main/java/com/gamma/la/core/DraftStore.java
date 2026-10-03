package com.gamma.la.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * D7-3 - the on-disk side of a <b>Draft</b> (a member's working copy of an Investigation; design
 * {@code docs/superpower/la-separation-d7-design.md} section 4). Host-free: the HTTP gate and the evaluation live in
 * {@code inspecto-la-api}.
 *
 * <pre>
 * investigations/&lt;id&gt;/drafts/&lt;draftId&gt;/
 *   header.json      write-once: draftId, investigationId, actor, createdAt, baseStep, baseLogHash, pins
 *   log.jsonl        the Draft's OWN ops, steps baseStep+1 ... (same entry shape and sealed payloads as the main log)
 *   sets/&lt;step&gt;.json   a Working Set per step (same writer as the main log: {@link SnapshotStore#appendStepAt})
 *   discarded.json   present once discarded (log.jsonl and sets/ are then deleted; see {@link #markDiscarded})
 * </pre>
 *
 * <p>The Draft's evaluated state is the MAIN log's first {@code baseStep} entries folded, then the Draft's own entries -
 * the two are one list because the Draft's steps continue the numbering, so the evaluator runs over them unchanged.
 *
 * <p>Path safety: a draft id is server-generated ({@link #newId}) and must match {@link #DRAFT_ID} exactly - one
 * segment, no dot, no separator - on every use ({@link #draftDir}).
 */
public final class DraftStore {

    private DraftStore() {}

    /** The only shape a draft id may take: {@code draft-} plus a UUID. A leading '.' (the scratch name) can never match. */
    public static final Pattern DRAFT_ID = Pattern.compile("draft-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public static final String DRAFTS = "drafts";
    public static final String HEADER = "header.json";
    public static final String DISCARDED = "discarded.json";
    public static final String PROMOTED = "promoted.json";

    /** How a staged directory is moved into place: a TEST SEAM (a test substitutes one that throws); production is one atomic rename. */
    @FunctionalInterface
    public interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    public static final Mover ATOMIC = (from, to) -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    public static volatile Mover mover = ATOMIC;

    /** TEST SEAM (D7-5): called before each main-log append of a promote with the step about to be written; a test throws to prove the rollback. */
    public static volatile java.util.function.IntConsumer promoteHook = step -> { };

    /** TEST SEAM (D7-6): counts header files read, so a test can prove a listing reads none of them. */
    public static final java.util.concurrent.atomic.AtomicLong headerReads = new java.util.concurrent.atomic.AtomicLong();

    public static String newId() {
        return "draft-" + UUID.randomUUID();
    }

    /** The directory holding every Draft of one Investigation. */
    public static Path draftsDir(Path investigationDir) {
        return investigationDir.resolve(DRAFTS);
    }

    /** One Draft's directory; {@code IllegalArgumentException} when the id is not exactly a server-generated one. */
    public static Path draftDir(Path investigationDir, String draftId) {
        if (draftId == null || !DRAFT_ID.matcher(draftId).matches())
            throw new IllegalArgumentException("draft id must match " + DRAFT_ID.pattern());
        Path root = draftsDir(investigationDir).normalize();
        Path d = root.resolve(draftId).normalize();
        if (!d.startsWith(root) || d.equals(root)) throw new IllegalArgumentException("draft id escapes the drafts directory");
        return d;
    }

    /**
     * Stage the whole Draft (header, empty {@code sets/}) in a scratch directory and MOVE it into place with one rename,
     * so a failed fork leaves no half-written directory under a real id. False when the id is already taken.
     */
    public static boolean create(Path investigationDir, String draftId, String headerJson) throws IOException {
        Path target = draftDir(investigationDir, draftId);
        Path root = draftsDir(investigationDir);
        Files.createDirectories(root);
        Path tmp = Files.createTempDirectory(root, ".fork-");   // a leading '.' never matches DRAFT_ID
        try {
            Files.writeString(tmp.resolve(HEADER), headerJson, StandardCharsets.UTF_8);
            Files.createDirectories(tmp.resolve("sets"));
            if (Files.exists(target)) {
                deleteTree(tmp);
                return false;
            }
            mover.move(tmp, target);
            return true;
        } catch (IOException | RuntimeException failed) {
            deleteTree(tmp);   // best effort: nothing was moved in, the scratch dir is all that exists
            throw failed;
        }
    }

    /** The header's raw JSON, or null when no such Draft exists. */
    public static String readHeader(Path investigationDir, String draftId) throws IOException {
        Path f = draftDir(investigationDir, draftId).resolve(HEADER);
        if (!Files.isRegularFile(f)) return null;
        headerReads.incrementAndGet();
        return Files.readString(f, StandardCharsets.UTF_8);
    }

    /** Every draft id with a header, sorted (discarded ones included: see {@link #isDiscarded}). */
    public static List<String> listIds(Path investigationDir) throws IOException {
        Path root = draftsDir(investigationDir);
        if (!Files.isDirectory(root)) return List.of();
        List<String> out = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                String id = d.getFileName().toString();
                if (DRAFT_ID.matcher(id).matches() && Files.isRegularFile(d.resolve(HEADER))) out.add(id);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Ids of the Drafts that are not closed - directory names and marker checks only, no header is read (D7-6). */
    public static List<String> openIds(Path investigationDir) {
        Path root = draftsDir(investigationDir);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var ds = Files.newDirectoryStream(root)) {
            for (Path d : ds)
                if (DRAFT_ID.matcher(d.getFileName().toString()).matches() && Files.isRegularFile(d.resolve(HEADER)) && !isClosed(d))
                    out.add(d.getFileName().toString());
        } catch (IOException unreadable) {
            // an unreadable directory counts as empty here; the caller's own read of it fails loudly
        }
        return out;
    }

    /** Open Drafts across every Investigation under {@code investigationsDir} (one Space): the D21 cap's count. */
    public static int countOpen(Path investigationsDir) {
        int n = 0;
        if (!Files.isDirectory(investigationsDir)) return 0;
        try (var ds = Files.newDirectoryStream(investigationsDir, Files::isDirectory)) {
            for (Path inv : ds) n += openIds(inv).size();
        } catch (IOException unreadable) {
            // see openIds
        }
        return n;
    }

    private static final Pattern OLD_ASIDE = Pattern.compile("\\.old-(" + DRAFT_ID.pattern() + ")-[0-9a-f-]{36}");

    /**
     * D7-6 crash sweep for the scratch directories a fork or a rebase leaves when the process dies mid-way. Deterministic, and it
     * deletes only after verifying:
     * <ul>
     *   <li>{@code .old-<id>-*} with NO {@code <id>} directory: the crash fell between the two renames of a rebase. The aside is the
     *       complete pre-rebase Draft; if its header names {@code <id>} it is moved back (the rebase simply did not happen).</li>
     *   <li>{@code .old-<id>-*} WITH a {@code <id>} directory that has its header: the new Draft is in place, only the delete was
     *       lost - the aside is deleted. Without a header the pair is left alone (counted as {@code held}).</li>
     *   <li>{@code .rebase-*} / {@code .fork-*} older than {@code scratchGrace}: a stage nobody finished - deleted.</li>
     * </ul>
     * {@code lockFor} gives the monitor a rebase holds while it swaps, so a sweep cannot move a live rebase's aside.
     *
     * @return {@code {restored, removed, held}}
     */
    public static int[] recover(Path investigationDir, java.time.Instant now, java.time.Duration scratchGrace,
                                java.util.function.Function<Path, Object> lockFor) throws IOException {
        int restored = 0, removed = 0, held = 0;
        Path root = draftsDir(investigationDir);
        if (!Files.isDirectory(root)) return new int[] {0, 0, 0};
        List<Path> entries = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) if (d.getFileName().toString().startsWith(".")) entries.add(d);
        }
        for (Path d : entries) {
            String name = d.getFileName().toString();
            var m = OLD_ASIDE.matcher(name);
            if (m.matches()) {
                String id = m.group(1);
                Path live = draftDir(investigationDir, id);
                synchronized (lockFor.apply(live)) {
                    if (!Files.isDirectory(d)) continue;   // another sweep got it
                    if (!Files.exists(live)) {
                        if (headerNames(d, id)) {
                            Files.move(d, live, StandardCopyOption.ATOMIC_MOVE);
                            restored++;
                        } else held++;
                    } else if (Files.isRegularFile(live.resolve(HEADER))) {
                        deleteTree(d);
                        removed++;
                    } else held++;
                }
            } else if ((name.startsWith(".rebase-") || name.startsWith(".fork-")) && Files.isDirectory(d)
                    && Files.getLastModifiedTime(d).toInstant().plus(scratchGrace).isBefore(now)) {
                deleteTree(d);
                removed++;
            }
        }
        return new int[] {restored, removed, held};
    }

    private static boolean headerNames(Path dir, String id) {
        try {
            Path h = dir.resolve(HEADER);
            return Files.isRegularFile(h)
                    && id.equals(InvestigationEvaluator.CANONICAL.readValue(Files.readString(h, StandardCharsets.UTF_8), java.util.Map.class).get("draftId"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static boolean isDiscarded(Path draftDir) {
        return Files.isRegularFile(draftDir.resolve(DISCARDED));
    }

    public static boolean isPromoted(Path draftDir) {
        return Files.isRegularFile(draftDir.resolve(PROMOTED));
    }

    /** A closed Draft (discarded or promoted) takes no more reads of its log and no writes, and does not count against D17. */
    public static boolean isClosed(Path draftDir) {
        return isDiscarded(draftDir) || isPromoted(draftDir);
    }

    public static String readPromoted(Path draftDir) throws IOException {
        Path f = draftDir.resolve(PROMOTED);
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * D7-5 promote: write {@code promoted.json} (CREATE_NEW; false when already promoted) and delete the log and {@code sets/} - the
     * rows now live in the main log, so the Draft keeps only its header and this marker (as a discard does).
     */
    public static boolean markPromoted(Path draftDir, String markerJson) throws IOException {
        try {
            Files.writeString(draftDir.resolve(PROMOTED), markerJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException already) {
            return false;
        }
        Files.deleteIfExists(draftDir.resolve("log.jsonl"));
        Path sets = draftDir.resolve("sets");
        if (Files.isDirectory(sets)) deleteTree(sets);
        DraftLifecycle.closed(draftDir);
        return true;
    }

    /**
     * D7-5 rebase: stage a whole replacement (header, log, sets) beside the Draft and swap it in with two renames (old aside, new in,
     * old deleted); if the second rename fails the old Draft is moved back, so a failed rebase leaves the Draft as it was. Both
     * moves go through {@link #mover} (the fault-injection seam).
     */
    public static void replaceRebased(Path draftDir, String headerJson, List<String> lines, List<String> sets, List<Integer> setSteps) throws IOException {
        Path root = draftDir.getParent();
        Path tmp = Files.createTempDirectory(root, ".rebase-");   // a leading '.' never matches DRAFT_ID
        Path old = root.resolve(".old-" + draftDir.getFileName() + "-" + UUID.randomUUID());
        try {
            Files.writeString(tmp.resolve(HEADER), headerJson, StandardCharsets.UTF_8);
            if (!lines.isEmpty()) Files.writeString(tmp.resolve("log.jsonl"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            Files.createDirectories(tmp.resolve("sets"));
            for (int i = 0; i < sets.size(); i++)
                Files.writeString(tmp.resolve("sets").resolve(setSteps.get(i) + ".json"), sets.get(i), StandardCharsets.UTF_8);
            mover.move(draftDir, old);
            try {
                mover.move(tmp, draftDir);
            } catch (IOException | RuntimeException second) {
                try {
                    Files.move(old, draftDir, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException restoreFailed) {
                    second.addSuppressed(restoreFailed);
                }
                throw second;
            }
        } catch (IOException | RuntimeException failed) {
            deleteTree(tmp);
            throw failed;
        }
        deleteTree(old);
    }

    /** The marker's raw JSON, or null when the Draft was not discarded. */
    public static String readDiscarded(Path draftDir) throws IOException {
        Path f = draftDir.resolve(DISCARDED);
        return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /**
     * Append one step to a Draft's log and seal its Working Set through the SAME writer as the main log, but undo the log
     * line if the set cannot be written, so a failed append leaves the Draft exactly as it was. (The main log keeps its
     * log-first order on purpose - replay can recompute a missing set - and is untouched; a Draft is cheap to keep strict.)
     */
    public static void appendStep(Path draftDir, int step, String lineJson, String workingSetJson) throws IOException {
        Path log = draftDir.resolve("log.jsonl");
        long before = Files.isRegularFile(log) ? Files.size(log) : 0;
        try {
            SnapshotStore.appendStepAt(draftDir, step, lineJson, workingSetJson);
        } catch (IOException | RuntimeException failed) {
            if (Files.isRegularFile(log)) {
                try (FileChannel ch = FileChannel.open(log, StandardOpenOption.WRITE)) {
                    ch.truncate(before);
                } catch (IOException ignored) {
                    // the original failure is the one to report
                }
            }
            throw failed;
        }
    }

    /**
     * Discard: write {@code discarded.json} FIRST (CREATE_NEW; false if it already exists, so a discard is idempotent), then
     * delete the log and {@code sets/}. The header and the marker stay as the audit record that the Draft existed, who
     * forked it and who discarded it; the sealed rows - which may carry personal data - do not outlive the discard (the
     * per-step audit events record the ops and fingerprints).
     */
    public static boolean markDiscarded(Path draftDir, String markerJson) throws IOException {
        try {
            Files.writeString(draftDir.resolve(DISCARDED), markerJson, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException already) {
            return false;
        }
        Files.deleteIfExists(draftDir.resolve("log.jsonl"));
        Path sets = draftDir.resolve("sets");
        if (Files.isDirectory(sets)) deleteTree(sets);
        DraftLifecycle.closed(draftDir);
        return true;
    }

    /** {@code sha256:<hex>} of the first {@code k} lines of a log, each terminated by one newline - the bytes the log file holds for them. */
    public static String prefixHash(List<String> lines, int k) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < k && i < lines.size(); i++) {
                d.update(lines.get(i).getBytes(StandardCharsets.UTF_8));
                d.update((byte) '\n');
            }
            return "sha256:" + HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path p) {
        try (var s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }
}
