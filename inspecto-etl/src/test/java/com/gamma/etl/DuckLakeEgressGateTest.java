package com.gamma.etl;

import com.gamma.config.safety.EgressRefusedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** S5 of policy-narrowing-design: the DuckLake catalog host and a remote data path pass the EgressGate. */
class DuckLakeEgressGateTest {

    @TempDir Path tmp;

    @AfterEach
    void clear() {
        System.clearProperty("system.config.dir");
    }

    private void policy(String toon) throws Exception {
        Files.writeString(tmp.resolve("safety-policy.toon"), toon);
        System.setProperty("system.config.dir", tmp.toString());
    }

    @Test
    void aCatalogHostOutsideAllowHostsIsRefusedAndItsTwinPasses() throws Exception {
        policy("allow:\n  hosts[1]: lake.allowed.test\n");
        assertThrows(EgressRefusedException.class, () -> DuckLakeRegistrar.gateEgress(
                "postgres:dbname=lake host=db.denied.test port=5432", "/data"));
        assertDoesNotThrow(() -> DuckLakeRegistrar.gateEgress(
                "postgres:dbname=lake host=lake.allowed.test port=5432", "/data"));
    }

    @Test
    void aRemoteDataPathIsGatedOnItsAuthority() throws Exception {
        policy("allow:\n  hosts[1]: lake.allowed.test\n");
        assertThrows(EgressRefusedException.class,
                () -> DuckLakeRegistrar.gateEgress("lake.ducklake", "s3://bucket.denied.test/prefix"));
        assertDoesNotThrow(() -> DuckLakeRegistrar.gateEgress("lake.ducklake", "/local/data"));
    }

    @Test
    void networkFalseRefusesASharedCatalogButNotAFileCatalog() throws Exception {
        policy("permit:\n  network: false\n");
        assertThrows(EgressRefusedException.class,
                () -> DuckLakeRegistrar.gateEgress("postgres:dbname=lake host=127.0.0.1", "/d"));
        assertDoesNotThrow(() -> DuckLakeRegistrar.gateEgress("lake.ducklake", "/d"));
    }
}
