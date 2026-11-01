package com.gamma.actionrequests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.gamma.analytics.similarity.Similarity;
import com.gamma.config.safety.PathJail;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * <b>Incident Briefs</b> ({@code AGENTIC-INVESTIGATION-1}, slice S1): the record an investigation of one Incident
 * produces — <i>why it fired</i>, the cited <i>evidence</i>, the <i>value at risk</i> and, optionally, the
 * <i>proposed next action</i>. {@link IncidentBriefRoutes} is the HTTP surface.
 *
 * <p><b>Deterministic, no model.</b> This slice runs a fixed read-only playbook over the Incident itself, its
 * recorded impact and the similar past Incidents the caller may see. Every statement in {@code why} names the evidence
 * item it came from; nothing is inferred. Value at risk is the Incident's DECLARED impact (the Impact ledger,
 * {@code PUT /objects/{id}/impact}) — absent, the Brief says {@code declared: false}, never a guessed number.
 *
 * <p><b>The proposal never acts.</b> A proposed next action is filed as an ordinary {@code pending} Action Request
 * ({@link ActionRequestRoutes#propose}) whose makers are the human who started the Brief AND the Brief's agent
 * identity, so the mandatory four-eyes decide gate excludes both; nothing here approves, dispatches or changes the
 * Incident.
 *
 * <p><b>Storage</b> — one JSON document per Brief at {@code <write-root>/incident-briefs/<id>.json}, atomic, jailed
 * under the write root. Unsigned: a Brief decides nothing — the decision it may lead to lives in the signed Action
 * Request it links to.
 */
public final class IncidentBriefs {

    private IncidentBriefs() {}

    static final String DIR = "incident-briefs";
    static final String RECORD_TYPE = "incident-brief";
    static final Pattern SAFE_ID = Pattern.compile("ib-\\d{14}-[0-9a-f]{6}");
    /** The agent identity recorded as the Brief's author and as a co-maker of its proposal. */
    static final String AGENT_PREFIX = "agent:incident-brief:";
    static final String PLAYBOOK = "incident-brief/v1 (deterministic, no model)";
    /** How many similar past Incidents the evidence carries, and how many candidates are scored. */
    static final int SIMILAR_K = 5;
    static final int SIMILAR_SCAN = 500;
    private static final int MIN_TOKEN = 3;
    /** Attribute keys that say what raised an Incident, copied into {@code why} when present. */
    static final List<String> TRIGGER_KEYS = List.of("alertRule", "rule", "expectation", "decisionRule", "signal",
            "signalType", "metric", "measure", "threshold", "observed", "pipeline", "dataset", "job", "category");

    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    static Object lock() { return LOCK; }

    static String newId() {
        return "ib-" + STAMP.format(Instant.now()) + "-" + String.format("%06x", ThreadLocalRandom.current().nextInt(1 << 24));
    }

    /**
     * Build a Brief for {@code incident} (an {@code ObjectAccess.detail} map). {@code candidates} are other Incidents,
     * scored for similarity here; only those {@code visible} to the caller are ever cited.
     */
    static Map<String, Object> build(String id, Map<String, Object> incident, List<Map<String, Object>> candidates,
                                     Predicate<Map<String, Object>> visible, String startedBy) {
        String incidentId = str(incident, "id");
        List<Map<String, Object>> evidence = new ArrayList<>();
        evidence.add(item("e1", "incident", "GET /objects/" + incidentId, "Incident '" + str(incident, "title")
                + "', severity " + str(incident, "severity") + ", status " + str(incident, "status")));

        Map<String, Object> why = new LinkedHashMap<>();
        why.put("summary", str(incident, "title"));
        why.put("severity", str(incident, "severity"));
        why.put("status", str(incident, "status"));
        why.put("correlationId", str(incident, "correlationId"));
        Map<String, String> trigger = new LinkedHashMap<>();
        Map<?, ?> attrs = incident.get("attributes") instanceof Map<?, ?> a ? a : Map.of();
        for (String k : TRIGGER_KEYS) {
            Object v = attrs.get(k);
            if (v != null && !String.valueOf(v).isBlank()) trigger.put(k, String.valueOf(v));
        }
        why.put("trigger", trigger);
        why.put("triggerDeclared", !trigger.isEmpty());
        why.put("cites", List.of("e1"));

        Map<String, Object> valueAtRisk = valueAtRisk(incident);
        if (Boolean.TRUE.equals(valueAtRisk.get("declared"))) {
            evidence.add(item("e2", "impact", "GET /objects/" + incidentId, "declared impact: " + valueAtRisk.get("formula")));
            valueAtRisk.put("cites", List.of("e2"));
        }

        List<Map<String, Object>> similar = similar(incident, candidates, visible);
        int n = evidence.size();
        for (Map<String, Object> s : similar)
            evidence.add(item("e" + (++n), "similar-incident", "GET /objects/" + s.get("id"),
                    "similar past Incident '" + s.get("title") + "' (" + s.get("status") + "), similarity " + s.get("similarity")));

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("recordType", RECORD_TYPE);
        rec.put("id", id);
        rec.put("incidentId", incidentId);
        rec.put("startedBy", startedBy);
        rec.put("author", AGENT_PREFIX + id);
        rec.put("playbook", PLAYBOOK);
        rec.put("createdAt", Instant.now().toString());
        rec.put("why", why);
        rec.put("evidence", evidence);
        rec.put("valueAtRisk", valueAtRisk);
        rec.put("similar", similar);
        rec.put("proposal", null);
        return rec;
    }

    private static Map<String, Object> item(String id, String kind, String citation, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("citation", citation);
        m.put("summary", summary);
        return m;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * The declared impact (the ops module's typed impact, {@code outstanding} already derived there): outstanding
     * when something is confirmed, else suspected; never estimated.
     */
    static Map<String, Object> valueAtRisk(Map<String, Object> incident) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<?, ?> impact = incident.get("impact") instanceof Map<?, ?> i ? i : Map.of();
        String currency = str(impact, "currency");
        String amount = null, basis = null;
        if (str(impact, "outstanding") != null) {
            amount = str(impact, "outstanding");
            basis = "outstanding = confirmed " + str(impact, "confirmed") + " − recovered "
                    + (str(impact, "recovered") == null ? "0" : str(impact, "recovered"));
        } else if (str(impact, "suspected") != null) {
            amount = str(impact, "suspected");
            basis = "suspected " + amount + " (nothing confirmed yet)";
        }
        if (amount == null) {
            out.put("declared", false);
            out.put("note", "not declared — no impact is recorded on this Incident; the Brief never estimates one");
            return out;
        }
        out.put("declared", true);
        out.put("amount", amount);
        out.put("currency", currency);
        out.put("formula", basis + (currency == null ? "" : " " + currency));
        out.put("source", "impact ledger (PUT /objects/{id}/impact)");
        return out;
    }

    /** Top {@link #SIMILAR_K} visible candidates by token Jaccard over title + description, best first, newest as tie-break. */
    static List<Map<String, Object>> similar(Map<String, Object> incident, List<Map<String, Object>> candidates,
                                             Predicate<Map<String, Object>> visible) {
        Set<String> q = tokens(str(incident, "title") + " " + str(incident, "description"));
        if (q.isEmpty()) return List.of();
        record Scored(Map<String, Object> o, double s, long at) {}
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            if (String.valueOf(c.get("id")).equals(str(incident, "id"))) continue;
            double s = Similarity.jaccard(q, tokens(str(c, "title") + " " + str(c, "description")));
            if (s > 0.0) scored.add(new Scored(c, s, c.get("createdAt") instanceof Number t ? t.longValue() : 0L));
        }
        scored.sort(Comparator.comparingDouble(Scored::s).reversed()
                .thenComparing(Scored::at, Comparator.reverseOrder()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored sc : scored) {
            if (out.size() >= SIMILAR_K) break;
            if (!visible.test(sc.o())) continue;   // invisible reads as absent - never cited
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sc.o().get("id"));
            m.put("title", sc.o().get("title"));
            m.put("status", sc.o().get("status"));
            m.put("similarity", Math.round(sc.s() * 1000.0) / 1000.0);
            out.add(m);
        }
        return out;
    }

    /**
     * {@code brief} as {@code reader} may see it: every similar Incident (and its evidence item) the reader cannot see
     * is dropped — a stored Brief was filtered for the person who started it, not for whoever reads it later.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> forReader(Map<String, Object> brief, Predicate<String> visible) {
        Map<String, Object> out = new LinkedHashMap<>(brief);
        if (brief.get("similar") instanceof List<?> sim)
            out.put("similar", sim.stream().filter(s -> s instanceof Map<?, ?> m && visible.test(String.valueOf(m.get("id"))))
                    .toList());
        if (brief.get("evidence") instanceof List<?> ev)
            out.put("evidence", ev.stream().filter(e -> !(e instanceof Map<?, ?> m) || !"similar-incident".equals(m.get("kind"))
                    || visible.test(String.valueOf(m.get("citation")).replaceFirst("^GET /objects/", ""))).toList());
        return out;
    }

    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
            if (t.length() >= MIN_TOKEN) out.add(t);
        return out;
    }

    // ── the store ───────────────────────────────────────────────────────────────────────────────

    private static Path file(Path root, String id) {
        if (id == null || !SAFE_ID.matcher(id).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "incident brief id must match " + SAFE_ID.pattern());
        Path f = root.toAbsolutePath().normalize().resolve(DIR).resolve(id + ".json");
        if (!PathJail.contains(root.toAbsolutePath().normalize(), f))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "incident brief path escapes the config root");
        return f;
    }

    static void save(Path root, Map<String, Object> rec) throws IOException {
        Path f = file(root, String.valueOf(rec.get("id")));
        Files.createDirectories(f.getParent());
        AtomicFiles.write(f, ApiContext.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(rec), ".ib-");
    }

    /** Every Brief of {@code incidentId}, newest first. */
    static List<Map<String, Object>> list(Path root, String incidentId) throws IOException {
        Path d = root.toAbsolutePath().normalize().resolve(DIR);
        if (!Files.isDirectory(d)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (!SAFE_ID.matcher(id).matches()) continue;
                Map<String, Object> rec = ApiContext.JSON.readValue(Files.readAllBytes(file(root, id)), MAP);
                if (RECORD_TYPE.equals(rec.get("recordType")) && incidentId.equals(rec.get("incidentId"))) out.add(rec);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("id"))).reversed());
        return out;
    }
}
