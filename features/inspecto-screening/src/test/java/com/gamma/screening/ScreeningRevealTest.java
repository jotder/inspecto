package com.gamma.screening;

import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SCREENING-1-D3: the raw subject is read again from the Dataset the Job screened; a list entry unmasks to its member. */
class ScreeningRevealTest {

    @Test
    void readsTheRawNameAndIdentifierOfOneSubjectFromItsDataset(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config"), data = dir.resolve("data");
        new ComponentStore(cfg.resolve("registry")).write("dataset", "customers", Map.of("physicalRef", "customers"));
        Path d = data.resolve("customers");
        Files.createDirectories(d);
        String file = d.resolve("data.parquet").toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('c1', 'Putin, Vladimir', 'NID-1'), ('c2', 'Jane Doe', 'NID-2'))"
                    + " AS t(customer_id, full_name, national_id)) TO '" + file + "' (FORMAT PARQUET)");
        }
        ScreeningJobType.Params p = ScreeningJobType.Params.of(Map.of("dataset", "customers", "keyField", "customer_id",
                "nameField", "full_name", "idField", "national_id", "lists", "sanctions"));
        Map<String, String> v = ScreeningJobType.ScreeningJob.subjectValues(cfg, data, p, "c1");
        assertEquals("Putin, Vladimir", v.get("subjectName"));
        assertEquals("NID-1", v.get("subjectIdentifier"));
        Map<String, String> gone = ScreeningJobType.ScreeningJob.subjectValues(cfg, data, p, "c404");
        assertNull(gone.get("subjectName"), "a row that no longer exists reveals nothing");
        ScreeningJobType.Params other = ScreeningJobType.Params.of(Map.of("dataset", "nope", "keyField", "k",
                "nameField", "n", "lists", "sanctions"));
        assertThrows(IllegalArgumentException.class, () -> ScreeningJobType.ScreeningJob.subjectValues(cfg, data, other, "c1"));
    }

    @Test
    void anUnmaskedListEntryIsItsOwnReveal(@TempDir Path cfg) throws Exception {
        ScreeningLists.unmasked(cfg);
        ScreeningLists.create(cfg, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(cfg, "sanctions", "default", List.of("Vladimir Putin"));
        Instant now = Instant.now();
        Screener.Prepared p = Screener.load(cfg, List.of("sanctions"), now).get(0);
        assertEquals("vladimir putin", Screener.unmaskEntry(p, "vladimir putin", now));
        assertNull(Screener.unmaskEntry(p, "someone else", now));
    }
}
