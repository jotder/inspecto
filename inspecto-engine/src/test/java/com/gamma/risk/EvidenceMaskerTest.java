package com.gamma.risk;

import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Risk Score mask key lives in the Space's secrets directory, owner-only, and masks deterministically. */
class EvidenceMaskerTest {

    private static EvidenceMasker masker(Path config) throws Exception {
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "topups", Map.of("physicalRef", "topups",
                "columns", List.of(Map.of("name", "topup_id", "classification", "pii"), Map.of("name", "amount"))));
        return EvidenceMasker.of(store, config, RiskScoreModel.fromMap("m", Map.of("entityType", "subscriber",
                "highThreshold", 50, "factors", List.of(Map.of("id", "f", "dataset", "topups", "key", "msisdn",
                        "measure", "count", "weight", 1, "evidence", List.of("topup_id", "amount"))))));
    }

    @Test
    void theKeyLivesInTheSecretsSiblingOfTheConfigRootNeverUnderDataOrConfig(@TempDir Path space) throws Exception {
        Path config = Files.createDirectories(space.resolve("config"));
        Object token = masker(config).mask("topups", "topup_id", "t1");
        Path key = space.resolve("config.secrets").resolve(".risk-score-mask.key");
        assertTrue(Files.isRegularFile(key), "the key is created on first use in config.secrets/");
        assertEquals(key.toAbsolutePath().normalize(), EvidenceMasker.keyFile(config));
        assertFalse(EvidenceMasker.keyFile(config).startsWith(config.toAbsolutePath().normalize()), "outside the config tree");
        try (var walk = Files.walk(space)) {
            assertEquals(List.of(key.toAbsolutePath().normalize()), walk.filter(p -> p.getFileName().toString()
                    .equals(".risk-score-mask.key")).map(p -> p.toAbsolutePath().normalize()).toList(), "exactly one copy");
        }
        assertTrue(String.valueOf(token).startsWith("masked:"));
    }

    @Test
    void theKeyIsOwnerOnlyWhereThePlatformAllows(@TempDir Path space) throws Exception {
        Path config = Files.createDirectories(space.resolve("config"));
        masker(config).mask("topups", "topup_id", "t1");
        Path file = EvidenceMasker.keyFile(config);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        Assumptions.assumeTrue(acl != null, "neither POSIX nor ACL permissions on this filesystem");
        assertEquals(1, acl.getAcl().size(), "one ACE: " + acl.getAcl());
        assertEquals(acl.getOwner(), acl.getAcl().get(0).principal());
    }

    @Test
    void onlyClassifiedColumnsAreMaskedAndTokensAreDeterministicAcrossModels(@TempDir Path space) throws Exception {
        Path config = Files.createDirectories(space.resolve("config"));
        EvidenceMasker a = masker(config);
        EvidenceMasker b = masker(config);   // a second model in the same Space
        assertEquals(12.5, a.mask("topups", "amount", 12.5), "an unclassified column is left alone");
        assertNull(a.mask("topups", "topup_id", null));
        assertEquals(a.mask("topups", "topup_id", "t1"), b.mask("topups", "topup_id", "t1"),
                "deterministic per Space: the same value links across models (documented)");
        assertNotEquals(a.mask("topups", "topup_id", "t1"), a.mask("topups", "topup_id", "t2"));
        assertEquals(List.of("topups.topup_id"), a.maskedColumns());
    }

    // ── ASSURE-CLASSIFICATION-PROPAGATION-1: a pipeline schema's classification follows its mapping into evidence ──

    /** A pipeline "cust" whose schema gives raw MSISDN {@code rawClass}, renamed to stored {@code m}, and a Dataset
     *  over it that classifies nothing itself; the model's evidence is {@code m} and {@code plan}. */
    private static EvidenceMasker custMasker(Path config, String rawClass, String extraPipeline) throws Exception {
        Files.createDirectories(config.resolve("cust"));
        Files.writeString(config.resolve("cust/cust_pipeline.toon"), "name: cust\nactive: true\n\ndirs:\n"
                + "  poll: data/inbox/cust\n  database: data/cust/database\n  backup: data/cust/backup\n"
                + "  temp: data/cust/temp\n  errors: data/cust/errors\n  quarantine: data/cust/quarantine\n"
                + "  markers: data/cust/markers\n  status_dir: data/cust/status\n  log_dir: data/cust/logs\n\n"
                + "output:\n  format: PARQUET\n  compression: snappy\n\nprocessing:\n  threads: 1\n"
                + "  file_pattern: \"glob:**/*.csv\"\n  schema_file: cust_schema.toon\n" + extraPipeline);
        Files.writeString(config.resolve("cust/cust_schema.toon"), "partitionKey: DAY\nraw:\n  name: CUST\n  format: CSV\n"
                + "  fields[2]{name,selector,type,description,unit,classification}:\n"
                + "    MSISDN,\"0\",VARCHAR,\"\",\"\",\"" + rawClass + "\"\n    PLAN,\"1\",VARCHAR,\"\",\"\",\"\"\n"
                + "mapping:\n  canonicalName: cust\n  rawName: CUST\n  fields[2]:\n"
                + "    - name: m\n      from: MSISDN\n      fn: keep\n    - name: plan\n      from: PLAN\n      fn: keep\n");
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "cust", Map.of("physicalRef", "cust"));
        return EvidenceMasker.of(store, config, RiskScoreModel.fromMap("m", Map.of("entityType", "subscriber",
                "highThreshold", 50, "factors", List.of(Map.of("id", "f", "dataset", "cust", "key", "plan",
                        "measure", "count", "weight", 1, "evidence", List.of("m", "plan"))))));
    }

    @Test
    void aColumnRenamedFromAClassifiedRawFieldIsMaskedInEvidence(@TempDir Path space) throws Exception {
        EvidenceMasker m = custMasker(Files.createDirectories(space.resolve("config")), "MSISDN", "");
        assertTrue(String.valueOf(m.mask("cust", "m", "9198")).startsWith("masked:"), "m is MSISDN under another name");
        assertEquals("gold", m.mask("cust", "plan", "gold"));
    }

    @Test
    void anUnclassifiedRawFieldLeavesTheRenamedColumnRaw(@TempDir Path space) throws Exception {
        EvidenceMasker m = custMasker(Files.createDirectories(space.resolve("config")), "", "");
        assertEquals("9198", m.mask("cust", "m", "9198"));
        assertEquals(List.of(), m.maskedColumns());
    }

    @Test
    void untraceableLineageFailsClosedAndMasksEveryEvidenceColumn(@TempDir Path space) throws Exception {
        // FAIL CLOSED: a summarize step rewrites the columns, so the mapping no longer says which stored column is
        // MSISDN; the column's lineage cannot be resolved, so it is treated as CLASSIFIED (masked), never open.
        EvidenceMasker m = custMasker(Files.createDirectories(space.resolve("config")), "MSISDN",
                "steps[1]:\n  - summarize:\n      group_by: [plan]\n");
        assertTrue(String.valueOf(m.mask("cust", "m", "9198")).startsWith("masked:"));
        assertTrue(String.valueOf(m.mask("cust", "plan", "gold")).startsWith("masked:"), "even an unrelated column");
    }

    // ── strictest wins (operator 2026-10-04): several classified inputs feed one computed column ──

    /** A pipeline "subs" with raw IMSI (class IMSI), MSISDN (class MSISDN) and PLAN, and one mapping rule
     *  {@code both} = concat of {@code args}. */
    private static Path subs(Path config, String args) throws Exception {
        Files.createDirectories(config.resolve("subs"));
        Files.writeString(config.resolve("subs/subs_pipeline.toon"), "name: subs\nactive: true\n\ndirs:\n"
                + "  poll: data/inbox/subs\n  database: data/subs/database\n  backup: data/subs/backup\n"
                + "  temp: data/subs/temp\n  errors: data/subs/errors\n  quarantine: data/subs/quarantine\n"
                + "  markers: data/subs/markers\n  status_dir: data/subs/status\n  log_dir: data/subs/logs\n\n"
                + "output:\n  format: PARQUET\n  compression: snappy\n\nprocessing:\n  threads: 1\n"
                + "  file_pattern: \"glob:**/*.csv\"\n  schema_file: subs_schema.toon\n");
        Files.writeString(config.resolve("subs/subs_schema.toon"), "partitionKey: DAY\nraw:\n  name: SUBS\n  format: CSV\n"
                + "  fields[3]{name,selector,type,description,unit,classification}:\n"
                + "    IMSI,\"0\",VARCHAR,\"\",\"\",\"IMSI\"\n    MSISDN,\"1\",VARCHAR,\"\",\"\",\"MSISDN\"\n"
                + "    PLAN,\"2\",VARCHAR,\"\",\"\",\"\"\n"
                + "mapping:\n  canonicalName: subs\n  rawName: SUBS\n  fields[1]:\n"
                + "    - name: both\n      from: \"\"\n      fn: custom\n      args:\n        expression: \"concat(" + args + ")\"\n");
        return config;
    }

    @Test
    void aConcatOfTwoClassifiedInputsTakesTheStrictestClass(@TempDir Path space) throws Exception {
        // IMSI is named FIRST, so a first-match resolver would answer IMSI; strictest-wins answers MSISDN
        Path config = subs(Files.createDirectories(space.resolve("config")), "IMSI, MSISDN");
        assertEquals("MSISDN", EvidenceMasker.schemaClassification(config, java.util.Set.of("subs"),
                EvidenceMasker.SENSITIVE::contains).get("both"));
    }

    @Test
    void aMaskedInputOutranksAHigherRankedUnmaskedOne(@TempDir Path space) throws Exception {
        // negative twin: a consumer that masks only IMSI (a Space whose MSISDN Entity Type is unmasked) gets IMSI,
        // i.e. "masked if ANY input is masked", never the higher-ranked but unmasked MSISDN
        Path config = subs(Files.createDirectories(space.resolve("config")), "MSISDN, IMSI");
        assertEquals("IMSI", EvidenceMasker.schemaClassification(config, java.util.Set.of("subs"),
                "IMSI"::equals).get("both"));
        Path plain = subs(Files.createDirectories(space.resolve("other")), "PLAN, PLAN");
        assertNull(EvidenceMasker.schemaClassification(plain, java.util.Set.of("subs"),
                EvidenceMasker.SENSITIVE::contains).get("both"), "unclassified inputs classify nothing");
    }

    // ── sibling-Dataset same-name inheritance and view lineage (the publish.postgres rules, now in evidence) ──

    /** A store "topups" that Dataset {@code topups_raw} classifies ({@code msisdn} = MSISDN), plus a factor Dataset
     *  {@code factorDs} whose evidence is {@code msisdn} and {@code amount}. */
    private static EvidenceMasker siblingMasker(Path config, Map<String, Object> factorDs) throws Exception {
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        store.write("dataset", "topups_raw", Map.of("physicalRef", "topups",
                "columns", List.of(Map.of("name", "MSISDN", "classification", "msisdn"))));
        store.write("dataset", "f_ds", factorDs);
        return EvidenceMasker.of(store, config, RiskScoreModel.fromMap("m", Map.of("entityType", "subscriber",
                "highThreshold", 50, "factors", List.of(Map.of("id", "f", "dataset", "f_ds", "key", "amount",
                        "measure", "count", "weight", 1, "evidence", List.of("msisdn", "amount"))))));
    }

    @Test
    void aSameNamedColumnInheritsTheClassificationASiblingDatasetDeclares(@TempDir Path space) throws Exception {
        // NEGATIVE PROBE: f_ds classifies nothing itself; without sibling inheritance msisdn is stored raw
        EvidenceMasker m = siblingMasker(Files.createDirectories(space.resolve("config")), Map.of("physicalRef", "topups"));
        assertTrue(String.valueOf(m.mask("f_ds", "msisdn", "9198")).startsWith("masked:"), "inherited from topups_raw");
        assertEquals(12.5, m.mask("f_ds", "amount", 12.5));
        assertEquals(List.of("f_ds.msisdn"), m.maskedColumns());
    }

    @Test
    void aSiblingOverAnotherStoreLeavesTheColumnRaw(@TempDir Path space) throws Exception {
        EvidenceMasker m = siblingMasker(Files.createDirectories(space.resolve("config")), Map.of("physicalRef", "other"));
        assertEquals("9198", m.mask("f_ds", "msisdn", "9198"));
    }

    @Test
    void aViewOverAClassifiedStoreFailsClosedAndMasksEveryEvidenceColumn(@TempDir Path space) throws Exception {
        // NEGATIVE PROBE: the view renames msisdn AS m, which cannot be traced; without view lineage both stay raw
        Path config = Files.createDirectories(space.resolve("config"));
        new com.gamma.pipeline.ViewStore(config.resolve("views")).write(new com.gamma.pipeline.ViewDefinition(
                "topups_v", "p", List.of("topups"), "SELECT msisdn AS m, amount FROM topups", "2026-10-06T00:00:00Z"));
        EvidenceMasker m = siblingMasker(config, Map.of("view", "topups_v"));
        assertTrue(String.valueOf(m.mask("f_ds", "m", "9198")).startsWith("masked:"));
        assertTrue(String.valueOf(m.mask("f_ds", "amount", 12.5)).startsWith("masked:"), "every column: fail closed");
    }

    @Test
    void aViewOverAnUnclassifiedStoreIsLeftAlone(@TempDir Path space) throws Exception {
        Path config = Files.createDirectories(space.resolve("config"));
        new com.gamma.pipeline.ViewStore(config.resolve("views")).write(new com.gamma.pipeline.ViewDefinition(
                "plain_v", "p", List.of("plain"), "SELECT amount FROM plain", "2026-10-06T00:00:00Z"));
        EvidenceMasker m = siblingMasker(config, Map.of("view", "plain_v"));
        assertEquals(12.5, m.mask("f_ds", "amount", 12.5));
    }
}
