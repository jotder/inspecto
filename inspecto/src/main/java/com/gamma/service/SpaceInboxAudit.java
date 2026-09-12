package com.gamma.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two Spaces polling one inbox — scale-out plan §5.3, "inbox ownership follows Space ownership".
 *
 * <h3>🔴 Why this is worth detecting at all: there is NO pre-poll claim</h3>
 * {@code MarkerManager} writes its marker only <b>after</b> a batch commits, so a file is "claimed"
 * retroactively, never before it is read. Two pollers scanning one directory therefore both see the same
 * un-marked files as pending and both ingest them: the failure is <b>silent double-ingestion</b>, not a
 * race that self-heals. ⛔ Do not assume the dedup ledger or the marker rescues a shared inbox — neither
 * runs early enough.
 *
 * <h3>What makes it possible</h3>
 * {@code dirs.poll} is a free-form path ({@code PipelineConfigParser}: {@code require(dirs, "poll")}),
 * jailed only to the JVM-wide allowed roots by {@code PathJail} — never to its declaring Space. The
 * per-Space default (`…/data/inbox/<id>`) comes from the bundle/settings routes and is a <b>convention,
 * not a guard</b>. {@code PipelineDataDirs.conflictsFor} looks similar but fires only at pipeline
 * <em>deletion</em>, within a single write root.
 *
 * <h3>⚠ SCOPE — this sees only what this pod hosts</h3>
 * 🔴 It compares the Spaces booted in <b>this process</b>. Once Spaces are partitioned across pods
 * ({@link SpacePartition}), two Spaces on <b>different</b> pods pointing at one directory are exactly the
 * dangerous case and are <b>invisible here</b> — no pod can see the other's config. Closing that needs a
 * shared registry of declared inboxes (an ops-DB family, the way the lease is), which is filed rather than
 * built. ⛔ Do not describe this audit as enforcing the §5.3 invariant; it catches the single-node and
 * same-pod cases, which are real but are the lesser half.
 *
 * <h3>⚠ It WARNS; it does not refuse</h3>
 * Same blast-radius reasoning as {@link SpacePartition}'s unassigned-Space case: refusing at boot would
 * take down every Space on the pod over a config smell that may predate the check. The loud finding is the
 * deliverable.
 *
 * @since 5.x
 */
final class SpaceInboxAudit {

    private SpaceInboxAudit() {}

    /** One pipeline's declared inbox, with the Space that declared it. */
    record InboxDecl(String spaceId, String pipeline, String pollDir) {}

    /**
     * Inboxes declared by more than one <b>Space</b>.
     *
     * <p>⛔ Two pipelines in the SAME Space sharing an inbox is deliberately <b>not</b> a finding: they are
     * polled by one {@code CollectorService} on one pod, which is the very thing that makes it safe. The
     * invariant is about Spaces, not pipelines — flagging within-Space sharing would bury the real signal
     * in noise from a legal arrangement.
     */
    static List<String> sharedInboxFindings(List<InboxDecl> declarations) {
        Map<Path, Set<String>> spacesByDir = new LinkedHashMap<>();
        Map<Path, Set<String>> declsByDir = new LinkedHashMap<>();
        for (InboxDecl d : declarations) {
            if (d.pollDir() == null || d.pollDir().isBlank()) continue;
            Path key;
            try {
                key = Paths.get(d.pollDir()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;   // an unparseable path is the path jail's business, not this audit's
            }
            spacesByDir.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(d.spaceId());
            declsByDir.computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(d.spaceId() + '/' + d.pipeline());
        }
        List<String> findings = new ArrayList<>();
        spacesByDir.forEach((dir, spaces) -> {
            if (spaces.size() > 1)
                findings.add("shared inbox: " + declsByDir.get(dir) + " all poll '" + dir
                        + "' — these are different Spaces, so nothing claims a file before it is read and "
                        + "the same file is ingested more than once. Give each Space its own inbox.");
        });
        return findings;
    }
}
