package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Subject;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
import com.gamma.pipeline.ComponentStore;
import com.gamma.alert.AnomalyScoreOutputs;
import com.gamma.alert.RiskScoreOutputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Deferred seed of an Alert Rule over a Space's own score output ({@code TEMPLATE-RISK-SCORE-ALERT-RULE-1},
 * operator 2026-10-06; generalised to every score kind by ANOMALY-DETECTION-1 D-AD7, operator 2026-10-10). A
 * per-entity rule's {@code by} columns are checked against its Dataset's Schema at save, and a score model's
 * {@code <prefix><model>_latest} store has no Schema until the model first runs — so a Space Template declares such a
 * rule as PENDING, in {@code config/pending/alert-rules/<name>.toon}: the rule body plus
 * {@code afterScore: {kind, model}}, where {@code kind} is a score kind of {@link #KINDS} ({@code risk-score} reads
 * {@code risk_scores_<model>_latest}, {@code anomaly-model} reads {@code anomaly_scores_<model>_latest}).
 *
 * <p>When that model's Job has written its output ({@code risk.score.produced} / {@code anomaly.score.produced}), each
 * pending rule naming the kind and model goes through the normal save gate ({@link AlertRoutes#parse}, the {@code by} Schema
 * check included) and is written to {@code registry/alert-rules/} and armed; the {@code _latest} Dataset it reads is
 * registered first through the Dataset save gate, since the Risk Score gate refuses one before the output exists. Never forced:
 * <ul>
 *   <li>a refusal keeps the rule pending, emits an AUDIT {@code alert-rule.pending.refused} event with the reason,
 *       and is retried on the model's next run;</li>
 *   <li>a Space whose approval policy holds {@code alert-rule} writes is refused the same way — a writer outside any
 *       request cannot be approved, so it does not write (as {@link PendingChanges#governs} prescribes);</li>
 *   <li>{@link DecisionRuleGuard#refuseUnattended} runs before any write: a background writer has no Subject to
 *       hold {@code canWorkIncidents}, so a rule carrying an {@code invoke-api} consequence is refused (fail closed);</li>
 *   <li>a rule that already exists under that name is never overwritten: the pending entry is dropped and audited.</li>
 * </ul>
 * The latest refusal's reason and time are also kept beside the pending file, in {@code <name>}{@value #REFUSAL_EXT}
 * (written atomically; the rule body is never touched), and served by {@code GET /alerts/rules/pending} as
 * {@code lastRefusal: {reason, at}} — the AUDIT event is still emitted. The stored reason is scrubbed of absolute paths.
 * Creation deletes the pending file, so a rule is materialized at most once. The applier's {@code canAuthorAlertRules}
 * was checked when the template was applied ({@link ImportCapabilityGuard}).
 */
public final class PendingAlertRules {

    private static final Logger log = LoggerFactory.getLogger(PendingAlertRules.class);

    /** Config-relative directory of the pending rules. */
    public static final String DIR = "pending/alert-rules";
    /** The key naming the score model whose first run releases the rule: {@code {kind, model}} (D-AD7). */
    public static final String AFTER = "afterScore";

    /** A score kind whose {@code _latest} output a pending rule may read: its prefix and ownership marker. */
    record ScoreKind(String kind, String scoresPrefix, String latestSuffix, String ownerMarker) {
        String latest(String model) { return scoresPrefix + model + latestSuffix; }
        boolean ownedBy(Path dir, String model) {
            return com.gamma.alert.ScoreOutputDirs.ownedBy(dir, ownerMarker, model);
        }
    }

    /** Every score kind a pending rule can wait for, by component kind. */
    static final Map<String, ScoreKind> KINDS = Map.of(
            RiskScoreOutputs.KIND, new ScoreKind(RiskScoreOutputs.KIND, RiskScoreOutputs.SCORES_PREFIX,
                    RiskScoreOutputs.LATEST_SUFFIX, RiskScoreOutputs.OWNER_MARKER),
            AnomalyScoreOutputs.KIND, new ScoreKind(AnomalyScoreOutputs.KIND, AnomalyScoreOutputs.SCORES_PREFIX,
                    AnomalyScoreOutputs.LATEST_SUFFIX, AnomalyScoreOutputs.OWNER_MARKER));

    /** A parsed {@code afterScore}: the score kind and model id. */
    record After(ScoreKind kind, String model) {}

    /** Parse {@code afterScore}; throws {@link IllegalArgumentException} naming what is wrong. */
    static After after(Object raw) {
        if (!(raw instanceof Map<?, ?> m))
            throw new IllegalArgumentException(AFTER + " must be {kind, model}: the score kind (one of "
                    + new java.util.TreeSet<>(KINDS.keySet()) + ") and the model whose first run creates the rule");
        for (Object k : m.keySet())
            if (!"kind".equals(k) && !"model".equals(k))
                throw new IllegalArgumentException(AFTER + ": unknown key '" + k + "' (expected [kind, model])");
        ScoreKind kind = KINDS.get(String.valueOf(m.get("kind")));
        if (kind == null)
            throw new IllegalArgumentException(AFTER + ".kind must be one of " + new java.util.TreeSet<>(KINDS.keySet())
                    + ", got '" + m.get("kind") + "'");
        if (!(m.get("model") instanceof String model) || model.isBlank())
            throw new IllegalArgumentException(AFTER + ".model must name the " + kind.kind() + " whose first run creates it");
        return new After(kind, model);
    }
    /** Extension of the sidecar holding a pending rule's latest refusal (not {@code .toon}, so never listed as a rule). */
    static final String REFUSAL_EXT = ".refusal";

    private PendingAlertRules() {}

    /** The pending rules under {@code configRoot}: {@code {name, afterScore: {kind, model}, dataset}}, sorted by name. */
    public static List<Map<String, Object>> list(Path configRoot) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Path f : files(configRoot)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", nameOf(f));
            try {
                Map<String, Object> body = read(f);
                m.put(AFTER, body.get(AFTER));
                m.put("dataset", body.get("dataset"));
            } catch (RuntimeException unreadable) {
                m.put("error", "unreadable: " + unreadable.getMessage());
            }
            Map<String, Object> refusal = readRefusal(f);
            if (refusal != null) m.put("lastRefusal", refusal);
            out.add(m);
        }
        return out;
    }

    /**
     * Template-time check of one pending rule (run by {@link TemplateSeedGate}): it parses as an Alert Rule, names a
     * score kind and a model of that kind the template seeds, and reads that model's {@code _latest} store. Throws
     * {@link IllegalArgumentException} with the reason.
     */
    static void requireDeclarable(Path configRoot, Map<String, Object> body, String name) {
        After after = after(body.get(AFTER));
        if (!new ComponentStore(configRoot.resolve("registry")).exists(after.kind().kind(), after.model()))
            throw new IllegalArgumentException(AFTER + " names unknown " + after.kind().kind() + " '" + after.model() + "'");
        Map<String, Object> rule = ruleBody(body, name);
        AlertRule parsed = AlertRule.fromMap(rule);
        String latest = after.kind().latest(after.model());
        if (!latest.equals(parsed.dataset()))
            throw new IllegalArgumentException("a pending Alert Rule reads its score model's output: dataset must be '"
                    + latest + "'");
    }

    /**
     * After the {@code kind} model {@code modelId}'s run wrote its output: create each pending rule over it through the
     * save gate. Synchronized so two runs finishing together cannot both create one rule. Returns the names created.
     * Serves every score kind (D-AD7).
     */
    public static synchronized List<String> onScoreProduced(Path configRoot, Supplier<Path> dataRoot, String kind,
                                                            String modelId, AlertService alerts, EventLog events) {
        List<String> created = new ArrayList<>();
        if (configRoot == null || modelId == null || !KINDS.containsKey(kind)) return created;
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        for (Path f : files(configRoot)) {
            String name = nameOf(f);
            Map<String, Object> body;
            try {
                body = read(f);
            } catch (RuntimeException unreadable) {
                audit(events, "alert-rule.pending.refused", name, kind, modelId, "unreadable: " + unreadable.getMessage());
                recordRefusal(configRoot, f, "the pending file could not be read");
                continue;
            }
            After after;
            try {
                after = after(body.get(AFTER));
            } catch (IllegalArgumentException malformed) {
                continue;   // names no kind + model, so no run can be its trigger (the template seed gate refuses it)
            }
            if (!kind.equals(after.kind().kind()) || !modelId.equals(after.model())) continue;
            try {
                if (store.exists(AlertRoutes.TYPE, name)) {
                    Files.deleteIfExists(f);
                    Files.deleteIfExists(refusalFile(f));
                    audit(events, "alert-rule.pending.dropped", name, kind, modelId,
                            "an Alert Rule named '" + name + "' already exists; it was not overwritten");
                    continue;
                }
                for (String held : List.of(AlertRoutes.TYPE, "dataset"))
                    if (PendingChanges.governs(configRoot, held))
                        throw new IllegalArgumentException("this Space's approval policy holds " + held + " changes, "
                                + "and a deferred seed cannot be approved — create the rule through the Alert Rules page");
                DecisionRuleGuard.refuseUnattended(ruleBody(body, name));
                ensureLatestDataset(configRoot, dataRoot, store, after.kind(), modelId);
                AlertRule rule = AlertRoutes.parse(configRoot, dataRoot, ruleBody(body, name));
                store.write(AlertRoutes.TYPE, name, rule.toMap());
                if (alerts != null) alerts.upsert(rule);
                Files.deleteIfExists(f);
                Files.deleteIfExists(refusalFile(f));
                created.add(name);
                audit(events, "alert-rule.pending.created", name, kind, modelId, null);
            } catch (ApiException | IllegalArgumentException refused) {
                audit(events, "alert-rule.pending.refused", name, kind, modelId, refused.getMessage());
                recordRefusal(configRoot, f, refused.getMessage());
            } catch (IOException | UncheckedIOException io) {
                audit(events, "alert-rule.pending.refused", name, kind, modelId, "write failed: " + io.getMessage());
                // An I/O message names host paths: the served reason says only that the write failed.
                recordRefusal(configRoot, f, "write failed");
            }
        }
        return created;
    }

    /**
     * The Dataset the rule reads, {@code <prefix><model>_latest} (id = physicalRef), registered through the
     * Dataset save gate ({@link ComponentRoutes#validateKind}) if absent. It cannot be seeded with the template: the
     * score kind's save gate refuses a Dataset over its output store until the model has written it.
     */
    private static void ensureLatestDataset(Path configRoot, Supplier<Path> dataRoot, ComponentStore store,
                                            ScoreKind kind, String modelId) throws IOException {
        String latest = kind.latest(modelId);
        if (store.exists("dataset", latest)) return;
        Map<String, Object> ds = new LinkedHashMap<>(Map.of("physicalRef", latest));
        ComponentRoutes.validateKind(configRoot, dataRoot, "dataset", latest, ds);
        if (dataRoot.get() == null || !kind.ownedBy(dataRoot.get().resolve(latest), modelId))
            throw new IllegalArgumentException(kind.kind() + " '" + modelId + "' has not written '" + latest + "' yet");
        store.write("dataset", latest, ds);
    }

    private static Path refusalFile(Path pending) {
        return pending.resolveSibling(nameOf(pending) + REFUSAL_EXT);
    }

    /**
     * Keeps {@code reason} as the rule's latest refusal: written to a temp file and atomically moved over the sidecar,
     * so a reader sees the old or the new record, never a torn one. Best effort — the AUDIT event is the durable record.
     */
    private static void recordRefusal(Path configRoot, Path pending, String reason) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("reason", scrub(configRoot, reason));
        rec.put("at", java.time.Instant.now().toString());
        Path target = refusalFile(pending);
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.writeString(tmp, com.gamma.config.io.ConfigCodec.toToon(rec));
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.warn("could not record the refusal of pending Alert Rule '{}': {}", nameOf(pending), e.toString());
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
        }
    }

    /** The reason with the Space's absolute location replaced, so no host path outside the Space is served. */
    static String scrub(Path configRoot, String reason) {
        String r = reason == null ? "refused" : reason;
        Path space = configRoot.toAbsolutePath().normalize().getParent();
        if (space != null) {
            r = r.replace(space.toString() + space.getFileSystem().getSeparator(), "")
                    .replace(space.toString(), "<space>");
        }
        return r;
    }

    private static Map<String, Object> readRefusal(Path pending) {
        Path f = refusalFile(pending);
        if (!Files.isRegularFile(f)) return null;
        try {
            Map<String, Object> m = com.gamma.util.ToonHelper.load(f.toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reason", String.valueOf(m.get("reason")));
            out.put("at", String.valueOf(m.get("at")));
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> ruleBody(Map<String, Object> body, String name) {
        Map<String, Object> rule = new LinkedHashMap<>(body);
        rule.remove(AFTER);
        rule.put("name", name);
        return rule;
    }

    private static void audit(EventLog events, String action, String rule, String kind, String model, String reason) {
        String msg = switch (action) {
            case "alert-rule.pending.created" -> "pending Alert Rule '" + rule + "' created after " + kind + " '"
                    + model + "' ran";
            case "alert-rule.pending.dropped" -> "pending Alert Rule '" + rule + "' dropped: " + reason;
            default -> "pending Alert Rule '" + rule + "' stays pending (retried on the next " + kind + " '" + model
                    + "' run): " + reason;
        };
        if (reason == null) log.info(msg);
        else log.warn(msg);
        if (events == null) return;
        try {
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(msg)
                    .actor("system").actorType("system").action(action).actionCategory("configuration")
                    .attr("alertRule", rule).attr("scoreKind", kind).attr("model", model);
            if (reason != null) b.attr("reason", reason).level(EventLevel.WARN);
            events.emit(b);
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit — the pending file is the record
        }
    }

    private static List<Path> files(Path configRoot) {
        if (configRoot == null) return List.of();
        Path dir = configRoot.resolve(DIR);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".toon") && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nameOf(Path f) {
        String n = f.getFileName().toString();
        return n.substring(0, n.length() - ".toon".length());
    }

    private static Map<String, Object> read(Path f) {
        try {
            return com.gamma.util.ToonHelper.load(f.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
