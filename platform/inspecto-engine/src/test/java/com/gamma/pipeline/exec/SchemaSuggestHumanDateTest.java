package com.gamma.pipeline.exec;

import com.gamma.etl.DataTransformer;
import com.gamma.etl.Identifiers;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builder-pilot gap (2026-09-25): a CSV column {@code date} holding {@code "March 22,2025"} was
 * suggested as VARCHAR, so the landed Dataset had no temporal column. The suggestion now recognises
 * human date spellings AND emits the strptime {@code format} the engine needs to land them as DATE —
 * a DATE type alone would compile to the pipeline's {@code date_formats} list and NULL every value.
 */
class SchemaSuggestHumanDateTest {

    private static Field inferOne(String... values) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String v : values) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("d", v);
            rows.add(r);
        }
        SchemaSuggest.Field f = SchemaSuggest.infer(rows).get(0);
        return new Field(f.type(), f.format());
    }

    private record Field(String type, String format) {}

    @Test
    void humanSpellingsVoteDateWithTheFormatTheyWereProvenWith() throws Exception {
        assertEquals(new Field("DATE", "%B %d,%Y"), inferOne("March 22,2025", "March 23,2025", "April 1,2025"));
        assertEquals(new Field("DATE", "%B %d, %Y"), inferOne("March 22, 2025", "april 1, 2025"));
        assertEquals(new Field("DATE", "%d %B %Y"), inferOne("22 March 2025", "1 April 2025"));
        assertEquals(new Field("DATE", "%b %d %Y"), inferOne("Mar 22 2025", "Apr 1 2025"));
        assertEquals(new Field("DATE", "%Y-%m-%d"), inferOne("2025-03-22", "2025-04-01"),
                "ISO pins its format too, so it lands whatever the pipeline's date_formats say");
    }

    /** DD/MM vs MM/DD: only a sample that settles it gets a format — ambiguity stays VARCHAR, never a guess. */
    @Test
    void slashDatesNeedAnUnambiguousSample() throws Exception {
        assertEquals(new Field("VARCHAR", null), inferOne("03/04/2025", "05/06/2025"),
                "every value reads both ways as a different day — refuse to guess");
        assertEquals(new Field("DATE", "%d/%m/%Y"), inferOne("03/04/2025", "13/04/2025"));
        assertEquals(new Field("DATE", "%m/%d/%Y"), inferOne("03/04/2025", "04/13/2025"));
        assertEquals(new Field("DATE", "%d/%m/%Y"), inferOne("05/05/2025"),
                "both readings agree on every value — not ambiguous");
    }

    /** A mixed column fits no single format: it stays text rather than landing half-NULL. */
    @Test
    void aColumnNoSingleFormatCoversStaysVarchar() throws Exception {
        assertEquals(new Field("VARCHAR", null), inferOne("March 22,2025", "22 March 2025"));
        assertEquals(new Field("VARCHAR", null), inferOne("March 22,2025", "not a date"));
    }

    /**
     * End to end: a real CSV (the pilot's quoted {@code "March 22,2025"}) → the all-VARCHAR raw relation
     * the parse step produces → the suggestion → the draft {@code raw.fields}/{@code mapping.fields} the
     * route emits → config-load validation → the Record Transformer projection the graph executor runs
     * ({@link DataTransformer#dataColumns}, the {@code RowShaper} path) under a pipeline whose own
     * {@code date_formats} is ISO-only. The column must land as DATE values, not NULLs.
     */
    @Test
    void theSuggestedFieldLandsAsDateValues(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("orders.csv");
        Files.writeString(csv, "id,date\n1,\"March 22,2025\"\n2,\"March 23,2025\"\n3,\"April 1,2025\"\n");

        File db = DuckDbUtil.tempDbFile("suggest_land_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE raw_input AS SELECT *, 0 AS __src_id FROM read_csv('"
                    + csv.toString().replace("\\", "/") + "', header=true, all_varchar=true, auto_detect=false,"
                    + " columns={'id':'VARCHAR','date':'VARCHAR'})");
            List<Map<String, Object>> sample = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT id, date FROM raw_input ORDER BY id")) {
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", rs.getString(1));
                    r.put("date", rs.getString(2));
                    sample.add(r);
                }
            }

            // The draft exactly as POST /config/suggest/schema shapes it.
            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> mapping = new ArrayList<>();
            for (SchemaSuggest.Field f : SchemaSuggest.infer(sample)) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", f.name());
                field.put("selector", f.name());
                field.put("type", f.type());
                if (f.format() != null) field.put("format", f.format());
                fields.add(field);
                mapping.add(Map.of("name", f.name(), "from", f.name(), "fn", "keep"));
            }
            assertEquals(Map.of("name", "date", "selector", "date", "type", "DATE", "format", "%B %d,%Y"),
                    fields.get(1));
            Map<String, Object> schema = Map.of("raw", Map.of("fields", fields),
                    "mapping", Map.of("fields", mapping));
            Identifiers.validateSchema(schema, "test");   // the config-load gate accepts the draft

            List<Map<String, Object>> cols = DataTransformer.dataColumns(schema,
                    PipelineConfig.CsvSettings.ofFormats(List.of("%Y-%m-%d"), List.of()), "raw_input");
            String select = "SELECT " + cols.get(1).get("expr") + " AS d FROM raw_input ORDER BY id";
            List<LocalDate> landed = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(select)) {
                ResultSetMetaData md = rs.getMetaData();
                assertEquals("DATE", md.getColumnTypeName(1));
                while (rs.next()) landed.add(rs.getObject(1, LocalDate.class));
            }
            assertEquals(List.of(LocalDate.of(2025, 3, 22), LocalDate.of(2025, 3, 23), LocalDate.of(2025, 4, 1)),
                    landed);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
