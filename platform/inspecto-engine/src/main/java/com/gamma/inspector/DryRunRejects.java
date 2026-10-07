package com.gamma.inspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-record rejects a whole-pipeline dry run found (EXECUTION-RESIDUALS X4), collected per pipeline so the
 * trigger that fired the dry run can put them on its HTTP response. Keyed by (space, pipeline) via
 * {@link com.gamma.util.CurrentSpace}, the same way {@code IngestProgress} is (SPACE-UNKEYED-STATICS-1: two Spaces
 * with a same-named pipeline must never read or clear each other's slot). The caller holds the pipeline's run
 * claim, so one pipeline has at most one run filling its slot, and {@link #take} both reads and clears it.
 */
public final class DryRunRejects {

    /**
     * One dry-run member that rejected records: its first {@link PipelineTestRun#REJECTS_PER_MEMBER} records
     * (line + reason, file order) and the uncapped total.
     */
    public record MemberRejects(String consignment, String file, long rejectTotal,
                                List<PipelineTestRun.RejectedRecord> rejects) {}

    private static final Map<String, List<MemberRejects>> BY_PIPELINE = new ConcurrentHashMap<>();

    private DryRunRejects() {}

    private static String key(String pipeline) {
        return com.gamma.util.CurrentSpace.id() + '\0' + pipeline;
    }

    /** Drop every slot belonging to {@code spaceId} (space deletion). */
    public static void forgetSpace(String spaceId) {
        if (spaceId != null) BY_PIPELINE.keySet().removeIf(k -> k.startsWith(spaceId + '\0'));
    }

    static void add(String pipeline, MemberRejects member) {
        BY_PIPELINE.computeIfAbsent(key(pipeline), p -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(member);
    }

    /** Every member recorded for {@code pipeline} since the last take, and clear the slot. Never null. */
    public static List<MemberRejects> take(String pipeline) {
        List<MemberRejects> got = BY_PIPELINE.remove(key(pipeline));
        return got == null ? List.of() : List.copyOf(got);
    }
}
