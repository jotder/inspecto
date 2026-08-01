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
     * W3, 2026-08-01. A pipeline's {@code dirs.*} and its schema reference name the space they were
     * authored in, so importing alpha's data source into another space produced a live pipeline polling,
     * writing and quarantining inside <em>alpha's</em> data plane — and reading alpha's schema file, which
     * still resolved because relative references fall back to the working directory. That is the whole
     * point of the check: the import looked completely successful.
     */
    @Test
    void spaceQualifiedPathsAreRebasedOntoTheTargetSpace(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("beta").resolve("config");
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\nsource_space: alpha\n",
                "orders/orders_pipeline.toon", """
                        name: orders
                        dirs:
                          poll:     spaces/alpha/data/inbox/orders
                          database: spaces/alpha/data/orders/database
                        processing:
                          schema_file: spaces/alpha/config/orders/orders_schema.toon
                        """,
                "orders/orders_schema.toon", "raw:\n  name: ORDERS\n"));

        BundleImporter.Unpacked out = BundleImporter.writeConfig(BundleImporter.parse(zip), config);

        String body = Files.readString(config.resolve("orders/orders_pipeline.toon"));
        assertFalse(body.contains("spaces/alpha/"), "no path may still point into the source space: " + body);
        String target = tmp.resolve("beta").toString().replace('\\', '/');
        assertTrue(body.contains(target + "/data/orders/database"), body);
        assertTrue(body.contains(target + "/config/orders/orders_schema.toon"),
                "the schema reference is rebased too — it resolved against the CWD and silently read alpha's");
        assertEquals(List.of("orders/orders_pipeline.toon"), out.rebased(), "and the rewrite is reported");
        assertTrue(body.startsWith("name: orders\n"),
                "a textual prefix swap, so the operator's own formatting and comments survive");
    }

    @Test
    void aBundleThatNamesNoSourceSpaceIsLeftVerbatim(@TempDir Path tmp) throws Exception {
        // Nothing to rebase FROM — guessing would be worse than leaving the paths alone.
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\n",
                "a_pipeline.toon", "name: A\ndirs:\n  poll: spaces/alpha/data/inbox/a\n"));

        BundleImporter.Unpacked out = BundleImporter.writeConfig(BundleImporter.parse(zip), tmp.resolve("config"));
        assertTrue(out.rebased().isEmpty());
        assertTrue(Files.readString(tmp.resolve("config/a_pipeline.toon")).contains("spaces/alpha/"));
    }

    @Test
    void configsWithNoSourceSpacePathsAreNotTouched(@TempDir Path tmp) throws Exception {
        byte[] zip = zip(Map.of(
                "bundle.toon", "kind: datasource\nsource_space: alpha\n",
                // An absolute, deliberately external inbox — a NAS mount is not the source space's, and
                // rebasing it would break a working collector.
                "a_pipeline.toon", "name: A\ndirs:\n  poll: /mnt/nas/cdr/inbox\n"));

        BundleImporter.Unpacked out = BundleImporter.writeConfig(BundleImporter.parse(zip), tmp.resolve("config"));
        assertTrue(out.rebased().isEmpty(), "nothing matched, so nothing was rewritten");
        assertTrue(Files.readString(tmp.resolve("config/a_pipeline.toon")).contains("/mnt/nas/cdr/inbox"));
    }

    @Test
    void rebaseTargetsPredictsTheRewriteWithoutWriting(@TempDir Path tmp) throws Exception {
        BundleImporter.Bundle b = BundleImporter.parse(zip(Map.of(
                "bundle.toon", "kind: datasource\nsource_space: alpha\n",
                "a_pipeline.toon", "name: A\ndirs:\n  poll: spaces/alpha/data/inbox/a\n",
                "b_schema.toon", "raw:\n  name: B\n")));
        Path config = tmp.resolve("config");

        assertEquals(List.of("a_pipeline.toon"), BundleImporter.rebaseTargets(b, config),
                "preview must name exactly what commit would rewrite");
        assertFalse(Files.exists(config), "and write nothing");
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
