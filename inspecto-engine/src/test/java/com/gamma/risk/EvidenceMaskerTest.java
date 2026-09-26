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
}
