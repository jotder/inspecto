package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.util.ColumnClassification;
import org.junit.jupiter.api.Test;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEMPLATE-CONFIDENTIAL-UNMASKED-1: {@code CONFIDENTIAL} is in neither masking vocabulary
 * ({@link ColumnClassification#SENSITIVE}, or the classes a masked Entity Type claims), so a personal identifier
 * classed that way is masked nowhere (the defect {@code TelcoFraudTemplateGoldenTest} already pins for the
 * telco-fraud feeds). Pins the identifier columns of the shipped {@code aml}, {@code mobile-money} and
 * {@code telco-fraud} schemas to a {@code SENSITIVE} class. {@code ACCOUNT} and {@code MSISDN} mask everywhere;
 * {@code PII} ({@code id_doc}) masks Risk Score evidence but not a BI result in the default {@code typed} mode
 * (CLASSIFICATION-UNMASKED-CLASS-1).
 */
class TemplateIdentifierClassificationTest {

    private static final Path TEMPLATES = Path.of("..", "spaces", "_templates").toAbsolutePath().normalize();

    /** Schema file, then the identifier columns it must class as masked. */
    private static final String[][] IDENTIFIERS = {
            {"aml/config/aml_parties/aml_parties_schema.toon", "party_id"},
            {"aml/config/aml_txn/aml_txn_schema.toon", "account_id", "counterparty_id"},
            {"mobile-money/config/core_subscribers/core_subscribers_schema.toon", "msisdn"},
            {"mobile-money/config/wallet_txn/wallet_txn_schema.toon", "wallet_id"},
            {"mobile-money/config/wallets/wallets_schema.toon", "wallet_id", "msisdn"},
            {"telco-fraud/config/subscriber_events/subscriber_events_schema.toon", "id_doc"},
    };

    @Test
    void everyIdentifierColumnHasAMaskedClassification() throws Exception {
        for (String[] f : IDENTIFIERS) {
            List<String> lines = Files.readAllLines(TEMPLATES.resolve(f[0]));
            for (int i = 1; i < f.length; i++) {
                String col = f[i];
                List<String> rows = lines.stream().map(String::strip).filter(l -> l.startsWith(col + ",")).toList();
                assertEquals(1, rows.size(), f[0] + " " + col + " field rows");
                String row = rows.get(0);
                String cls = row.substring(row.lastIndexOf(',') + 1).replace("\"", "");
                assertTrue(ColumnClassification.SENSITIVE.contains(cls),
                        f[0] + " " + col + " is classified " + cls + ", which is not masked");
            }
        }
    }

    /**
     * CLASSIFICATION-UNMASKED-CLASS-1: no shipped schema or Dataset states a class its own template does not know
     * ({@link ClassificationVocabulary}, judged against the Entity Types of that template's {@code link-analysis.toon}),
     * so no template column is classed with a word that masks nothing, and every template passes the save gate.
     */
    @Test
    void noTemplateSchemaOrDatasetStatesAClassItsTemplateDoesNotKnow() throws Exception {
        List<String> unknown = new ArrayList<>();
        int read = 0;
        try (DirectoryStream<Path> templates = Files.newDirectoryStream(TEMPLATES, Files::isDirectory)) {
            for (Path template : templates) {
                Path config = template.resolve("config");
                if (!Files.isDirectory(config)) continue;
                List<Path> files;
                try (Stream<Path> walk = Files.walk(config)) {
                    files = walk.filter(p -> {
                        String rel = config.relativize(p).toString().replace('\\', '/');
                        return rel.endsWith("_schema.toon") || (rel.startsWith("registry/datasets/") && rel.endsWith(".toon"));
                    }).sorted().toList();
                }
                for (Path file : files) {
                    read++;
                    try {
                        // no type or id: nothing counts as already stored, so the file's own classes are all judged
                        ClassificationVocabulary.require(config, null, null, ConfigLoader.filesystem().decode(file.toString()));
                    } catch (IllegalArgumentException refused) {
                        unknown.add(TEMPLATES.relativize(file) + ": " + refused.getMessage());
                    }
                }
            }
        }
        assertTrue(read > 400, "the sweep found the shipped schemas and Datasets: " + read);
        assertTrue(unknown.isEmpty(), String.join("\n", unknown));
    }
}
