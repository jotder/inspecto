package com.gamma.la.api;

import com.gamma.control.ComponentAccess;
import com.gamma.control.LinkAnalysisSettings;
import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InputFingerprint;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.storage.BucketFunction;
import com.gamma.la.storage.IndexBuildService;
import com.gamma.la.storage.IndexBuildService.Refused;
import com.gamma.la.storage.IndexBuildService.RunView;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexPlan;
import com.gamma.la.storage.IndexStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The scheduled index build</b> (LA-DAILY-INGEST-1, T5; decision D-ING2): what the {@code la.index.build} Job asks of
 * Link Analysis after a daily partition lands. The model is standing detection's ({@link StandingDetection}, D-LD1).
 *
 * <p><b>Principal.</b> A scheduled build has no caller, so it acts as the service principal {@code index-build:<job>}. That
 * principal holds exactly ONE capability, {@link #CAPABILITY} ({@code canBuildLinkIndex}) - a constant, not a setting, so a
 * configuration can never grant it more. Its AUTHORITY is the recorded {@code owner} user id of the Job's configuration
 * (bound when an operator configures the Job), and {@link #decide} RE-DECIDES it on every run: the build may read the Dataset
 * only while that owner, as a user id, still could ({@link ComponentAccess#canViewAs}: owner id and {@code user} shares only;
 * a {@code role} share cannot be re-resolved off a request and is refused). Anything undecidable is a refusal.
 *
 * <p><b>Mode.</b> The run reads the index {@code plan} advice ({@link IndexPlan}, the logic of {@code GET /inv/index}) and runs
 * ONLY what it recommends: nothing to do is {@code UP_TO_DATE}; {@code append}; {@code compact} (then, if files are still
 * waiting, one {@code append}); {@code full} only when the configuration states {@code allowFull} (a first build or a
 * rewritten input is expensive). It goes through the SAME {@link IndexBuildService} as {@code POST /inv/index/builds}, so a
 * refusal of the server (409 duplicate / not applicable, 422 over budget, 503 queue full) is a recorded {@code REFUSED}, never a
 * retry loop, and a running build is never started twice.
 *
 * <p><b>Output</b> is aggregate-only ({@link Outcome}); every attempt is audited as {@link LinkEventTypes#LINK_INDEX_SCHEDULED_RUN}
 * beside the standard build events.
 */
public final class ScheduledIndexBuild {
    private ScheduledIndexBuild() {}

    /** The only capability the delegated principal holds. */
    public static final String CAPABILITY = "canBuildLinkIndex";

    /** The principal is {@code index-build:<job name>}. */
    public static final String PRINCIPAL_PREFIX = "index-build:";

    /** Refusal codes (stable: they ride the audit trail and the Run message). */
    public static final String NO_OWNER = "NO_OWNER", UNDECIDABLE = "UNDECIDABLE", DATASET_GONE = "DATASET_GONE",
            DATASET_NOT_SHARED = "DATASET_NOT_SHARED", ROLE_SHARE_ONLY = "ROLE_SHARE_ONLY", MAPPING_INVALID = "MAPPING_INVALID",
            NO_INDEX_SERVICE = "NO_INDEX_SERVICE", FULL_NOT_ALLOWED = "FULL_NOT_ALLOWED", NOT_APPLICABLE = "NOT_APPLICABLE",
            BUILD_IN_PROGRESS = "BUILD_IN_PROGRESS", OVER_BUDGET = "OVER_BUDGET", BUSY = "BUSY", CANCELLED = "CANCELLED";

    /** What to build. {@code owner} is the user id the principal stands in for. */
    public record Request(String job, String dataset, String sourceCol, String targetCol, String kindCol, String timeCol,
                         String timeColZone, String weightCol, List<String> attrCols, String owner, boolean allowFull,
                         long timeoutMs) { }

    /** Aggregate-only answer: {@code result} is BUILT | UP_TO_DATE | REFUSED | FAILED | RUNNING. */
    public record Outcome(String result, String mode, String code, String message, long edges, long nodes, int deltas) { }

    /** Waits on an in-flight build before giving up (operator, 2026-10-06: wait, never make the operator pause the trigger). */
    static final int MAX_WAITS = 6;
    private static final long FIRST_BACKOFF_MS = 2_000L, MAX_BACKOFF_MS = 60_000L;
    private static volatile long firstBackoffMs = FIRST_BACKOFF_MS;

    /** Test hook: the first backoff in ms (doubling, capped); a value &lt;= 0 restores the default. */
    public static void backoffForTest(long firstMs) {
        firstBackoffMs = firstMs <= 0 ? FIRST_BACKOFF_MS : firstMs;
    }

    /**
     * Run one scheduled build now. Never throws for a refusal; an exception means the attempt itself broke. When another
     * build of the same index is in flight ({@code BUILD_IN_PROGRESS}), the run WAITS and re-plans: at most {@link #MAX_WAITS}
     * times, with a doubling backoff, and never past {@code timeoutMs} from the start. Every attempt is audited; a waited-on
     * attempt carries {@code waiting=true} and its attempt number. The re-plan may find the index already covers the
     * Dataset (the in-flight build took the new files), which is {@code UP_TO_DATE}.
     */
    public static Outcome run(Path writeRoot, Path dataRoot, Request r) {
        long start = System.currentTimeMillis(), backoff = firstBackoffMs;
        for (int waits = 0; ; waits++) {
            Outcome o = attempt(writeRoot, dataRoot, r);
            boolean wait = BUILD_IN_PROGRESS.equals(o.code()) && waits < MAX_WAITS
                    && System.currentTimeMillis() - start + backoff < r.timeoutMs();
            if (!wait && BUILD_IN_PROGRESS.equals(o.code()) && waits > 0)
                o = new Outcome(o.result(), o.mode(), o.code(), o.message() + " (still in progress after " + waits + " waits)",
                        o.edges(), o.nodes(), o.deltas());
            audit(r, o, wait, waits + 1);
            if (!wait) return o;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return o;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    // ── the decision ─────────────────────────────────────────────────────────────────────────────────────

    /** The pure authority re-decision: the refusal code, or null when the principal may build over {@code dataset}. */
    static String decide(String owner, Map<String, Object> datasetContent) {
        if (owner == null || owner.isBlank()) return NO_OWNER;
        if (datasetContent == null) return DATASET_GONE;
        // The principal passes NO capabilities: canConfigureAccess is a live grant it cannot confirm.
        return switch (ComponentAccess.canViewAs(owner, Set.of(), datasetContent)) {
            case ALLOWED -> null;
            case ROLE_ONLY -> ROLE_SHARE_ONLY;
            case DENIED -> DATASET_NOT_SHARED;
        };
    }

    private static String describe(String code) {
        return switch (code) {
            case NO_OWNER -> "the Job records no owner id, so nothing can be decided";
            case DATASET_GONE -> "the Dataset no longer exists";
            case DATASET_NOT_SHARED -> "the recorded owner can no longer view the Dataset";
            case ROLE_SHARE_ONLY -> "the Dataset is shared to the owner only through a role share, which cannot be re-resolved "
                    + "without a request; share it to the owner by user id";
            default -> code;
        };
    }

    // ── the run ──────────────────────────────────────────────────────────────────────────────────────────

    private static Outcome attempt(Path writeRoot, Path dataRoot, Request r) {
        String principal = PRINCIPAL_PREFIX + r.job();
        DatasetProvider provider = DatasetProviders.active().orElse(null);
        if (provider == null) return refused(null, UNDECIDABLE, "no Dataset provider is bound, so the Dataset cannot be read");

        Map<String, Object> content = provider.dataset(writeRoot, r.dataset()).orElse(null);
        String denied = decide(r.owner(), content);
        if (denied != null) return refused(null, denied, describe(denied));

        String relationSql;
        List<String> columns;
        try {
            relationSql = provider.relationSql(content, dataRoot, writeRoot);
            columns = InvRoutes.relationColumns(r.dataset(), relationSql);
        } catch (RuntimeException unusable) {
            return refused(null, MAPPING_INVALID, "the Dataset's columns cannot be read");
        }
        IndexMapping mapping;
        IndexStore store;
        try {
            List<String> mapped = new ArrayList<>(List.of(r.sourceCol(), r.targetCol()));
            for (String c : new String[] {r.kindCol(), r.timeCol(), r.weightCol()}) if (c != null) mapped.add(c);
            mapped.addAll(r.attrCols());
            for (String c : mapped)
                if (columns.stream().noneMatch(x -> x.equalsIgnoreCase(c)))
                    return refused(null, MAPPING_INVALID, "a mapped column is not a column of the Dataset");
            if (r.timeColZone() != null) {
                if (r.timeCol() == null) return refused(null, MAPPING_INVALID, "a time zone needs a time column");
                ZoneId.of(r.timeColZone());
            }
            mapping = new IndexMapping(r.sourceCol(), r.targetCol(), r.kindCol(), r.timeCol(), r.timeColZone(), r.weightCol(), r.attrCols());
            store = new IndexStore(IndexRoutes.indexRoot(writeRoot), r.dataset(), mapping.hash());
        } catch (DateTimeException | IllegalArgumentException bad) {
            return refused(null, MAPPING_INVALID, "the mapping is not valid for this Dataset");
        }

        IndexBuildService svc = IndexRoutes.scheduledService(writeRoot);
        if (svc == null) return refused(null, NO_INDEX_SERVICE, "the control API is not serving the index in this process");

        LinkAnalysisSettings.Index ix = LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex();
        long deadline = System.currentTimeMillis() + r.timeoutMs();
        Outcome last = null;
        for (int step = 0; step < 2; step++) {                       // at most: compact, then append
            IndexManifest live = liveManifest(store);
            IndexBuilder.Mode mode;
            if (live == null) {
                mode = IndexBuilder.Mode.FULL;
            } else {
                InputFingerprint input = IndexRoutes.currentInput(dataRoot, writeRoot, r.dataset());
                List<IndexManifest.InputFile> now = input != null && input.known()
                        ? input.files().stream().map(f -> new IndexManifest.InputFile(f.path(), f.size(), f.mtimeMillis())).toList() : null;
                IndexPlan.Plan plan = IndexPlan.classify(live, now, hashOrNull(provider, content, dataRoot, writeRoot),
                        BucketFunction.NAME, duckdbOrNull());
                switch (plan.recommended()) {
                    case NONE -> {
                        return last != null ? last : done("UP_TO_DATE", "none", "the index already covers the Dataset", live);
                    }
                    case APPEND -> mode = IndexBuilder.Mode.APPEND;
                    case COMPACT -> mode = IndexBuilder.Mode.COMPACT;
                    default -> mode = IndexBuilder.Mode.FULL;
                }
            }
            if (mode == IndexBuilder.Mode.FULL && !r.allowFull())
                return refused("full", FULL_NOT_ALLOWED, live == null
                        ? "the index does not exist yet and a first full build is not allowed (set allow_full to build it)"
                        : "the server recommends a full build (input removed, rewritten or redefined) and allow_full is not set");
            last = submitAndWait(svc, principal, r, mapping, mode, relationSql, writeRoot, dataRoot, ix, deadline);
            if (!"BUILT".equals(last.result()) || mode != IndexBuilder.Mode.COMPACT) return last;
        }
        return last;
    }

    private static Outcome submitAndWait(IndexBuildService svc, String principal, Request r, IndexMapping mapping,
                                         IndexBuilder.Mode mode, String relationSql, Path writeRoot, Path dataRoot,
                                         LinkAnalysisSettings.Index ix, long deadline) {
        String m = mode.name().toLowerCase(Locale.ROOT);
        RunView v;
        try {
            v = svc.submit(new IndexBuildService.Request(principal, r.dataset(), mapping,
                    ds -> IndexRoutes.relation(dataRoot, writeRoot, r.dataset(), relationSql),
                    ix.maxDiskBytesInForce(), ix.keepVersionsInForce(), mode));
        } catch (Refused refused) {
            return refused(m, switch (refused.kind()) {
                case DUPLICATE -> BUILD_IN_PROGRESS;
                case OVER_BUDGET, ESTIMATE_TIMEOUT -> OVER_BUDGET;
                case REJECTED, ESTIMATE_BUSY -> BUSY;
                default -> NOT_APPLICABLE;
            }, refused.getMessage());
        } catch (IllegalArgumentException unsafe) {
            return refused(m, MAPPING_INVALID, "the Dataset cannot name an index directory");
        }
        try {
            v = svc.await(v.id(), Math.max(1L, deadline - System.currentTimeMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome("FAILED", m, null, "interrupted while waiting for the build", 0L, 0L, 0);
        }
        return switch (v.status()) {
            case COMPLETED -> {
                IndexBuilder.Result res = v.result();
                int deltas = res != null && res.manifest() != null ? res.manifest().deltas().size() : 0;
                yield new Outcome("BUILT", m, null, "index version " + (res == null ? "?" : res.version()) + " published",
                        res == null ? 0L : res.edges(), res == null ? 0L : res.nodes(), deltas);
            }
            case FAILED -> new Outcome("FAILED", m, null, "the build failed (" + v.failure() + "); nothing was published", 0L, 0L, 0);
            case CANCELLED -> new Outcome("FAILED", m, CANCELLED, "the build was cancelled; nothing was published", 0L, 0L, 0);
            case QUEUED, RUNNING -> new Outcome("RUNNING", m, null, "the build was still running when the wait ended", 0L, 0L, 0);
        };
    }

    private static IndexManifest liveManifest(IndexStore store) {
        try {
            Optional<Path> cur = store.current();
            return cur.isEmpty() ? null : IndexManifest.read(cur.get());
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }

    private static String hashOrNull(DatasetProvider p, Map<String, Object> content, Path dataRoot, Path writeRoot) {
        try {
            return IndexBuilder.relationSqlHash(p.relationSql(content, dataRoot, writeRoot));
        } catch (RuntimeException unresolvable) {
            return null;
        }
    }

    private static String duckdbOrNull() {
        try {
            return IndexBuilder.duckdbVersion();
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    private static Outcome done(String result, String mode, String message, IndexManifest m) {
        long edges = m.tables().containsKey("out") ? m.tables().get("out").rows() : 0L;
        long nodes = m.tables().containsKey("nodes") ? m.tables().get("nodes").rows() : 0L;
        return new Outcome(result, mode, null, message, edges, nodes, m.deltas().size());
    }

    private static Outcome refused(String mode, String code, String message) {
        return new Outcome("REFUSED", mode, code, message, 0L, 0L, 0);
    }

    /** Best effort: an audit failure never fails a build. Never a column name or a row value. */
    private static void audit(Request r, Outcome o, boolean waiting, int attempt) {
        try {
            Event.Builder b = Event.builder(LinkEventTypes.LINK_INDEX_SCHEDULED_RUN).source("inv")
                    .message("link.index.scheduled.run - " + r.dataset() + " - " + o.result().toLowerCase(Locale.ROOT))
                    .actor(PRINCIPAL_PREFIX + r.job()).actorType("service")
                    .action("link.index.scheduled.run").actionCategory("analysis")
                    .attr("dataset", r.dataset()).attr("result", o.result()).attr("mode", o.mode() == null ? "" : o.mode());
            if (o.code() != null) b = b.attr("code", o.code());
            b = b.attr("attempt", String.valueOf(attempt));
            if (waiting) b = b.attr("waiting", "true");
            EventLog.current().emit(b);
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
