package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Parses a bundle zip and unpacks it into a config dir (jailed against zip-slip). */
class BundleImporterTest {

    @Test
    void parsesManifestAndSplitsOutTheSpaceToon() throws Exception {
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: space\nschema_version: 1\nsource_space: alpha\n",
                "space.toon", "display_name: \"Alpha\"\n",
                "a_pipeline.toon", "name: A_ETL\n"));

        BundleImporter.Bundle b = BundleImporter.parse(zip);
        assertEquals("space", b.kind());
        assertEquals("alpha", b.manifest().get("source_space"));
        assertNotNull(b.spaceToon(), "space.toon is split out of the config entries");
        assertTrue(b.configEntries().containsKey("a_pipeline.toon"));
        assertFalse(b.configEntries().containsKey("bundle.toon"), "manifest is not a config entry");
        assertFalse(b.configEntries().containsKey("space.toon"), "space.toon is not a config entry");
    }

    @Test
    void pipelineIdsAreTheLowercasedInFileNames() throws Exception {
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "voucher/voucher_pipeline.toon", "name: VOUCHER_ETL\n",
                "voucher/voucher_schema.toon", "raw:\n  name: V\n"));
        assertEquals(List.of("voucher_etl"), BundleImporter.pipelineIds(BundleImporter.parse(zip)));
    }

    /**
     * A missing connection disables every config that needs it, by that kind's own switch, and yields one
     * warning per connection. A pipeline needs a connection through {@code collector.connection} OR
     * {@code webhook.connection}; a job through {@code job.connection}. A carried or registered connection
     * disables nothing, and an untouched entry keeps its exact bytes.
     */
    @Test
    void disableForMissingConnectionsSwitchesOffEachDependentByItsOwnSwitch() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("bundle.toon", "kind: datasource\n");
        files.put("a_pipeline.toon", "name: A_ETL\nactive: true\ncollector:\n  connection: gone\n");
        files.put("b_pipeline.toon", "name: B_ETL\nactive: true\nwebhook:\n  connection: gone\n");
        files.put("c_pipeline.toon", "name: C_ETL\nactive: true\ncollector:\n  connection: carried\n");
        files.put("d_pipeline.toon", "name: D_ETL\nactive: true\ncollector:\n  connection: registered\n");
        files.put("x_job.toon", "job:\n  name: X\n  type: objectstore.export\n  connection: other_gone\n");
        files.put("carried_connection.toon", "connection:\n  id: carried\n  connector: sftp\n");
        BundleImporter.Bundle in = BundleImporter.parse(zip(files));

        BundleImporter.MissingConnections r = BundleImporter.disableForMissingConnections(in, java.util.Set.of("registered"));

        Map<String, byte[]> out = r.bundle().configEntries();
        assertEquals("false", String.valueOf(toMap(out.get("a_pipeline.toon")).get("active")));
        assertEquals("false", String.valueOf(toMap(out.get("b_pipeline.toon")).get("active")));
        assertEquals("false", String.valueOf(((Map<?, ?>) toMap(out.get("x_job.toon")).get("job")).get("enabled")));
        for (String kept : List.of("c_pipeline.toon", "d_pipeline.toon", "carried_connection.toon"))
            assertArrayEquals(in.configEntries().get(kept), out.get(kept), kept + " is untouched");

        assertEquals(List.of("gone", "other_gone"), r.warnings().stream().map(w -> w.get("connection")).toList());
        assertEquals(List.of(Map.of("kind", "pipeline", "name", "a_etl", "file", "a_pipeline.toon"),
                        Map.of("kind", "pipeline", "name", "b_etl", "file", "b_pipeline.toon")),
                r.warnings().get(0).get("disabled"));
        assertEquals(List.of(Map.of("kind", "job", "name", "X", "file", "x_job.toon")),
                r.warnings().get(1).get("disabled"));
    }

    private static Map<String, Object> toMap(byte[] toon) {
        return com.gamma.config.io.ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
    }

    @Test
    void writeConfigUnpacksEntriesUnderConfigDir(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "voucher/voucher_pipeline.toon", "name: VOUCHER_ETL\n",
                "voucher/voucher_schema.toon", "raw:\n"));

        List<String> written = BundleImporter.writeConfig(BundleImporter.parse(zip), config).paths();
        assertTrue(written.contains("voucher/voucher_pipeline.toon"));
        assertEquals("name: VOUCHER_ETL\n",
                Files.readString(config.resolve("voucher/voucher_pipeline.toon")), "bytes unpacked verbatim");
        assertTrue(Files.exists(config.resolve("voucher/voucher_schema.toon")));
    }

    @Test
    void writeConfigRejectsZipSlipEntries(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config");
        Files.createDirectories(config);
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "../evil.toon", "name: EVIL\n"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> BundleImporter.writeConfig(BundleImporter.parse(zip), config));
        assertTrue(ex.getMessage().contains("escapes"), ex.getMessage());
        assertFalse(Files.exists(tmp.resolve("evil.toon")), "nothing written outside the config dir");
    }

    /**
     * {@code DATA-DIRS-RESOLVE-AGAINST-CWD-1}: a config's data paths are Space-relative and its config refs
     * sibling names, so an imported config means the TARGET space's files with its bytes untouched. The
     * retired "space rebasing" rewrote {@code spaces/alpha/...} prefixes; nothing may rewrite anything now.
     */
    @Test
    void configBytesTravelVerbatim(@TempDir Path tmp) throws Exception {
        String pipeline = """
                name: orders
                dirs:
                  poll:     data/inbox/orders
                  database: data/orders/database
                processing:
                  schema_file: orders_schema.toon
                """;
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\nsource_space: alpha\n",
                "orders/orders_pipeline.toon", pipeline,
                "orders/orders_schema.toon", "raw:\n  name: ORDERS\n"));
        Path config = tmp.resolve("beta").resolve("config");

        BundleImporter.Unpacked out = BundleImporter.writeConfig(BundleImporter.parse(zip), config);

        assertEquals(pipeline, Files.readString(config.resolve("orders/orders_pipeline.toon")));
        assertEquals(List.of("orders/orders_pipeline.toon", "orders/orders_schema.toon"),
                out.paths().stream().sorted().toList());
    }

    /**
     * W5 forward closure: a carried Reference the target already hosts is dropped — only the entries it alone
     * brought, never one another carried Reference still needs — and named as kept. One the target lacks, and
     * a bundle with no {@code references} index, pass through untouched.
     */
    @Test
    void keepExistingReferencesDropsOnlyWhatAHostedReferenceAloneBrought() throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kind", "datasource");
        manifest.put(BundleExporter.REFERENCES, Map.of(
                "region_dim", List.of("region_pipeline.toon", "dims_connection.toon"),
                "country_dim", List.of("country_pipeline.toon", "dims_connection.toon")));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("bundle.toon", com.gamma.config.io.ConfigCodec.toToon(manifest));
        entries.put("orders_pipeline.toon", "name: ORDERS_ETL\n");
        entries.put("region_pipeline.toon", "name: REGION_DIM\n");
        entries.put("country_pipeline.toon", "name: COUNTRY_DIM\n");
        entries.put("dims_connection.toon", "connection:\n  id: DIMS\n");
        BundleImporter.Bundle bundle = BundleImporter.parse(zip(entries));

        BundleImporter.Narrowed n = BundleImporter.keepExistingReferences(bundle, java.util.Set.of("region_dim"));
        assertEquals(List.of("region_dim"), n.referencesKept());
        assertEquals(List.of("orders_pipeline.toon", "country_pipeline.toon", "dims_connection.toon"),
                List.copyOf(n.bundle().configEntries().keySet()),
                "region_dim's pipeline is dropped; the connection country_dim also needs stays");
        assertEquals(List.of("orders_etl", "country_dim"), BundleImporter.pipelineIds(n.bundle()),
                "so the kept Reference is no longer a conflict candidate");

        BundleImporter.Narrowed none = BundleImporter.keepExistingReferences(bundle, java.util.Set.of("orders_etl"));
        assertTrue(none.referencesKept().isEmpty());
        assertSame(bundle, none.bundle(), "a target hosting none of the References changes nothing");

        BundleImporter.Bundle legacy = BundleImporter.parse(zip(Map.of(
                "bundle.toon", "kind: datasource\n", "orders_pipeline.toon", "name: ORDERS_ETL\n")));
        assertSame(legacy, BundleImporter.keepExistingReferences(legacy, java.util.Set.of("region_dim")).bundle(),
                "a bundle with no references index is returned unchanged");
    }

    @Test
    void parseRejectsAZipWithoutAManifest() throws Exception {
        byte[] zip = zip(Map.of("a_pipeline.toon", "name: A\n"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> BundleImporter.parse(zip));
        assertTrue(ex.getMessage().contains("bundle.toon"), ex.getMessage());
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : new LinkedHashMap<>(entries).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }
}
