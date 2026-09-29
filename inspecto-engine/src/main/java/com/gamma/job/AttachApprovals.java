package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The approval of a report Job's ATTACHMENTS, pinned to CONTENT (ASSURE-XLSX-ATTACHMENTS-1 rounds 3–4; the same
 * shape the {@code publish.postgres} lane's {@code PublicationApproval} uses — one P2 row merges the two).
 *
 * <ul>
 *   <li><b>What is hashed</b> — {@link #fingerprint}: ONLY what decides what is sent and to whom — the Job's
 *       {@link #SENSITIVE} keys, template-EXPANDED, plus for its Dataset the definition AND the resolved relation
 *       SQL ({@link DatasetRelation#relationSql}, so a view's SQL is inside). Schedule and {@code enabled} are not.</li>
 *   <li><b>When it is fixed</b> — at HOLD time, from the PROPOSED content; stored on the Pending Change. Approve
 *       refuses (409) unless the live content still hashes to it, and then records THAT fingerprint, bound to the
 *       Pending Change id and a nonce ({@link #record}). Nothing written between proposal and replay is blessed.</li>
 *   <li><b>When it is required</b> — every write whose fingerprint is not the approved one is held, whatever the
 *       policy; so re-saving the Job is the re-approve path, and a cron-only edit never touches the approval.</li>
 *   <li><b>At run time</b> — {@code ReportJob} sends only when the running Job hashes to the approved fingerprint.</li>
 *   <li><b>Delete</b> revokes ({@link #revoke}): a re-created identical Job needs a new approval.</li>
 * </ul>
 * Stored as {@value #FILE} in the Space config root, reserved from every import.
 */
public final class AttachApprovals {

    private AttachApprovals() {}

    public static final String FILE = "attach-approvals.json";
    /** The Job keys that decide what data leaves and to whom. */
    public static final List<String> SENSITIVE = List.of("type", "attach", "recipients", "dataset", "scope",
            "measures", "group_by", "format", "out_dir", "limit", "connection", "use");
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object LOCK = new Object();

    /** THE predicate for "this Job attaches data", shared by the write-time guards and {@code ReportJob}. */
    public static boolean attaches(Map<?, ?> job) {
        if (job == null) return false;
        Map<?, ?> j = section(job);
        return "report".equalsIgnoreCase(str(j.get("type"))) && "true".equalsIgnoreCase(str(j.get("attach")));
    }

    /** {@link #attaches(Map)} over a loaded Job. */
    public static boolean attaches(JobConfig cfg) {
        return attaches(asSection(cfg));
    }

    /** A loaded Job as the flat section shape the fingerprint reads. */
    public static Map<String, Object> asSection(JobConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>(cfg.params());
        m.put("name", cfg.name());
        m.put("type", cfg.type());
        return m;
    }

    /** A Job section with its {@code template:} expanded against {@code templates} (by name); unchanged without one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> expand(Map<?, ?> job, Map<String, JobTemplate> templates) {
        Map<String, Object> j = new LinkedHashMap<>((Map<String, Object>) section(job));
        Object ref = j.get("template");
        if (ref == null) return j;
        JobTemplate t = templates.get(String.valueOf(ref).trim());
        if (t == null) return j;
        try {
            return t.instantiate(j);
        } catch (IllegalArgumentException unresolvable) {
            return j;   // the loader refuses it; nothing loads, nothing runs
        }
    }

    /** Every {@code *_job_template.toon} under {@code root} (depth 4), by name. */
    public static Map<String, JobTemplate> templates(Path root) {
        Map<String, JobTemplate> out = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) return out;
        try (Stream<Path> files = Files.walk(root, 4)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_job_template.toon")).toList()) {
                try {
                    JobTemplate t = JobTemplate.load(p.toString());
                    out.putIfAbsent(t.name(), t);
                } catch (Exception bad) {
                    // the loader skips a bad template too
                }
            }
        } catch (IOException unreadable) {
            // no templates readable ⇒ none expand
        }
        return out;
    }

    /** The fingerprint of an (expanded) Job section, against the Space whose config root is {@code root} now. */
    public static String fingerprint(Map<?, ?> job, Path root) {
        Map<?, ?> j = section(job);
        Map<String, Object> canon = new TreeMap<>();
        canon.put("name", str(j.get("name")));
        Map<String, Object> keys = new TreeMap<>();
        for (String k : SENSITIVE) keys.put(k, str(j.get(k)));
        canon.put("job", keys);
        String dataset = str(j.get("dataset"));
        if (dataset != null && !dataset.isEmpty() && root != null) {
            Map<String, Object> ds = new ComponentStore(root.resolve("registry")).get("dataset", dataset)
                    .map(ComponentRegistry.Component::content).orElse(null);
            canon.put("datasetDefinition", ds);
            String sql;
            try {
                sql = ds == null ? null : DatasetRelation.relationSql(ds, null, new ViewStore(root.resolve("views")));
            } catch (RuntimeException unresolvable) {
                sql = "unresolvable: " + unresolvable.getMessage();
            }
            canon.put("datasetSql", sql);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(canon).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** {@link #fingerprint(Map, Path)} of a loaded (already expanded) Job. */
    public static String fingerprint(JobConfig cfg, Path root) {
        return fingerprint(asSection(cfg), root);
    }

    /** Record {@code fingerprint} as Job {@code name}'s approved attachment version, bound to its Pending Change. */
    public static void record(Path root, String name, String fingerprint, String pendingChangeId, String approvedBy,
                              String nonce) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> all = read(root);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fingerprint", fingerprint);
            entry.put("pendingChange", pendingChangeId);
            entry.put("nonce", nonce);
            entry.put("approvedBy", approvedBy);
            entry.put("approvedAt", Instant.now().toString());
            all.put(name, entry);
            write(root, all);
        }
    }

    /** Drop Job {@code name}'s approval (a delete revokes it). */
    public static void revoke(Path root, String name) throws IOException {
        if (root == null || name == null) return;
        synchronized (LOCK) {
            Map<String, Object> all = read(root);
            if (all.remove(name) != null) write(root, all);
        }
    }

    /** Job {@code name}'s approval record, if any. */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> approval(Path root, String name) {
        if (root == null || name == null) return Optional.empty();
        try {
            return read(root).get(name) instanceof Map<?, ?> m ? Optional.of((Map<String, Object>) m) : Optional.empty();
        } catch (IOException unreadable) {
            return Optional.empty();   // fail closed
        }
    }

    /** Whether {@code fingerprint} is Job {@code name}'s approved attachment version. */
    public static boolean approved(Path root, String name, String fingerprint) {
        Verifier v = verifier;
        return approval(root, name)
                .filter(a -> fingerprint.equals(a.get("fingerprint")))
                .filter(a -> v != null && v.verify(root, name, a))   // no verifier installed: fail closed
                .isPresent();
    }

    /** A fresh approval nonce (hex), written into BOTH the MAC'd Pending Change and the approval record. */
    public static String newNonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /**
     * Round 5: the approval file alone proves nothing — it can be copied or computed. An approval record is honoured
     * only when the installed verifier (the control plane's, over its HMAC-signed Pending Change store) confirms the
     * Pending Change it names exists, verifies, is approved, is for this Job, carries the same nonce, and fixed the
     * same fingerprint. With none installed (an engine with no control plane) nothing attaches.
     */
    @FunctionalInterface
    public interface Verifier {
        boolean verify(Path root, String job, Map<String, Object> record);
    }

    private static volatile Verifier verifier;

    public static void installVerifier(Verifier v) { verifier = v; }

    public static Verifier verifier() { return verifier; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path root) throws IOException {
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return new TreeMap<>();
        return new TreeMap<>(JSON.readValue(f.toFile(), Map.class));
    }

    private static void write(Path root, Map<String, Object> all) throws IOException {
        AtomicFiles.write(root.resolve(FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(all), ".attach-");
    }

    private static Map<?, ?> section(Map<?, ?> m) {
        if (m == null) return Map.of();
        return m.get("job") instanceof Map<?, ?> inner ? inner : m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
