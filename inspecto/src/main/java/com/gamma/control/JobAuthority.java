package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * <b>Who may make a Job run, decided at authoring AND at run time</b> ({@code MAINT-TASK-AUTHORITY-1}, session
 * decision 2026-09-27).
 * <ol>
 *   <li><b>Every Job carries an author.</b> Each server write door ({@code POST}/{@code PUT /jobs},
 *       {@code /config/write} + {@code /config/patch} type {@code job}, every import door) calls {@link #stamp}:
 *       {@link JobConfig#AUTHOR_KEYS} are SERVER-STAMPED from the authenticated {@link Subject} — any value the
 *       client sent is discarded. Without a Subject (no Authenticator: Personal / an open dev server) nothing is
 *       stamped and any carried value is still stripped.</li>
 *   <li><b>The runner re-checks.</b> {@link #runAuthority} is installed into {@link JobService}: a Job that
 *       {@link JobRoutes#requiresAdminister needs canAdminister} runs only if its last editor's recorded roles,
 *       re-resolved against the role table as it is NOW (as {@code PendingChanges.authorNow} does for a
 *       replay), still grant {@code canAdminister}. Otherwise the run is {@code REJECTED} with the reason and an
 *       {@code AUDIT} event {@code job.run.refused}. A legacy Job with no recorded author is refused until an
 *       administrator re-saves it (logged once per Job). No Authenticator ⇒ nothing is refused, as before.</li>
 * </ol>
 * ⚠ The re-check sees a change to the ROLE TABLE (a role losing {@code canAdminister}, a deny grant); it cannot see
 * an identity provider dropping the user from a group, because the server holds no user → role store of its own —
 * the roles recorded at save time are what is re-resolved.
 */
final class JobAuthority {

    private static final Logger LOG = LoggerFactory.getLogger(JobAuthority.class);

    private JobAuthority() {}

    // ── stamping ──────────────────────────────────────────────────────────────────────────────

    /**
     * The {@code job:} section {@code job} with its author keys server-stamped: client values dropped;
     * {@code createdBy} = {@code priorCreatedBy} (the stored Job's) or the writer, {@code updatedBy} = the writer,
     * {@code updatedByRoles} = the writer's held roles. Nothing stamped without a Subject.
     */
    static Map<String, Object> stamp(HttpExchange ex, Map<String, Object> job, String priorCreatedBy) {
        Map<String, Object> out = new LinkedHashMap<>(job);
        JobConfig.AUTHOR_KEYS.forEach(out::remove);
        Optional<Subject> s = ApiContext.subject(ex);
        if (s.isEmpty()) return out;
        String by = s.get().id();
        out.put(JobConfig.CREATED_BY, priorCreatedBy == null || priorCreatedBy.isBlank() ? by : priorCreatedBy);
        out.put(JobConfig.UPDATED_BY, by);
        out.put(JobConfig.UPDATED_BY_ROLES, String.join(",", new TreeSet<>(ComponentAccess.heldRoles(ex))));
        return out;
    }

    /** {@link #stamp(HttpExchange, Map, String)} over a parsed Job; {@code existing} is the stored one, or null. */
    static JobConfig stamp(HttpExchange ex, JobConfig c, JobConfig existing) {
        String prior = existing == null ? null : existing.params().get(JobConfig.CREATED_BY);
        return JobConfig.fromMap(Map.of("job", stamp(ex, c.toMap(), prior)));
    }

    /** A config-write draft ({@code {job: {...}}} or the bare section), stamped; {@code prior} as above. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> stampDraft(HttpExchange ex, Map<String, Object> draft, String prior) {
        if (draft.get("job") instanceof Map<?, ?> j) {
            Map<String, Object> out = new LinkedHashMap<>(draft);
            out.put("job", stamp(ex, (Map<String, Object>) j, prior));
            return out;
        }
        return stamp(ex, draft, prior);
    }

    /** The stored {@code createdBy} of a decoded Job doc (either shape), or null. */
    static String createdByOf(Map<String, Object> doc) {
        Object j = doc == null ? null : doc.get("job") instanceof Map<?, ?> m ? m.get(JobConfig.CREATED_BY) : doc.get(JobConfig.CREATED_BY);
        return j == null ? null : String.valueOf(j);
    }

    /**
     * A raw import's entries with every {@code *_job.toon} stamped (re-encoded only when that changed it); other
     * entries untouched. An
     * unparseable Job file is left to the loader / spec gate, which refuse it. Returns {@code entries} itself when
     * nothing changed.
     */
    static Map<String, byte[]> stampFiles(HttpExchange ex, Map<String, byte[]> entries) {
        LinkedHashMap<String, byte[]> out = null;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!ImportCapabilityGuard.normalizedPath(e.getKey()).endsWith("_job.toon")) continue;
            Map<String, Object> doc;
            try {
                doc = ConfigCodec.toMap(new String(e.getValue(), StandardCharsets.UTF_8));
            } catch (RuntimeException unparseable) {
                continue;
            }
            Map<String, Object> stamped = stampDraft(ex, doc, null);
            if (stamped.equals(doc)) continue;   // nothing to strip or stamp (no Subject): the bytes stay verbatim
            if (out == null) out = new LinkedHashMap<>(entries);
            out.put(e.getKey(), ConfigCodec.toToon(stamped).getBytes(StandardCharsets.UTF_8));
        }
        return out == null ? entries : out;
    }

    /** {@link #stampFiles} over a raw bundle's config entries. */
    static com.gamma.service.BundleImporter.Bundle stampBundle(HttpExchange ex, com.gamma.service.BundleImporter.Bundle b) {
        Map<String, byte[]> stamped = stampFiles(ex, b.configEntries());
        return stamped == b.configEntries() ? b
                : new com.gamma.service.BundleImporter.Bundle(b.kind(), b.manifest(), new LinkedHashMap<>(stamped), b.spaceToon());
    }

    // ── run time ──────────────────────────────────────────────────────────────────────────────

    private static final Set<String> WARNED_UNAUTHORED = ConcurrentHashMap.newKeySet();

    /** The {@link JobService.RunAuthority} the control plane installs; {@code configRoot} is the bound Space's
     *  (the root whose role table the request gate resolves, {@code ControlApi.writeRoot()}). */
    static JobService.RunAuthority runAuthority(Supplier<Path> configRoot) {
        return cfg -> {
            if (Authenticators.active().isEmpty()) return Optional.empty();   // open server: as before
            Path root;
            try {
                root = configRoot.get();
            } catch (RuntimeException noSpace) {
                root = null;
            }
            if (!JobRoutes.requiresAdminister(cfg, root)) return Optional.empty();
            String refusal = refusal(cfg, root);
            if (refusal != null) audit(cfg, refusal);
            return Optional.ofNullable(refusal);
        };
    }

    /**
     * ASSURE-BI-PUBLICATION-1: who a {@code publish.postgres} run acts as — its last editor, with the capabilities
     * their recorded roles grant under the role table as it is NOW (deny grants applied). No Authenticator ⇒
     * {@link com.gamma.job.PostgresPublishJobType.Author#OPEN}; an unauthored Job ⇒ an author with no roles and no
     * capabilities, so it can read only unshared Datasets and never publishes a sensitive column.
     */
    static com.gamma.job.PostgresPublishJobType.Authority publishAuthority(Supplier<Path> configRoot) {
        return cfg -> {
            if (Authenticators.active().isEmpty()) return com.gamma.job.PostgresPublishJobType.Author.OPEN;
            Path root;
            try {
                root = configRoot.get();
            } catch (RuntimeException noSpace) {
                root = null;
            }
            List<String> roles = new ArrayList<>();
            for (String r : cfg.opt(JobConfig.UPDATED_BY_ROLES, "").split(","))
                if (!r.isBlank()) roles.add(r.trim().toLowerCase(Locale.ROOT));
            String by = cfg.params().get(JobConfig.UPDATED_BY);
            return new com.gamma.job.PostgresPublishJobType.Author(by == null || by.isBlank() ? null : by,
                    Set.copyOf(roles), Set.copyOf(capabilitiesNow(roles, root)), false);
        };
    }

    /** Why an administrator-only {@code cfg} may not run now, or null when its last editor still may. */
    static String refusal(JobConfig cfg, Path root) {
        String what = "'" + cfg.name() + "' (maintenance task '" + cfg.opt("task", "cleanup").toLowerCase(Locale.ROOT) + "')";
        String by = cfg.params().get(JobConfig.UPDATED_BY);
        if (by == null || by.isBlank()) {
            if (WARNED_UNAUTHORED.add(cfg.name()))
                LOG.warn("Job {} needs canAdminister and records no author — its runs are refused until an administrator "
                        + "re-saves it (MAINT-TASK-AUTHORITY-1)", what);
            return "run refused: Job " + what + " needs canAdminister and records no author — an administrator must "
                    + "re-save it before it runs";
        }
        List<String> roles = new ArrayList<>();
        for (String r : cfg.opt(JobConfig.UPDATED_BY_ROLES, "").split(","))
            if (!r.isBlank()) roles.add(r.trim().toLowerCase(Locale.ROOT));
        if (!capabilitiesNow(roles, root).contains(Roles.CAN_ADMINISTER))
            return "run refused: Job " + what + " needs canAdminister, and its last editor '" + by
                    + "' no longer holds it (roles " + roles + ")";
        return null;
    }

    /** The capabilities {@code roles} grant under {@code root}'s role table as it is now, deny grants applied. */
    static Set<String> capabilitiesNow(List<String> roles, Path root) {
        Map<String, Roles.Def> defs = Roles.effective(root);
        Set<String> caps = new TreeSet<>();
        for (String r : roles) {
            Roles.Def d = defs.get(r);
            if (d != null) caps.addAll(d.capabilities());
        }
        // Only roles the table defines, as DemoAuthenticator.authenticate passes them: AccessGrants reads any
        // unprofiled role name as "allow everywhere", so an undefined one would void every deny (fail-open).
        caps.removeAll(AccessGrants.deniedCapabilities(root, roles.stream().filter(defs::containsKey).toList()));
        return caps;
    }

    private static void audit(JobConfig cfg, String refusal) {
        try {
            EventLog log = EventLog.current();
            if (log == null) return;
            String by = cfg.params().getOrDefault(JobConfig.UPDATED_BY, "system");
            log.emit(Event.builder(EventType.AUDIT).source("audit").message(refusal)
                    .actor(by).actorType("system").action("job.run.refused").actionCategory("operation")
                    .target("job", cfg.name())
                    .attr("task", cfg.opt("task", "cleanup").toLowerCase(Locale.ROOT))
                    .attr("capability", Roles.CAN_ADMINISTER));
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit — the REJECTED run row is the record
        }
    }

    // ── cleanup targets ───────────────────────────────────────────────────────────────────────

    /** The cleanup path keys: {@code dir} is swept, {@code archive_dir} receives what is swept. */
    static final List<String> CLEANUP_PATH_KEYS = List.of("dir", "archive_dir");

    /**
     * Whether a {@code cleanup} Job's swept or archive directory overlaps the Space config root — inside it (the
     * root itself, {@code registry/}, {@code roles.toon}'s directory, every {@code ReservedConfigPaths} entry) or an
     * ancestor of it (a sweep that walks down into it). A relative value is tried against every base a runner may
     * resolve it against — the config root, {@code SpaceConfigRoot.current()} (what {@code CleanupTask} uses) and the
     * working directory (its legacy fallback). Compared canonically ({@link #canonical}). False with no config root.
     */
    static boolean cleanupTouchesConfigRoot(JobConfig c, Path configRoot) {
        if (configRoot == null || c == null || !"maintenance".equals(c.type())
                || !"cleanup".equals(c.opt("task", "cleanup").toLowerCase(Locale.ROOT))) return false;
        Path root = canonical(configRoot);
        for (String key : CLEANUP_PATH_KEYS) {
            String v = c.params().get(key);
            if (v == null || v.isBlank()) continue;
            try {
                Path raw = Path.of(v.trim());
                // every base a runner may resolve a relative value against: the config root, the Space root
                // CleanupTask reads (SpaceConfigRoot.current(), null in a legacy layout ⇒ the working directory)
                List<Path> bases = new ArrayList<>(List.of(configRoot, Path.of("")));
                Path current = com.gamma.pipeline.SpaceConfigRoot.current();
                if (current != null) bases.add(current);
                for (Path base : raw.isAbsolute() ? List.of(configRoot) : bases) {
                    Path p = canonical(raw.isAbsolute() ? raw : base.resolve(raw));
                    if (p.startsWith(root) || root.startsWith(p)) return true;
                }
            } catch (RuntimeException unreadable) {
                return true;   // fail closed: a path that cannot be read cannot be shown to miss the config root
            }
        }
        return false;
    }

    /** {@code p} absolute and normalised, its longest existing prefix resolved with {@code toRealPath} (links,
     *  Windows short names and case), the rest appended; lower-cased on Windows. */
    static Path canonical(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path existing = abs;
        List<Path> rest = new ArrayList<>();
        while (existing != null && !Files.exists(existing)) {
            rest.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        Path out = abs;
        if (existing != null) {
            try {
                out = existing.toRealPath();
                for (Path seg : rest) out = out.resolve(seg.toString());
            } catch (IOException unresolvable) {
                out = abs;
            }
        }
        if (WINDOWS) out = Path.of(out.toString().toLowerCase(Locale.ROOT));
        return out.normalize();
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
}
