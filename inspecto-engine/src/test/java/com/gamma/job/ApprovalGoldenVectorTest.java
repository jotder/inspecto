package com.gamma.job;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GOLDEN VECTORS: fingerprints of fixed inputs, recorded from the code as it stood BEFORE
 * {@link ApprovalFingerprint} existed (master {@code 4b820d150}, where each class hashed privately). They pin the
 * canonical form permanently: any change to it re-keys every approval already on disk, so it must fail HERE first.
 * The inputs hold a null, a nested map and a list.
 */
class ApprovalGoldenVectorTest {

    static final String PUBLISH_JOB =
            "a039036d3c191b7d7969ad36b2f351a2a158e5fd187cdb2d05d2cbf3b22d8c0b";
    static final String PUBLISH_CONNECTION =
            "f61139f332faa98a17d4608432ff4c876b5b5cebc058d012ce4e61b550bfae3c";
    static final String PUBLISH_CONNECTION_LITERAL =
            "b18251136af51d67890fe46fd295058b983575a76973ee35fd85d7161568f556";
    static final String PUBLISH_DATASET =
            "833524b22486624b99d5b0a89840ef1b86f6071fe9fc36549af599a88fb3e0bd";
    static final String ATTACH_NO_DATASET =
            "a3b4a8aeb391368850fa27c81f1614dd33d76c37709cfbb32ffa3b851c4677ba";
    static final String ATTACH_WITH_DATASET =
            "ee3a6841156a14f4d57f19b3c2c0b7bd0f7f85fcdb110a3dcb10f74011957b8e";

    private static ConnectionProfile profile(String password) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("sslmode", "verify-full");
        options.put("jdbc_url", "jdbc:postgresql://bi.example.test:5432/bidb?sslmode=verify-full");
        return new ConnectionProfile("BI", "postgres", "bi.example.test", 5432, "bidb", "/base", "svc", password,
                options, new ConnectionProfile.Tunnel("bastion", 22, "u", "p"), null, null);
    }

    private static Map<String, Object> publishJob() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", "1");
        nested.put("a", new LinkedHashMap<>(Map.of("k", "  v  ")));
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("name", "to-bi");
        job.put("type", "publish.postgres");
        job.put("connection", "BI");
        job.put("datasets", "subs");
        job.put("schema", "bi");
        job.put("note", null);
        job.put("tags", List.of(" a ", "b", 3));
        job.put("nested", nested);
        job.put("cron", "0 * * * *");   // schedule: excluded from the hash
        return job;
    }

    private static Map<String, Object> dataset() {
        Map<String, Object> col1 = new LinkedHashMap<>();
        col1.put("name", "msisdn");
        col1.put("classification", "MSISDN");
        Map<String, Object> col2 = new LinkedHashMap<>();
        col2.put("name", "n");
        col2.put("classification", null);
        Map<String, Object> ds = new HashMap<>();
        ds.put("physicalRef", "subs");
        ds.put("columns", List.of(col1, col2));
        ds.put("shares", new LinkedHashMap<>(Map.of("teams", List.of("ops", "fraud"), "public", "false")));
        ds.put("owner", null);
        return ds;
    }

    @Test
    void publishFingerprintsAreByteIdenticalToTheOriginalCode(@TempDir Path root) throws Exception {
        new ComponentStore(root.resolve("registry")).write("dataset", "subs", dataset());
        Map<String, String> fp = PublicationApproval.fingerprints(publishJob(), root, id -> Optional.of(profile("${KEYSTORE:bi}")));
        assertEquals(PUBLISH_JOB, fp.get("job"), "job");
        assertEquals(PUBLISH_CONNECTION, fp.get("connection"), "connection (password by reference)");
        assertEquals(PUBLISH_DATASET, fp.get("dataset:subs"), "dataset");
        Map<String, String> literal = PublicationApproval.fingerprints(publishJob(), root, id -> Optional.of(profile("s3cret")));
        assertEquals(PUBLISH_CONNECTION_LITERAL, literal.get("connection"), "connection (literal password identity)");
    }

    @Test
    void attachFingerprintsAreByteIdenticalToTheOriginalCode(@TempDir Path root) throws Exception {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("name", "mailer");
        job.put("type", "report");
        job.put("attach", "true");
        job.put("recipients", "ops@example.com");
        job.put("measures", null);
        job.put("format", "xlsx");
        job.put("cron", "0 * * * *");   // not sensitive: excluded
        assertEquals(ATTACH_NO_DATASET, AttachApprovals.fingerprint(job, root, null), "no Dataset");

        new ComponentStore(root.resolve("registry")).write("dataset", "orders_ds", dataset());
        job.put("dataset", "orders_ds");
        assertEquals(ATTACH_WITH_DATASET, AttachApprovals.fingerprint(job, root, "data"), "with a Dataset definition + relation");
    }
}
