package com.gamma.geolink;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.gamma.geolink.InvestigationEvaluator.canonical;
import static com.gamma.geolink.InvestigationEvaluator.sha256;
import static com.gamma.geolink.InvestigationEvaluator.strings;

/**
 * The <b>Dossier</b> of one Investigation (LA-12, {@code docs/superpower/link-analysis-backlog-plan.md} §2.7, §3.4):
 * the evidential narrative, built mechanically from the sealed op log so no authored prose can drift from what the
 * analyst did.
 *
 * <p>Sections: {@code summary}, {@code topology}, {@code scores} (centrality/risk tables), a chronological
 * {@code ledger}, {@code negativeSpace}, {@code integrity}, the three {@code renderings} — the re-runnable JSON log
 * (analyst), the numbered plain-language steps (reviewer) and the method statement (authority) — and a SHA-256
 * {@code manifest}.
 *
 * <p><b>The manifest is the chain of custody.</b> It hashes the raw stored BYTES of every artefact the dossier
 * draws on — {@code header.json}, each log line ({@code log.jsonl#<step>}), each {@code sets/<step>.json} and each
 * included snapshot — plus the canonical content the dossier exposes ({@code entities}, {@code links},
 * {@code excluded}, {@code scores}: G-R6's "nodes, edges and score vectors"). Its {@code root} is the SHA-256 of the
 * canonical manifest body. Rebuilding the manifest later from the store and comparing it entry by entry
 * ({@link #verify}) names every artefact that changed since the dossier was issued.
 *
 * <p><b>Integrity is checked independently of any earlier manifest</b>, from the hashes LA-10 recorded at append
 * time: each step's replayed Working Set hash must equal the {@code workingSetHash} its log line recorded, the
 * {@code hash} its set file recorded, and the SHA-256 of the set file's own content; every sealed read's rows must
 * still hash to its {@code fingerprint}. So a tampered stored step is caught on the FIRST dossier too — the
 * external manifest additionally catches a tamper that re-computed every internal hash consistently.
 *
 * <p>⚠ Positions are evaluated as the PREFIX they were at append time (an undo later in the log does not rewrite an
 * earlier position), which is exactly what the recorded {@code workingSetHash} describes.
 *
 * <p>Pure and framework-free: no I/O, no HTTP. {@link DossierRoutes} reads the store and applies access.
 */
final class GraphDossierBuilder {

    private GraphDossierBuilder() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int TABLE_ROWS = 25;
    static final int ENTITY_LIST_CAP = 5_000;

    /** One included evidence snapshot: its id and the raw bytes it is stored as. */
    record Snapshot(String id, String raw) {}

    /** Everything the builder reads, already fetched from the store. Set files absent from disk map to null. */
    record Input(String id, String headerRaw, List<String> logLines, Map<Integer, String> setFiles,
                 List<Snapshot> snapshots, int at) {}

    // ── build ──────────────────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> build(Input in) throws IOException {
        Map<String, Object> header = parse(in.headerRaw());
        List<Map<String, Object>> log = new ArrayList<>();
        for (String line : in.logLines().subList(0, in.at())) log.add(parse(line));

        // Positions as they were at append time, and the integrity checks against every recorded hash.
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Integer> entityCounts = new ArrayList<>();
        Map<Integer, List<String>> removedBy = new TreeMap<>();   // what each excludeBy removed (LA-17), for G-E10
        InvestigationEvaluator.State state = new InvestigationEvaluator.State();
        for (int i = 0; i < log.size(); i++) {
            Map<String, Object> e = log.get(i);
            int step = i + 1;
            List<String> had = new ArrayList<>(state.entities.keySet());
            if ("undo".equals(e.get("kind"))) state = InvestigationEvaluator.evaluate(log.subList(0, step), -1, null);
            else InvestigationEvaluator.apply(state, e);
            if ("excludeBy".equals(e.get("op"))) {
                List<String> gone = new ArrayList<>();
                for (String id : had) if (!state.entities.containsKey(id)) gone.add(id);
                removedBy.put(step, gone);
            }
            entityCounts.add(state.entities.size());
            String now = state.hash();
            if (!now.equals(e.get("workingSetHash")))
                failures.add(failure(step, "log.jsonl#" + step, "replayed Working Set hash " + now
                        + " differs from the recorded " + e.get("workingSetHash")));
            String set = in.setFiles().get(step);
            if (set == null) {
                failures.add(failure(step, "sets/" + step + ".json", "the sealed Working Set file is missing"));
            } else {
                Map<String, Object> doc = parse(set);
                if (!now.equals(doc.get("hash")))
                    failures.add(failure(step, "sets/" + step + ".json", "recorded hash " + doc.get("hash")
                            + " differs from the replayed " + now));
                String content = sha256(canonical(doc.get("workingSet")));
                if (!content.equals(doc.get("hash")))
                    failures.add(failure(step, "sets/" + step + ".json", "content hashes to " + content
                            + ", not the recorded " + doc.get("hash")));
            }
            if (e.get("read") instanceof Map<?, ?> r) {
                // A seedBy (LA-17) seals the ids it matched, where an expand seals its rows.
                String rows = sha256(canonical("seedBy".equals(e.get("op")) ? r.get("ids") : r.get("rows")));
                if (!rows.equals(r.get("fingerprint")))
                    failures.add(failure(step, "log.jsonl#" + step, "sealed rows hash to " + rows
                            + ", not the recorded fingerprint " + r.get("fingerprint")));
            }
        }
        Map<String, Object> ws = state.toMap();

        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (Snapshot s : in.snapshots()) snapshots.add(parse(s.raw()));

        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("summary", summary(in, header, state));
        dossier.put("topology", topology(state));
        dossier.put("scores", scores(in.snapshots(), snapshots));
        List<Map<String, Object>> ledger = ledger(log, entityCounts, removedBy);
        dossier.put("ledger", ledger);
        Map<String, Object> negative = negativeSpace(log, state, in.snapshots(), snapshots, removedBy);
        dossier.put("negativeSpace", negative);
        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("intact", failures.isEmpty());
        integrity.put("stepsChecked", log.size());
        integrity.put("failures", failures);
        dossier.put("integrity", integrity);
        Map<String, Object> manifest = manifest(in, ws, snapshots);
        dossier.put("manifest", manifest);

        Map<String, Object> renderings = new LinkedHashMap<>();
        renderings.put("json", jsonRendering(header, log, negative));
        renderings.put("steps", stepsRendering(ledger, negative));
        renderings.put("method", methodStatement(in, header, state, log, negative, integrity, manifest, removedBy));
        dossier.put("renderings", renderings);

        List<Map<String, Object>> entities = castList(ws.get("entities"));
        dossier.put("workingSet", Map.of(
                "entities", entities.subList(0, Math.min(ENTITY_LIST_CAP, entities.size())),
                "entitiesTotal", entities.size(),
                "entitiesTruncated", entities.size() > ENTITY_LIST_CAP,
                "hash", state.hash()));
        return dossier;
    }

    // ── manifest (chain of custody) ────────────────────────────────────────────────────────────────────

    /**
     * The SHA-256 manifest: every stored artefact the dossier draws on, by its raw bytes, plus the canonical
     * content it exposes, sealed by a {@code root} over the whole body.
     */
    static Map<String, Object> manifest(Input in, Map<String, Object> ws, List<Map<String, Object>> snapshots) {
        List<Map<String, Object>> artefacts = new ArrayList<>();
        artefacts.add(artefact("header.json", in.headerRaw()));
        for (int step = 1; step <= in.at(); step++) {
            artefacts.add(artefact("log.jsonl#" + step, in.logLines().get(step - 1)));
            String set = in.setFiles().get(step);
            if (set != null) artefacts.add(artefact("sets/" + step + ".json", set));
        }
        for (Snapshot s : in.snapshots()) artefacts.add(artefact("snapshots/" + s.id() + ".json", s.raw()));

        Map<String, Object> scores = new LinkedHashMap<>();
        for (int i = 0; i < snapshots.size(); i++)
            scores.put(in.snapshots().get(i).id(), snapshots.get(i).get("metrics"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("entities", sha256(canonical(ws.get("entities"))));
        content.put("links", sha256(canonical(ws.get("links"))));
        content.put("excluded", sha256(canonical(ws.get("excluded"))));
        content.put("scores", sha256(canonical(scores)));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm", "SHA-256");
        m.put("investigation", in.id());
        m.put("at", in.at());
        m.put("snapshots", in.snapshots().stream().map(Snapshot::id).toList());
        m.put("artefacts", artefacts);
        m.put("content", content);
        m.put("root", root(m));
        return m;
    }

    /** SHA-256 over the canonical manifest body — every field except {@code root} itself. */
    static String root(Map<String, Object> manifest) {
        Map<String, Object> body = new TreeMap<>(manifest);
        body.remove("root");
        return sha256(canonical(body));
    }

    /**
     * Compare a manifest a reader holds against one rebuilt from the store now. {@code verified} only when the
     * submitted manifest is self-consistent (its root matches its body — an edited manifest is caught), every
     * artefact and content hash still matches, and the store's own recorded hashes still agree ({@code intact}).
     */
    static Map<String, Object> verify(Map<String, Object> submitted, Map<String, Object> current, boolean intact) {
        boolean selfConsistent = String.valueOf(submitted.get("root")).equals(root(submitted));
        Map<String, String> was = artefactHashes(submitted), now = artefactHashes(current);
        List<String> changed = new ArrayList<>(), missing = new ArrayList<>(), added = new ArrayList<>();
        for (var e : was.entrySet()) {
            String n = now.get(e.getKey());
            if (n == null) missing.add(e.getKey());
            else if (!n.equals(e.getValue())) changed.add(e.getKey());
        }
        for (String k : now.keySet()) if (!was.containsKey(k)) added.add(k);
        List<String> contentChanged = new ArrayList<>();
        Map<String, Object> wc = submitted.get("content") instanceof Map<?, ?> m ? castMap(m) : Map.of();
        Map<String, Object> nc = castMap((Map<?, ?>) current.get("content"));
        for (var e : nc.entrySet()) if (!e.getValue().equals(wc.get(e.getKey()))) contentChanged.add(e.getKey());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", selfConsistent && intact && changed.isEmpty() && missing.isEmpty() && added.isEmpty()
                && contentChanged.isEmpty() && String.valueOf(submitted.get("root")).equals(current.get("root")));
        out.put("selfConsistent", selfConsistent);
        out.put("intact", intact);
        out.put("submittedRoot", submitted.get("root"));
        out.put("currentRoot", current.get("root"));
        out.put("changed", changed);
        out.put("missing", missing);
        out.put("added", added);
        out.put("contentChanged", contentChanged);
        return out;
    }

    private static Map<String, String> artefactHashes(Map<String, Object> manifest) {
        Map<String, String> out = new LinkedHashMap<>();
        if (manifest.get("artefacts") instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> a) out.put(String.valueOf(a.get("path")), String.valueOf(a.get("sha256")));
        return out;
    }

    private static Map<String, Object> artefact(String path, String raw) {
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("path", path);
        a.put("bytes", bytes.length);
        a.put("sha256", sha256(raw));
        return a;
    }

    // ── sections ───────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> summary(Input in, Map<String, Object> header, InvestigationEvaluator.State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("investigation", in.id());
        m.put("title", header.get("title"));
        m.put("purpose", header.get("purpose"));   // D-U5: the stated purpose / legal basis — recorded, not enforced
        m.put("owner", header.get("owner"));
        m.put("createdAt", header.get("createdAt"));
        m.put("dataset", header.get("dataset"));
        m.put("mapping", mapping(header));
        m.put("datasetVersion", header.get("datasetVersion"));
        m.put("parent", header.get("parent"));
        m.put("at", in.at());
        m.put("steps", in.logLines().size());
        m.put("entities", s.entities.size());
        m.put("links", s.links.size());
        m.put("excluded", s.excluded.size());
        m.put("hidden", s.hidden.size());
        m.put("kept", s.kept.size());
        m.put("snapshots", in.snapshots().stream().map(Snapshot::id).toList());
        return m;
    }

    private static Map<String, Object> mapping(Map<String, Object> header) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceCol", header.get("sourceCol"));
        m.put("targetCol", header.get("targetCol"));
        m.put("linkKindCol", header.get("linkKindCol"));
        return m;
    }

    /**
     * Counts over the final Working Set, plus degree taken straight from the sealed links. ⚠ Degree is a count of
     * recorded links, not a graph algorithm; the centrality measures live in {@code scores}.
     */
    private static Map<String, Object> topology(InvestigationEvaluator.State s) {
        TreeMap<Integer, Integer> perHop = new TreeMap<>();
        TreeMap<String, Integer> perSeed = new TreeMap<>();
        for (InvestigationEvaluator.Entity e : s.entities.values()) {
            perHop.merge(e.hop(), 1, Integer::sum);
            perSeed.merge(e.seed(), 1, Integer::sum);
        }
        TreeMap<String, long[]> byKind = new TreeMap<>();
        Map<String, long[]> degree = new HashMap<>();
        for (InvestigationEvaluator.Link l : s.links.values()) {
            long[] k = byKind.computeIfAbsent(String.valueOf(l.kind()), x -> new long[2]);
            k[0]++;
            k[1] += l.count();
            for (String end : List.of(l.source(), l.target())) {
                long[] d = degree.computeIfAbsent(end, x -> new long[2]);
                d[0]++;
                d[1] += l.count();
            }
        }
        List<Map<String, Object>> kinds = new ArrayList<>();
        byKind.forEach((k, v) -> kinds.add(Map.of("kind", k, "links", v[0], "events", v[1])));
        List<Map<String, Object>> top = new ArrayList<>();
        degree.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> x) -> -x.getValue()[0])
                        .thenComparingLong(x -> -x.getValue()[1]).thenComparing(Map.Entry::getKey))
                .limit(TABLE_ROWS)
                .forEach(x -> top.add(Map.of("id", x.getKey(), "degree", x.getValue()[0], "events", x.getValue()[1])));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entities", s.entities.size());
        m.put("links", s.links.size());
        m.put("perHop", perHop);
        m.put("perSeed", perSeed);
        m.put("linksByKind", kinds);
        m.put("degree", top);
        m.put("degreeTotal", degree.size());
        return m;
    }

    /**
     * Centrality / risk tables. No server-side graph algorithm exists, so the dossier computes none: it reports the
     * score vectors an included snapshot SEALED (computed client-side over that snapshot's graph), each table
     * labelled with the snapshot, its time and its node count — which measure, over which set, when.
     */
    private static Map<String, Object> scores(List<Snapshot> ids, List<Map<String, Object>> snaps) {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            Map<String, Object> snap = snaps.get(i);
            if (!(snap.get("metrics") instanceof Map<?, ?> metrics)) continue;
            for (var metric : new TreeMap<>(castMap(metrics)).entrySet()) {
                if (!(metric.getValue() instanceof Map<?, ?> vector)) continue;
                List<Map<String, Object>> rows = new ArrayList<>();
                castMap(vector).entrySet().stream()
                        .filter(v -> v.getValue() instanceof Number)
                        .sorted(Comparator.comparingDouble((Map.Entry<String, Object> v) ->
                                -((Number) v.getValue()).doubleValue()).thenComparing(Map.Entry::getKey))
                        .limit(TABLE_ROWS)
                        .forEach(v -> rows.add(Map.of("id", v.getKey(), "value", v.getValue())));
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("metric", metric.getKey());
                t.put("snapshot", ids.get(i).id());
                t.put("computedAt", snap.get("createdAt"));
                t.put("computedOver", sizeOf(snap.get("nodes")) + " nodes");
                t.put("rows", rows);
                t.put("total", castMap(vector).size());
                tables.add(t);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("computedBy", "client-side (the SPA's graph algorithms); sealed into a snapshot, not recomputed here");
        m.put("tables", tables);
        if (tables.isEmpty())
            m.put("note", "no score vectors in the evidence — include a snapshot anchored to this Investigation to "
                    + "carry the centrality/risk scores the analyst computed");
        return m;
    }

    private static List<Map<String, Object>> ledger(List<Map<String, Object>> log, List<Integer> entityCounts,
                                                    Map<Integer, List<String>> removedBy) {
        Map<Integer, Integer> undoneBy = undoneBy(log);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            Map<String, Object> e = log.get(i);
            int step = i + 1;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", step);
            row.put("at", e.get("at"));
            row.put("author", e.get("author"));
            row.put("kind", e.get("kind"));
            row.put("op", "undo".equals(e.get("kind")) ? "undo" : e.get("op"));
            row.put("text", render(e, removedBy.get(step)));
            row.put("undoneBy", undoneBy.get(step));
            row.put("entitiesAfter", entityCounts.get(i));
            row.put("workingSetHash", e.get("workingSetHash"));
            if (e.get("read") instanceof Map<?, ?> r) {
                row.put("truncated", Boolean.TRUE.equals(r.get("truncated")));
                row.put("readFingerprint", r.get("fingerprint"));
            }
            if (e.get("derivedFrom") != null) row.put("derivedFrom", e.get("derivedFrom"));
            out.add(row);
        }
        return out;
    }

    /**
     * What the result does NOT show (plan §2.7, gate G-E10): every exclusion with its op, author and reason and
     * whether it is a stated rule or an analyst judgement; undone steps; truncated reads; hidden entities; the
     * unassessed coverage; which measures exist over which set; and the unpinned Dataset version.
     */
    private static Map<String, Object> negativeSpace(List<Map<String, Object>> log, InvestigationEvaluator.State s,
                                                     List<Snapshot> ids, List<Map<String, Object>> snaps,
                                                     Map<Integer, List<String>> removedBy) {
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (var x : s.excluded.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.getKey());
            m.put("step", x.getValue().step());
            m.put("reason", x.getValue().reason());
            Map<String, Object> by = log.get(x.getValue().step() - 1);
            m.put("author", by.get("author"));
            // An `exclude` is an analyst's judgement; an `excludeBy` (LA-17) applies a stated rule — a named list.
            m.put("basis", "excludeBy".equals(by.get("op"))
                    ? "stated rule (" + InvestigationRoutes.listClause(by) + ")"
                    : "analyst judgement (extensional exclude)");
            excluded.add(m);
        }
        Map<Integer, Integer> undoneBy = undoneBy(log);
        List<Map<String, Object>> undone = new ArrayList<>();
        undoneBy.forEach((step, by) -> undone.add(Map.of("step", step, "undoneBy", by,
                "text", render(log.get(step - 1), removedBy.get(step)))));
        List<Map<String, Object>> truncated = new ArrayList<>();
        for (Map<String, Object> e : log)
            if (e.get("read") instanceof Map<?, ?> r && Boolean.TRUE.equals(r.get("truncated"))) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("step", e.get("step"));
                t.put("rowCount", r.get("rowCount"));
                t.put("budget", castMap((Map<?, ?>) e.get("params")).get("budget"));
                truncated.add(t);
            }
        List<Map<String, Object>> measures = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            Map<String, Object> snap = snaps.get(i);
            List<String> names = snap.get("metrics") instanceof Map<?, ?> mm
                    ? new ArrayList<>(new TreeMap<>(castMap(mm)).keySet()) : List.of();
            measures.add(Map.of("snapshot", ids.get(i).id(), "metrics", names,
                    "computedAt", String.valueOf(snap.get("createdAt")), "nodes", sizeOf(snap.get("nodes"))));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("excluded", excluded);
        m.put("excludedCount", excluded.size());
        m.put("undone", undone);
        m.put("truncated", truncated);
        m.put("hidden", new ArrayList<>(s.hidden));
        m.put("coverage", Map.of("assessed", false, "note", "the coverage indicator (LA-19) is not built: which days "
                + "and Collectors are missing for the read windows is NOT known, and a gap in the data is "
                + "indistinguishable from innocence"));
        m.put("measures", measures);
        m.put("datasetVersion", Map.of("pinned", false, "note", "no version-addressable read exists (D-E3): each read "
                + "is sealed at use and its time recorded as weak provenance, not a replay pin"));
        return m;
    }

    // ── the three renderings ───────────────────────────────────────────────────────────────────────────

    /** The analyst's rendering: the log as data, re-runnable, with the sealed rows summarised by fingerprint. */
    private static Map<String, Object> jsonRendering(Map<String, Object> header, List<Map<String, Object>> log,
                                                     Map<String, Object> negative) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> e : log) {
            Map<String, Object> out = new LinkedHashMap<>(e);
            if (e.get("read") instanceof Map<?, ?> r) {
                Map<String, Object> read = new LinkedHashMap<>();
                for (String k : List.of("dataset", "readAt", "query", "rowCount", "truncated", "fingerprint"))
                    read.put(k, r.get(k));
                out.put("read", read);
            }
            if (e.get("list") instanceof Map<?, ?> l) {   // LA-17: members counted — the log.jsonl artefact hashes them
                Map<String, Object> list = new LinkedHashMap<>();
                for (var x : l.entrySet()) if (!"members".equals(x.getKey())) list.put(String.valueOf(x.getKey()), x.getValue());
                list.put("size", strings(l.get("members")).size());
                out.put("list", list);
            }
            entries.add(out);
        }
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (String k : List.of("id", "purpose", "dataset", "sourceCol", "targetCol", "linkKindCol", "timeCol",
                "timeColZone", "datasetVersion", "parent"))
            bindings.put(k, header.get(k));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("investigation", bindings);
        m.put("log", entries);
        m.put("negativeSpace", negative);
        return m;
    }

    /** The reviewer's rendering: one numbered line per step — exclusions in full — then the negative space. */
    private static List<String> stepsRendering(List<Map<String, Object>> ledger, Map<String, Object> negative) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : ledger)
            out.add(row.get("step") + ". [" + row.get("author") + ", " + row.get("at") + "] " + row.get("text")
                    + (row.get("undoneBy") != null ? " (undone by step " + row.get("undoneBy") + ")" : ""));
        out.add("Not shown in the result:");
        out.addAll(negativeLines(negative));
        return out;
    }

    /** The authority's rendering: a method statement assembled line by line from the log, closed by the custody hash. */
    private static String methodStatement(Input in, Map<String, Object> header, InvestigationEvaluator.State s,
                                          List<Map<String, Object>> log, Map<String, Object> negative,
                                          Map<String, Object> integrity, Map<String, Object> manifest,
                                          Map<Integer, List<String>> removedBy) {
        StringBuilder b = new StringBuilder();
        b.append("Method statement — Investigation ").append(in.id());
        if (header.get("title") != null) b.append(" (").append(header.get("title")).append(")");
        b.append("\n\nScope. ");
        if (header.get("purpose") != null)   // D-U5 — stated at create; an Investigation before D-U5 has none
            b.append("Stated purpose (legal basis, recorded and not enforced): ").append(header.get("purpose")).append(". ");
        else b.append("No purpose was recorded (the Investigation predates the purpose requirement). ");
        b.append("Owner ").append(header.get("owner")).append(", created ").append(header.get("createdAt"))
         .append(". Dataset ").append(header.get("dataset")).append(", links read from ").append(header.get("sourceCol"))
         .append(" to ").append(header.get("targetCol"));
        if (header.get("linkKindCol") != null) b.append(", kind from ").append(header.get("linkKindCol"));
        b.append(".");
        if (header.get("timeCol") != null)   // LA-13: the timezone contract, stated where an authority reads it
            b.append(" Event time from ").append(header.get("timeCol")).append(header.get("timeColZone") == null
                    ? ", an instant (timestamp with time zone)"
                    : ", a wall clock read as " + header.get("timeColZone") + " time")
             .append("; time ranges are absolute instants, and intraday slots and day masks are read on the wall "
                     + "clock of the time zone each window names.");
        if (header.get("parent") instanceof Map<?, ?> p)
            b.append(" Forked from Investigation ").append(p.get("id")).append(" in the order ").append(p.get("order"))
             .append(".");
        b.append("\n\nMethod. ").append(log.size()).append(" step(s) from the closed op vocabulary, in order:\n");
        Map<Integer, Integer> undoneBy = undoneBy(log);
        for (int i = 0; i < log.size(); i++) {
            Map<String, Object> e = log.get(i);
            b.append("  ").append(i + 1).append(". ").append(render(e, removedBy.get(i + 1)));
            if (undoneBy.containsKey(i + 1)) b.append(" (undone by step ").append(undoneBy.get(i + 1)).append(")");
            b.append(" — ").append(e.get("author")).append(", ").append(e.get("at")).append("\n");
        }
        b.append("\nResult. ").append(s.entities.size()).append(" entities and ").append(s.links.size())
         .append(" links at step ").append(in.at()).append(" (Working Set ").append(s.hash()).append(").\n");
        b.append("\nWhat the result does not show.\n");
        for (String line : negativeLines(negative)) b.append("  ").append(line).append("\n");
        b.append("\nIntegrity. ");
        if (Boolean.TRUE.equals(integrity.get("intact")))
            b.append("Every step's recorded hash, sealed Working Set and sealed read agree with a replay of the log.");
        else {
            b.append("INTEGRITY FAILURE — the stored evidence disagrees with its own recorded hashes:");
            for (Map<String, Object> f : castList(integrity.get("failures")))
                b.append("\n  step ").append(f.get("step")).append(" ").append(f.get("artefact")).append(": ")
                 .append(f.get("detail"));
        }
        b.append("\n\nChain of custody. SHA-256 manifest over ").append(castList(manifest.get("artefacts")).size())
         .append(" stored artefacts and the entity, link, exclusion and score content; root ")
         .append(manifest.get("root")).append(".\n");
        return b.toString();
    }

    /** The negative space as plain lines — shared by the steps rendering and the method statement. */
    private static List<String> negativeLines(Map<String, Object> negative) {
        List<String> out = new ArrayList<>();
        List<Map<String, Object>> excluded = castList(negative.get("excluded"));
        if (excluded.isEmpty()) out.add("- Excluded: none.");
        else {
            out.add("- Excluded: " + excluded.size() + " entit" + (excluded.size() == 1 ? "y" : "ies") + ", gone from "
                    + "display, traversal and counts:");
            for (Map<String, Object> x : excluded)
                out.add("    " + x.get("id") + " — step " + x.get("step") + ", reason: " + x.get("reason") + ", by "
                        + x.get("author") + ", " + x.get("basis"));
        }
        for (Map<String, Object> u : castList(negative.get("undone")))
            out.add("- Undone: step " + u.get("step") + " (" + u.get("text") + ") by step " + u.get("undoneBy") + ".");
        List<Map<String, Object>> truncated = castList(negative.get("truncated"));
        if (truncated.isEmpty()) out.add("- Truncated reads: none.");
        for (Map<String, Object> t : truncated)
            out.add("- TRUNCATED: step " + t.get("step") + " read stopped at its budget of " + t.get("budget")
                    + " link rows; entities beyond it were never considered.");
        List<String> hidden = strings(negative.get("hidden"));
        if (!hidden.isEmpty())
            out.add("- Hidden from display (still traversed and counted): " + String.join(", ", hidden) + ".");
        out.add("- Coverage: NOT assessed — " + castMap((Map<?, ?>) negative.get("coverage")).get("note") + ".");
        List<Map<String, Object>> measures = castList(negative.get("measures"));
        if (measures.isEmpty())
            out.add("- Measures: no centrality or risk score is part of this evidence; any the analyst viewed were "
                    + "computed client-side and not sealed.");
        for (Map<String, Object> m : measures)
            out.add("- Measures " + m.get("metrics") + " were computed client-side over snapshot " + m.get("snapshot")
                    + " (" + m.get("nodes") + " nodes) at " + m.get("computedAt") + ".");
        out.add("- Dataset version: not pinned — "
                + castMap((Map<?, ?>) negative.get("datasetVersion")).get("note") + ".");
        return out;
    }

    // ── plain-language step text ───────────────────────────────────────────────────────────────────────

    /**
     * One step as a plain-language line. ⚠ Mirrors {@code InvestigationRoutes.render} with ONE deliberate
     * difference: an exclusion lists EVERY id (G-E10 — a narrative cannot omit an excluded entity), never
     * "and N more". Duplicated rather than shared to leave that class untouched under a parallel lane.
     * {@code removed} is what an {@code excludeBy} removed (LA-17) — the state's, not the log line's — else null.
     */
    static String render(Map<String, Object> e, List<String> removed) {
        if ("undo".equals(e.get("kind"))) return "Undid step " + e.get("undoes") + ".";
        Map<String, Object> p = e.get("params") instanceof Map<?, ?> m ? castMap(m) : Map.of();
        List<String> ids = strings(p.get("ids"));
        return switch (String.valueOf(e.get("op"))) {
            case "seed" -> "Seeded " + ids.size() + " entit" + (ids.size() == 1 ? "y" : "ies")
                    + (p.get("entityType") != null ? " of type " + p.get("entityType") : "") + ": " + head(ids) + ".";
            case "expand" -> {
                Map<String, Object> r = castMap((Map<?, ?>) e.get("read"));
                int frontier = strings(castMap((Map<?, ?>) r.get("query")).get("frontier")).size();
                Map<String, Object> q = castMap((Map<?, ?>) r.get("query"));
                yield "Expanded one hop from " + frontier + " entit" + (frontier == 1 ? "y" : "ies") + " over "
                        + r.get("dataset") + InvestigationTime.rungClause(q) + " — " + r.get("rowCount")
                        + " link rows read at " + r.get("readAt")
                        + (r.get("fanOutCapped") instanceof Number c && c.longValue() > 0
                                ? ", " + c + " more left out by the fan-out cap" : "")
                        + (Boolean.TRUE.equals(r.get("truncated")) ? ", TRUNCATED at its budget of " + q.get("budget") : "")
                        + "." + InvestigationRoutes.approvalClause(e);
            }
            case "window" -> p.get("window") == null
                    ? "Cleared the time window: later expansions read the full time range."
                    : "Set the time window to " + InvestigationTime.describe(castMap((Map<?, ?>) p.get("window")))
                            + "; later expansions read inside it (earlier steps are unchanged).";
            case "exclude" -> "Excluded " + ids.size() + " entit" + (ids.size() == 1 ? "y" : "ies")
                    + " (reason: " + p.get("reason") + "): " + String.join(", ", ids) + ".";
            case "hide" -> "Hid " + head(ids) + " from display (still traversed and counted).";
            case "keep" -> "Kept " + head(ids) + " (protected from later exclusion).";
            case "annotate" -> "Annotated " + String.join(", ", ids) + InvestigationRoutes.gradeClause(p)
                    + ": \"" + p.get("note") + "\"";
            case "excludeBy" -> {
                List<String> gone = removed == null ? List.of() : removed;
                yield "Excluded " + gone.size() + " entit" + (gone.size() == 1 ? "y" : "ies") + " on "
                        + InvestigationRoutes.listClause(e) + " (reason: " + p.get("reason") + ")"
                        + (gone.isEmpty() ? "." : ": " + String.join(", ", gone) + ".");
            }
            case "seedBy" -> {
                Map<String, Object> r = castMap((Map<?, ?>) e.get("read"));
                List<String> seeded = strings(r.get("ids"));
                yield "Seeded " + seeded.size() + " entit" + (seeded.size() == 1 ? "y" : "ies") + " of type "
                        + castMap((Map<?, ?>) e.get("list")).get("entityType") + " from " + InvestigationRoutes.listClause(e)
                        + " — the values of " + r.get("dataset") + " read at " + r.get("readAt") + " whose key is a member"
                        + (seeded.isEmpty() ? "." : ": " + head(seeded) + ".");
            }
            default -> "Applied " + e.get("op") + ".";
        };
    }

    private static String head(List<String> ids) {
        int shown = Math.min(10, ids.size());
        String head = String.join(", ", ids.subList(0, shown));
        return ids.size() > shown ? head + " and " + (ids.size() - shown) + " more" : head;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static Map<Integer, Integer> undoneBy(List<Map<String, Object>> log) {
        Map<Integer, Integer> out = new TreeMap<>();
        for (Map<String, Object> e : log)
            if ("undo".equals(e.get("kind")) && e.get("undoes") instanceof Number n)
                out.put(n.intValue(), ((Number) e.get("step")).intValue());
        return out;
    }

    private static Map<String, Object> failure(int step, String artefact, String detail) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("step", step);
        f.put("artefact", artefact);
        f.put("detail", detail);
        return f;
    }

    private static int sizeOf(Object o) {
        return o instanceof List<?> l ? l.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String raw) throws IOException {
        return JSON.readValue(raw, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return m == null ? Map.of() : (Map<String, Object>) m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
