package com.gamma.la.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitystore.EntityTypes;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Entity profile counts Entities by the SQL mirror of {@link EntityTypes#normalise} (D-EP5). It runs the shared
 * {@code entity-normaliser-parity.fixture.json} - the file the Java and the browser normalisers are held to - through DuckDB,
 * so the profile's "one Entity" is the exploration's one Entity.
 */
class EntityKeySqlTest {

    private static final Path FIXTURE = Path.of("..", "..", "inspecto-ui", "src", "app", "inspecto", "graph",
            "entity-normaliser-parity.fixture.json");

    @Test
    void everySharedFixtureCaseFoldsAsTheJavaNormaliserDoes() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(FIXTURE)).get("cases");
        assertTrue(cases.size() > 10, "the fixture has cases");
        List<String> failures = new ArrayList<>();
        DuckDbUtil.loadDriver();
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            for (JsonNode k : cases) {
                String n = k.get("normaliser").asText(), in = k.get("input").asText();
                try (PreparedStatement ps = c.prepareStatement("SELECT " + EntityKeySql.key(n, "v") + " AS k FROM (SELECT CAST(? AS VARCHAR) AS v)")) {
                    ps.setString(1, in);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        String got = rs.getString(1), want = EntityTypes.normalise(n, in);
                        if (!want.equals(got)) failures.add(n + "('" + in + "') = '" + got + "', want '" + want + "'");
                    }
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theSafePrefixIsACountryCodeForE164AndNothingElse() {
        assertEquals(3, EntityKeySql.prefixLength("e164"));
        for (String n : List.of("digits", "upper-trim", "default")) assertEquals(0, EntityKeySql.prefixLength(n), n);
    }

    @Test
    void anUndeclaredNormaliserIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EntityKeySql.key("lower", "v"));
    }
}
