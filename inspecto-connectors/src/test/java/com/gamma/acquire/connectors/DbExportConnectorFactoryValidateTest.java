package com.gamma.acquire.connectors;

import com.gamma.acquire.CollectorConnectors;
import com.gamma.acquire.ConnectionProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code db} Connection's required options are judged at the SAVE (`PROCESSOR-RELEASE-READINESS-1`,
 * 2026-09-24): {@link CollectorConnectors#validate} finds this module's {@link DbExportConnectorFactory} through
 * the real {@code ServiceLoader} lookup and runs the connector constructor's checks, which open nothing. Before
 * it, a Connection missing {@code options.query} saved 200 and failed on the Collector's first cycle.
 */
class DbExportConnectorFactoryValidateTest {

    private static ConnectionProfile db(Map<String, Object> options) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "ORDERS_DB");
        c.put("connector", "db");
        c.put("options", options);
        return ConnectionProfile.fromMap(c);
    }

    @Test
    void aDbConnectionWithoutAQueryIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CollectorConnectors.validate(db(Map.of("jdbc_url", "jdbc:duckdb:", "export_name", "x.csv"))));
        assertTrue(e.getMessage().contains("options.query"), e.getMessage());
    }

    @Test
    void aDbConnectionWithoutAnExportNameIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CollectorConnectors.validate(db(Map.of("jdbc_url", "jdbc:duckdb:", "query", "SELECT 1"))));
        assertTrue(e.getMessage().contains("options.export_name"), e.getMessage());
    }

    @Test
    void aWatermarkColumnWithoutThePlaceholderIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CollectorConnectors.validate(db(Map.of("jdbc_url", "jdbc:duckdb:", "query", "SELECT * FROM t",
                        "export_name", "x.csv", "watermark_column", "updated_at"))));
        assertTrue(e.getMessage().contains(":watermark"), e.getMessage());
    }

    @Test
    void aCompleteDbConnectionAndOtherConnectorsAreAccepted() {
        assertDoesNotThrow(() -> CollectorConnectors.validate(db(Map.of("jdbc_url", "jdbc:duckdb:",
                "query", "SELECT * FROM t WHERE updated_at > :watermark", "export_name", "x.csv",
                "watermark_column", "updated_at"))));
        Map<String, Object> sftp = new LinkedHashMap<>(Map.of("id", "S", "connector", "sftp", "host", "h"));
        assertDoesNotThrow(() -> CollectorConnectors.validate(ConnectionProfile.fromMap(sftp)),
                "a factory that does not override validate accepts");
    }
}
