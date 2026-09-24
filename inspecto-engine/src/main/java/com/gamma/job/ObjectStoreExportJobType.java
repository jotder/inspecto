package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.acquire.ExportConnector;
import com.gamma.acquire.ExportConnectorFactory;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.config.safety.PathJail;
import com.gamma.etl.PipelineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code objectstore.export} (EXPORT-1) — the push post-action: after a successful run, deliver a directory
 * under the Space data root ({@code <store>/database}, or one Dataset's output directory) to an
 * S3-compatible object store through an {@link ExportConnector}.
 *
 * <p><b>"After a successful run"</b> is the Job framework's own trigger: {@code on_pipeline: <name>} fires on
 * that pipeline's batch COMMIT, never on a failed run. No new pipeline key exists for this — the pipeline
 * TOON does not learn about exports, and credentials never appear in any TOON but the Connection's own
 * {@code *_connection.toon}, as {@code ${…}} references.
 *
 * <p>The contract, each half pinned by {@code ObjectStoreExportJobTest} (inspecto-connectors, against a fake S3):
 * <ul>
 *   <li><b>Idempotent.</b> An object whose remote size AND ETag equal the local file's size and MD5 is
 *       skipped. A single-part PUT's ETag is the body's MD5; an ETag of any other shape (multipart, SSE-KMS)
 *       never matches, so the file is re-sent — the conservative answer.</li>
 *   <li><b>Upload-then-manifest.</b> {@value #MANIFEST} is written LAST, once every file is confirmed. A
 *       consumer that reads the manifest first never sees a half-delivered export.</li>
 *   <li><b>Failure is failure.</b> Any file that still fails after {@code retries} leaves the run FAILED and
 *       the manifest unwritten.</li>
 *   <li><b>Path-jailed local reads.</b> {@code local_path} is contained in the Space data root; a symlink
 *       anywhere in the tree refuses the run before a byte is sent.</li>
 * </ul>
 *
 * <p>⚠ {@code local_path} is deliberately NOT in {@code ConfigSafetyValidator.JOB_PATH_KEYS}: that list is
 * for keys resolved against {@code SpaceConfigRoot.jobPathBase}, and this one resolves against the Space
 * DATA root, where the files it reads live. It is jailed here, at run time, against that root.
 *
 * <p>⛔ HDFS is reached only through an S3-compatible gateway (Ozone S3, MinIO) — never {@code hadoop-client}.
 */
public final class ObjectStoreExportJobType implements JobTypeProvider {

    public static final String TYPE_ID = "objectstore.export";
    /** The completion marker, written at the export prefix once every file is delivered. */
    public static final String MANIFEST = "_inspecto_export_manifest.json";
    /** S3's single-PUT ceiling; a larger file needs multipart upload, which is not built. */
    static final long MAX_SINGLE_PUT = 5L * 1024 * 1024 * 1024;

    static final String P_CONNECTION = "connection";
    static final String P_LOCAL_PATH = "local_path";
    static final String P_REMOTE_PREFIX = "remote_prefix";
    static final String P_RETRIES = "retries";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String dataDir;

    /** @param dataDir the Space data root that {@code local_path} is contained in (the built-in injection convention) */
    public ObjectStoreExportJobType(String dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(TYPE_ID, "Object Storage Export",
                "Pushes a directory under the Space data root to an S3-compatible object store after a "
                        + "successful run: unchanged files are skipped, the manifest is written last.",
                List.of(ParameterDecl.required(P_CONNECTION, ParamType.STRING,
                                "Id of the s3 Connection to deliver to; its base_path (bucket[/prefix]) is the "
                                        + "export root and its credentials stay in the Connection"),
                        ParameterDecl.required(P_LOCAL_PATH, ParamType.STRING,
                                "Directory under the Space data root to export, e.g. orders/database"),
                        ParameterDecl.optional(P_REMOTE_PREFIX, ParamType.STRING, null,
                                "Key prefix under the Connection's base_path (default: local_path)"),
                        ParameterDecl.optional(P_RETRIES, ParamType.INTEGER, "3",
                                "Retries per object on a failed request, with exponential backoff")),
                List.of(), List.of());
    }

    @Override
    public Job create(JobConfig config) {
        return new ExportJob(config.name(), dataDir);
    }

    record Planned(Path file, String key, long size) {}

    private static final class ExportJob implements Job {

        private final String name;
        private final String dataDir;

        ExportJob(String name, String dataDir) {
            this.name = name;
            this.dataDir = dataDir;
        }

        @Override public String name() { return name; }

        @Override public String type() { return TYPE_ID; }

        @Override
        public JobResult run() {
            return JobResult.failed(TYPE_ID + " requires a JobContext (its parameters name the Connection)", 0L);
        }

        @Override
        public JobResult run(JobContext ctx) {
            long t0 = System.nanoTime();
            Map<String, String> p = ctx.params().isEmpty() ? ctx.config() : ctx.params();
            try {
                return JobResult.ok(export(ctx, p), ms(t0));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                ctx.log().error("object-storage export failed — no manifest was written", e, "job", name);
                return JobResult.failed(TYPE_ID + " failed, no manifest written: " + e.getMessage(), ms(t0));
            }
        }

        private String export(JobContext ctx, Map<String, String> p) throws Exception {
            if (dataDir == null || dataDir.isBlank())
                throw new IllegalStateException("no Space data root is configured for this job");
            String connection = required(p, P_CONNECTION);
            ConnectionProfile profile = ConnectionRegistry.find(connection).orElseThrow(() ->
                    new IllegalStateException("Connection '" + connection + "' is not registered in this space"));
            if (profile.tunnel() != null || profile.proxy() != null)
                throw new IllegalStateException("Connection '" + connection + "' declares a tunnel or proxy, which "
                        + "the export transport does not dial through — refused rather than bypassed");

            Path dataRoot = Path.of(dataDir).toAbsolutePath().normalize();
            String localPath = required(p, P_LOCAL_PATH);
            Path root = PathJail.require(dataRoot, dataRoot.resolve(localPath).toString(), P_LOCAL_PATH);
            if (!Files.isDirectory(root))
                throw new IllegalArgumentException(P_LOCAL_PATH + " '" + localPath + "' is not a directory under the data root");
            String prefix = remotePrefix(p.get(P_REMOTE_PREFIX), localPath);
            RetryPolicy retry = RetryPolicy.from(new PipelineConfig.Retry(retries(p.get(P_RETRIES)),
                    "EXPONENTIAL", 1_000L, 30_000L));

            List<Planned> plan = plan(dataRoot, root, prefix);   // every refusal fires before any byte is sent
            ExportConnector out = ExportConnectorFactory.forProfile(profile);
            List<Map<String, Object>> entries = new ArrayList<>();
            int uploaded = 0, unchanged = 0;
            for (Planned f : plan) {
                String[] digests = digests(f.file());
                String md5 = digests[0];
                Optional<RemoteFile> remote = retry.execute(() -> out.stat(f.key()));
                boolean same = remote.isPresent() && remote.get().size() == f.size()
                        && md5.equalsIgnoreCase(remote.get().etag());
                if (same) {
                    unchanged++;
                } else if (!ctx.dryRun()) {
                    retry.execute(() -> { out.put(f.key(), f.file(), md5, digests[1]); return null; });
                    uploaded++;
                    ctx.log().info("exported", "key", f.key(), "bytes", f.size());
                } else {
                    uploaded++;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("key", f.key());
                e.put("size", f.size());
                e.put("md5", md5);
                entries.add(e);
            }
            String target = connection + ":" + prefix;
            if (ctx.dryRun())
                return "dry run: would upload " + uploaded + " and skip " + unchanged + " unchanged of "
                        + plan.size() + " file(s) to " + target + "; nothing was sent";

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("job", name);
            manifest.put("run_id", ctx.runId());
            manifest.put("exported_at", Instant.now().toString());
            manifest.put("local_path", localPath);
            manifest.put("files", entries);
            byte[] body = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
            retry.execute(() -> { out.put(prefix + MANIFEST, body, "application/json"); return null; });
            return "exported " + plan.size() + " file(s) to " + target + ": " + uploaded + " uploaded, "
                    + unchanged + " unchanged; manifest written";
        }
    }

    /** Walk {@code root}, refusing symlinks and anything the data-root jail would not contain. */
    static List<Planned> plan(Path dataRoot, Path root, String prefix) throws IOException {
        List<Planned> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : (Iterable<Path>) walk.sorted()::iterator) {
                if (Files.isSymbolicLink(f))
                    throw new IllegalStateException("refusing to export through the symlink " + f);
                if (!Files.isRegularFile(f)) continue;
                String fileName = f.getFileName().toString();
                if (fileName.startsWith(".") || fileName.endsWith(".tmp")) continue;   // in-flight / hidden
                if (!PathJail.contains(dataRoot, f))
                    throw new IllegalStateException(f + " is outside the data root " + dataRoot);
                if (fileName.equals(MANIFEST))
                    throw new IllegalStateException("a local file is named " + MANIFEST + ", the export's own marker");
                long size = Files.size(f);
                if (size > MAX_SINGLE_PUT)
                    throw new IllegalStateException(f + " is " + size + " bytes, over the 5 GiB single-PUT limit "
                            + "(multipart upload is not built)");
                out.add(new Planned(f, prefix + root.relativize(f).toString().replace('\\', '/'), size));
            }
        }
        return out;
    }

    /** The export prefix, {@code ""} or ending in {@code /}; a {@code .}/{@code ..} segment is refused. */
    static String remotePrefix(String authored, String localPath) {
        String s = (authored == null || authored.isBlank() ? localPath : authored).replace('\\', '/').trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        for (String seg : s.split("/"))
            if (seg.equals(".") || seg.equals(".."))
                throw new IllegalArgumentException(P_REMOTE_PREFIX + " '" + s + "' may not contain '.' or '..' segments");
        return s.isEmpty() ? "" : s + "/";
    }

    private static int retries(String v) {
        if (v == null || v.isBlank()) return 3;
        int n = Integer.parseInt(v.trim());
        if (n < 0 || n > 10) throw new IllegalArgumentException(P_RETRIES + " must be 0..10, got " + n);
        return n;
    }

    /** {@code [md5Hex, sha256Hex]} in one read — MD5 for the ETag comparison, SHA-256 for SigV4. */
    static String[] digests(Path file) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                for (int n; (n = in.read(buf)) > 0; ) {
                    md5.update(buf, 0, n);
                    sha.update(buf, 0, n);
                }
            }
            return new String[] {HexFormat.of().formatHex(md5.digest()), HexFormat.of().formatHex(sha.digest())};
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK without MD5/SHA-256", e);
        }
    }

    private static String required(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("parameter '" + key + "' is required");
        return v.trim();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
