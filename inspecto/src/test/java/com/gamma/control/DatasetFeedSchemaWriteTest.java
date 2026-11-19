package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.spi.auth.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} B5 (B16): the import schema's write path ({@link DatasetFeed#rewriteSchema}) gates the
 * edit like any schema save - the mapping drift and BACKWARD-compatibility gates refuse what the edit introduces, and a
 * refused edit writes nothing and keeps nothing in history. The kept-column path through it is driven over real HTTP in
 * {@code ControlApiArrivalNewColumnsTest}.
 */
class DatasetFeedSchemaWriteTest {

    private static Map<String, Object> field(String name, String selector) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("selector", selector);
        f.put("type", "VARCHAR");
        return f;
    }

    private static Map<String, Object> keep(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("from", name);
        m.put("fn", "keep");
        return m;
    }

    /** {@code <root>/imports/imp_schema.toon} reading columns a and b, both kept. */
    private static Path schema(Path root) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", new ArrayList<>(List.of(field("a", "0"), field("b", "1"))));
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("fields", new ArrayList<>(List.of(keep("a"), keep("b"))));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("raw", raw);
        s.put("mapping", mapping);
        Path file = root.resolve("imports").resolve("imp_schema.toon");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ConfigCodec.toToon(s), StandardCharsets.UTF_8);
        return file;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> s, String block) {
        Map<String, Object> b = new LinkedHashMap<>((Map<String, Object>) s.get(block));
        List<Object> fields = new ArrayList<>((List<Object>) b.get("fields"));
        b.put("fields", fields);
        s.put(block, b);
        return fields;
    }

    private static ApiException refused(Path root, Path schema, Consumer<Map<String, Object>> edit) {
        return assertThrows(ApiException.class, () -> DatasetFeed.rewriteSchema(root, "imp", schema,
                schema.resolveSibling("imp_pipeline.toon"), null, edit));
    }

    @Test
    void aMappingThatReadsAColumnTheSchemaLacksIsRefusedAndNothingIsWritten(@TempDir Path root) throws Exception {
        Path schema = schema(root);
        String before = Files.readString(schema);
        ApiException e = refused(root, schema, s -> list(s, "mapping").add(keep("fee")));
        assertEquals(422, e.status, e.getMessage());
        assertTrue(e.getMessage().startsWith("the import's schema was refused: "), e.getMessage());
        assertEquals(before, Files.readString(schema));
        assertFalse(Files.exists(PipelineHistory.schemaDirFor(root, "imp")), "a refused write keeps nothing in history");
    }

    @Test
    void anEditThatDropsAColumnIsRefusedAsNotBackwardCompatible(@TempDir Path root) throws Exception {
        Path schema = schema(root);
        String before = Files.readString(schema);
        ApiException e = refused(root, schema, s -> {
            list(s, "raw").remove(1);
            list(s, "mapping").remove(1);
        });
        assertEquals(422, e.status, e.getMessage());
        assertEquals(before, Files.readString(schema));
    }

    @Test
    void anUnreadableSchemaIsRefused(@TempDir Path root) throws Exception {
        Path schema = root.resolve("imports").resolve("imp_schema.toon");
        ApiException e = refused(root, schema, s -> { });
        assertEquals(422, e.status);
        assertEquals("the schema of 'imp' cannot be read", e.getMessage());
    }
}
