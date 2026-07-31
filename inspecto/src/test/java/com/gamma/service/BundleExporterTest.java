package com.gamma.service;

import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Packages a data-source bundle / whole space into a zip + bundle.toon manifest. */
class BundleExporterTest {

    @Test
    void exportDataSourceZipsBundleFilesRelativeToConfigPlusManifest(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Path pipeline = write(config.resolve("voucher/voucher_pipeline.toon"), "name: VOUCHER_ETL\n");
        // A connection fixture must be a *loadable* profile since the exporter now re-serialises it to strip
        // secrets (W3): `connector` is mandatory in ConnectionProfile's constructor, so an id-only stub was
        // never a valid connection file — and the exporter is deliberately fail-closed about that.
        Path conn     = write(config.resolve("connections/voucher_conn_connection.toon"),
                "connection:\n  id: VOUCHER_CONN\n  connector: sftp\n");
        Path schema   = write(config.resolve("voucher/voucher_schema.toon"), "raw:\n  name: VOUCHER\n");
        Path job      = write(config.resolve("voucher/voucher_job.toon"), "job:\n  name: vh\n");

        DataSourceBundle bundle = new DataSourceBundle("voucher_etl", pipeline, conn, List.of(schema), List.of(job));
        byte[] zip = BundleExporter.exportDataSource(bundle, config, "voucher-space");

        Map<String, byte[]> got = unzip(zip);
        assertTrue(got.containsKey("bundle.toon"), "manifest at zip root");
        assertTrue(got.containsKey("voucher/voucher_pipeline.toon"), "config-relative entry, subdir preserved");
        assertTrue(got.containsKey("connections/voucher_conn_connection.toon"));
        assertTrue(got.containsKey("voucher/voucher_schema.toon"));
        assertTrue(got.containsKey("voucher/voucher_job.toon"));
        assertEquals("name: VOUCHER_ETL\n", new String(got.get("voucher/voucher_pipeline.toon"), StandardCharsets.UTF_8),
                "file bytes preserved verbatim");

        Map<String, Object> manifest = parseManifest(tmp, got.get("bundle.toon"));
        assertEquals("datasource", manifest.get("kind"));
        assertEquals("voucher-space", manifest.get("source_space"));
        assertEquals("voucher_etl", manifest.get("data_source"));
        assertEquals(BundleExporter.SCHEMA_VERSION, ((Number) manifest.get("schema_version")).intValue());
        assertInstanceOf(List.class, manifest.get("artifacts"), "an artifact list is recorded");
    }

    @Test
    void exportSpaceIncludesWholeConfigTreeAndSpaceToon(@TempDir Path tmp) throws Exception {
        Path base   = tmp.resolve("space-a");
        Path config = base.resolve("config");
        write(config.resolve("a_pipeline.toon"), "name: A\n");
        write(config.resolve("sub/b_schema.toon"), "raw:\n");
        Path spaceToon = write(base.resolve("space.toon"), "display_name: \"A\"\n");

        byte[] zip = BundleExporter.exportSpace(config, spaceToon, "space-a");

        Map<String, byte[]> got = unzip(zip);
        assertTrue(got.containsKey("bundle.toon"));
        assertTrue(got.containsKey("space.toon"), "the space manifest rides at the zip root");
        assertTrue(got.containsKey("a_pipeline.toon"));
        assertTrue(got.containsKey("sub/b_schema.toon"), "nested config files preserved");

        Map<String, Object> manifest = parseManifest(tmp, got.get("bundle.toon"));
        assertEquals("space", manifest.get("kind"));
        assertEquals("space-a", manifest.get("source_space"));
    }

    /**
     * W3, 2026-07-31. Both export paths used to {@code Files.readAllBytes} a {@code *_connection.toon}, and
     * nothing upstream forces a credential to be a {@code ${…}} reference — {@code POST /connections} accepts
     * a literal and {@code connectionDoc} writes it unmasked — so a plaintext password was zipped and served
     * by the export routes. These tests are the regression bar: they read the emitted entry and assert the
     * secret is not in the bytes at all.
     */
    @Test
    void aLiteralConnectionPasswordNeverReachesTheZip(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Path pipeline = write(config.resolve("p_pipeline.toon"), "name: P\n");
        Path conn = write(config.resolve("c_connection.toon"), """
                connection:
                  id: C
                  connector: sftp
                  host: sftp.example.com
                  username: svc_acct
                  password: hunter2-literal-secret
                """);

        byte[] zip = BundleExporter.exportDataSource(
                new DataSourceBundle("ds", pipeline, conn, List.of(), List.of()), config, "sp");
        String entry = new String(unzip(zip).get("c_connection.toon"), StandardCharsets.UTF_8);

        assertFalse(entry.contains("hunter2-literal-secret"), "the literal password must not travel: " + entry);
        assertFalse(entry.contains("***"), "omitted, not masked — a *** sentinel would round-trip as a literal");
        assertFalse(entry.contains("password"), "the key itself is dropped when its value cannot travel");
        // The non-secret identity of the profile still has to survive, or promotion is useless.
        assertTrue(entry.contains("sftp.example.com"), "host survives");
        assertTrue(entry.contains("svc_acct"), "username is not a secret and survives");
        assertTrue(entry.contains("C"), "id survives");
    }

    @Test
    void aSecretReferenceIsSafeAndSurvives(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Path pipeline = write(config.resolve("p_pipeline.toon"), "name: P\n");
        Path conn = write(config.resolve("c_connection.toon"), """
                connection:
                  id: C
                  connector: sftp
                  password: ${ENV:SFTP_PW}
                """);

        byte[] zip = BundleExporter.exportDataSource(
                new DataSourceBundle("ds", pipeline, conn, List.of(), List.of()), config, "sp");
        String entry = new String(unzip(zip).get("c_connection.toon"), StandardCharsets.UTF_8);

        assertTrue(entry.contains("${ENV:SFTP_PW}"), "a reference is not a secret — it is the portable form");
    }

    @Test
    void secretLookingOptionsAreStrippedButOrdinaryOptionsSurvive(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Path pipeline = write(config.resolve("p_pipeline.toon"), "name: P\n");
        Path conn = write(config.resolve("c_connection.toon"), """
                connection:
                  id: C
                  connector: kafka
                  options:
                    topic: cdr.events
                    sasl_password: literal-pw
                    api_token: literal-token
                    ssl_key: literal-key
                """);

        byte[] zip = BundleExporter.exportDataSource(
                new DataSourceBundle("ds", pipeline, conn, List.of(), List.of()), config, "sp");
        String entry = new String(unzip(zip).get("c_connection.toon"), StandardCharsets.UTF_8);

        assertFalse(entry.contains("literal-pw"), entry);
        assertFalse(entry.contains("literal-token"), entry);
        assertFalse(entry.contains("literal-key"), entry);
        assertTrue(entry.contains("cdr.events"), "a non-secret option must still travel");
    }

    /** The whole-space walk is the wider hole of the two — it sweeps in every connection file. */
    @Test
    void exportSpaceAlsoStripsConnectionSecrets(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("sp");
        Path config = base.resolve("config");
        write(config.resolve("a_pipeline.toon"), "name: A\n");
        write(config.resolve("nested/c_connection.toon"), """
                connection:
                  id: C
                  connector: sftp
                  password: space-export-literal
                """);

        byte[] zip = BundleExporter.exportSpace(config, null, "sp");
        String entry = new String(unzip(zip).get("nested/c_connection.toon"), StandardCharsets.UTF_8);

        assertFalse(entry.contains("space-export-literal"), entry);
    }

    /**
     * Fail-closed, and it is a real branch rather than a theoretical one: `connector` is mandatory in
     * {@link com.gamma.acquire.ConnectionProfile}'s constructor, so a connection file missing it does not
     * parse — and an unparseable connection file is exactly the one whose secrets cannot be proven absent.
     * Aborting a promotion is recoverable; leaking a credential into a zip is not.
     */
    @Test
    void anUnparseableConnectionFileAbortsTheExportRatherThanTravellingVerbatim(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Path pipeline = write(config.resolve("p_pipeline.toon"), "name: P\n");
        Path conn = write(config.resolve("c_connection.toon"), "connection:\n  id: C\n  password: leaked\n");

        DataSourceBundle bundle = new DataSourceBundle("ds", pipeline, conn, List.of(), List.of());
        var e = assertThrows(java.io.IOException.class,
                () -> BundleExporter.exportDataSource(bundle, config, "sp"));
        assertTrue(e.getMessage().contains("c_connection.toon"), "names the offending file: " + e.getMessage());
        assertFalse(e.getMessage().contains("leaked"), "the error must not quote the secret it refused to ship");
    }

    /** A non-connection file must keep travelling byte-for-byte — the sanitiser is targeted, not global. */
    @Test
    void nonConnectionFilesAreStillVerbatim(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        String body = "raw:\n  name: V\n  # password: not-a-connection-file\n";
        Path pipeline = write(config.resolve("p_pipeline.toon"), "name: P\n");
        Path schema = write(config.resolve("v_schema.toon"), body);

        byte[] zip = BundleExporter.exportDataSource(
                new DataSourceBundle("ds", pipeline, null, List.of(schema), List.of()), config, "sp");

        assertEquals(body, new String(unzip(zip).get("v_schema.toon"), StandardCharsets.UTF_8));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Path write(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                out.put(e.getName(), zis.readAllBytes());
            }
        }
        return out;
    }

    private static Map<String, Object> parseManifest(Path tmp, byte[] manifestBytes) throws Exception {
        Path mf = tmp.resolve("manifest-" + System.nanoTime() + ".toon");
        Files.write(mf, manifestBytes);
        return ToonHelper.load(mf.toString());   // proves the emitted bundle.toon is valid, re-parsable TOON
    }
}
