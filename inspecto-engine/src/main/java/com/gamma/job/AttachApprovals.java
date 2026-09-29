package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The RUN-TIME lock on report attachments (ASSURE-XLSX-ATTACHMENTS-1 round 3, operator 2026-09-29). Write-time
 * holds are the friendly path. This class is the one that cannot be walked around, because it checks at send time
 * what the Job actually IS, whoever wrote it: a template expansion, a hand edit, an import, a recovery create, or
 * any future writer.
 *
 * <p>When a four-eyes approval applies a change to an attaching report Job, the control plane records an
 * <b>approval fingerprint</b> ({@link #record}). This is a SHA-256 over the Job exactly as the scheduler holds it
 * ({@link JobConfig}, i.e. template-EXPANDED): its name, type and every param except the server-stamped author
 * keys, plus the definition of the Dataset it reads. {@code ReportJob} with {@code attach: true} sends only when
 * the current fingerprint equals the approved one ({@link #approved}). Anything else fails the Run, with an audit
 * row and a Signal saying "attach not approved for this job version; re-approve".
 *
 * <p>Stored as {@value #FILE} in the Space config root and reserved from every import ({@code ReservedConfigPaths}).
 * ⚠ Someone with shell access to the config root can still forge it; they could equally edit anything. The threat
 * this closes is the product's own writers.
 */
public final class AttachApprovals {

    private AttachApprovals() {}

    public static final String FILE = "attach-approvals.json";
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Object LOCK = new Object();

    /** THE predicate for "this Job attaches data", shared by the write-time guard and {@code ReportJob}. */
    public static boolean attaches(Map<?, ?> job) {
        if (job == null) return false;
        Map<?, ?> j = job.get("job") instanceof Map<?, ?> inner ? inner : job;
        return "report".equalsIgnoreCase(str(j.get("type"))) && "true".equalsIgnoreCase(str(j.get("attach")));
    }

    /** {@link #attaches(Map)} over a loaded Job. */
    public static boolean attaches(JobConfig cfg) {
        Map<String, Object> m = new LinkedHashMap<>(cfg.params());
        m.put("type", cfg.type());
        return attaches(m);
    }

    /** The approval fingerprint of {@code cfg} as it would run in the Space whose config root is {@code root}. */
    public static String fingerprint(JobConfig cfg, Path root) throws IOException {
        Map<String, Object> canon = new TreeMap<>();
        canon.put("name", cfg.name());
        canon.put("type", cfg.type());
        Map<String, Object> params = new TreeMap<>();
        cfg.params().forEach((k, v) -> { if (!JobConfig.AUTHOR_KEYS.contains(k)) params.put(k, v); });
        canon.put("params", params);
        String dataset = cfg.params().get("dataset");
        if (dataset != null && !dataset.isBlank() && root != null)
            canon.put("dataset", new ComponentStore(root.resolve("registry")).get("dataset", dataset.trim())
                    .map(ComponentRegistry.Component::content).orElse(null));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(canon).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Record {@code fingerprint} as the approved version of Job {@code name} (replaces any earlier one). */
    public static void record(Path root, String name, String fingerprint) throws IOException {
        synchronized (LOCK) {
            Map<String, Object> all = read(root);
            all.put(name, fingerprint);
            AtomicFiles.write(root.resolve(FILE), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(all), ".attach-");
        }
    }

    /** Whether {@code fingerprint} is the approved version of Job {@code name}; false with no root or no record. */
    public static boolean approved(Path root, String name, String fingerprint) {
        if (root == null) return false;
        try {
            return fingerprint.equals(read(root).get(name));
        } catch (IOException unreadable) {
            return false;   // fail closed
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path root) throws IOException {
        Path f = root.resolve(FILE);
        if (!Files.isRegularFile(f)) return new TreeMap<>();
        return new TreeMap<>(JSON.readValue(f.toFile(), Map.class));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
