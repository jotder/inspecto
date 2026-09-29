package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.SecretResolver;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * <b>What a four-eyes approval of a {@code publish.postgres} Job pins</b> (ASSURE-BI-PUBLICATION-1, decision
 * 2026-09-29): a SHA-256 CONTENT fingerprint, not names. Three kinds, keyed in one map:
 * <ul>
 *   <li>{@code job} — every key of the Job except its schedule ({@code cron}, {@code on_pipeline},
 *       {@code on_signal}, {@code when}, {@code catch_up}, {@code enabled}) and the server-stamped author keys;</li>
 *   <li>{@code connection} — the resolved Connection: connector, host, port, database, user, base path, every
 *       option (jdbc_url, so sslmode and sslrootcert, and insecure_tls), tunnel/proxy, and the password's
 *       IDENTITY — the {@code ${…}} reference itself, or a SHA-256 of a literal; never the secret;</li>
 *   <li>{@code dataset:<id>} — each Dataset's definition: its relation keys ({@code physicalRef}, {@code view},
 *       {@code sql}, {@code sourceName}, {@code calculated}), its columns with their classification, its sharing
 *       envelope, and a view's stored definition.</li>
 * </ul>
 * Recorded at approval in {@value #DIR}{@code <job>.json} — a directory no Job writer touches and every import is
 * refused ({@code ReservedConfigPaths}) — and recomputed on every run; any difference refuses the run.
 */
public final class PublicationApproval {

    public static final String DIR = "publication-approvals/";
    static final Set<String> NOT_CONTENT = Set.of("cron", "on_pipeline", "on_signal", "when", "catch_up", "enabled",
            JobConfig.CREATED_BY, JobConfig.UPDATED_BY, JobConfig.UPDATED_BY_ROLES);
    static final List<String> DATASET_KEYS = List.of("physicalRef", "view", "sql", "sourceName", "calculated",
            "columns", "owner", "shares");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final ObjectMapper JSON = new ObjectMapper();

    private PublicationApproval() {}

    /** The fingerprints of a Job section ({@code job:} or bare) as it would run now in the Space at {@code configRoot}. */
    public static Map<String, String> fingerprints(Map<?, ?> jobDoc, Path configRoot,
                                                   Function<String, Optional<ConnectionProfile>> connections) {
        Map<?, ?> job = jobDoc.get("job") instanceof Map<?, ?> j ? j : jobDoc;
        Map<String, String> out = new TreeMap<>();
        Map<String, Object> content = new TreeMap<>();
        job.forEach((k, v) -> { if (!NOT_CONTENT.contains(String.valueOf(k))) content.put(String.valueOf(k), canonical(v)); });
        out.put("job", sha(content));
        String conn = job.get(PostgresPublishJobType.P_CONNECTION) == null ? "" : String.valueOf(job.get(PostgresPublishJobType.P_CONNECTION)).trim();
        out.put("connection", sha(connection(connections.apply(conn).orElse(null))));
        ComponentStore store = new ComponentStore(configRoot.resolve("registry"));
        ViewStore views = new ViewStore(configRoot.resolve("views"));
        for (String id : PostgresPublishJobType.csv(job.get(PostgresPublishJobType.P_DATASETS) == null ? null
                : String.valueOf(job.get(PostgresPublishJobType.P_DATASETS)))) {
            Map<String, Object> ds = store.get("dataset", id).map(ComponentRegistry.Component::content).orElse(null);
            Map<String, Object> def = new TreeMap<>();
            if (ds != null) {
                for (String k : DATASET_KEYS) if (ds.containsKey(k)) def.put(k, canonical(ds.get(k)));
                if (ds.get("view") != null)
                    views.get(String.valueOf(ds.get("view"))).ifPresent(v -> def.put("viewDefinition", canonical(v.toMap())));
            } else {
                def.put("absent", true);
            }
            out.put("dataset:" + id, sha(def));
        }
        return out;
    }

    static Map<String, Object> connection(ConnectionProfile p) {
        Map<String, Object> m = new TreeMap<>();
        if (p == null) { m.put("absent", true); return m; }
        m.put("connector", p.connector());
        m.put("host", p.host());
        m.put("port", p.port());
        m.put("database", p.database());
        m.put("basePath", p.basePath());
        m.put("user", p.username());
        m.put("options", p.options() == null ? Map.of() : new TreeMap<>(p.options()));
        m.put("tunnel", p.tunnel() == null ? null : p.tunnel().endpoint());
        m.put("proxy", p.proxy() == null ? null : p.proxy().endpoint());
        String pw = p.password();
        m.put("password", pw == null ? null : SecretResolver.isReference(pw) ? "ref:" + pw
                : "literal-sha256:" + HexFormat.of().formatHex(digest(pw.getBytes(StandardCharsets.UTF_8))));
        return m;
    }

    /** Record the approval of {@code job}'s fingerprints ({@code approvedBy} is the approving Subject). */
    public static void record(Path configRoot, String job, Map<String, String> fingerprints, String approvedBy,
                              Set<String> approverCapabilities) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("job", job);
        doc.put("fingerprints", new TreeMap<>(fingerprints));
        doc.put("approvedBy", approvedBy);
        doc.put("approverCapabilities", new java.util.TreeSet<>(approverCapabilities));
        doc.put("approvedAt", java.time.Instant.now().toString());
        Path f = file(configRoot, job);
        Files.createDirectories(f.getParent());
        AtomicFiles.write(f, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc), ".approval-");
    }

    /** The recorded fingerprints of {@code job}, or empty when it was never approved (or the record is unreadable). */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, String>> approved(Path configRoot, String job) {
        try {
            Path f = file(configRoot, job);
            if (!Files.exists(f)) return Optional.empty();
            Map<String, Object> doc = JSON.readValue(f.toFile(), Map.class);
            if (!job.equals(doc.get("job")) || !(doc.get("fingerprints") instanceof Map<?, ?> fp)) return Optional.empty();
            Map<String, String> out = new TreeMap<>();
            fp.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            return Optional.of(out);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** What differs between the approved and the current fingerprints, in words ({@code connection}, {@code dataset X},
     *  {@code job params}); empty when nothing does. */
    public static List<String> changed(Map<String, String> approved, Map<String, String> now) {
        List<String> out = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>(approved.keySet());
        keys.addAll(now.keySet());
        for (String k : keys)
            if (!java.util.Objects.equals(approved.get(k), now.get(k)))
                out.add(k.equals("job") ? "job params" : k.startsWith("dataset:") ? "dataset " + k.substring(8) : k);
        return out;
    }

    static Path file(Path configRoot, String job) {
        if (job == null || !SAFE_NAME.matcher(job).matches() || job.contains(".."))
            throw new IllegalArgumentException("unsafe Job name '" + job + "'");
        return configRoot.resolve(DIR).resolve(job + ".json");
    }

    @SuppressWarnings("unchecked")
    private static Object canonical(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), canonical(x)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(PublicationApproval::canonical).toList();
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String sha(Object canonical) {
        try {
            return HexFormat.of().formatHex(digest(JSON.writeValueAsBytes(canonical)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
