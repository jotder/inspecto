package com.gamma.la.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexManifestTest {

    static IndexManifest sample(IndexMapping mapping) {
        Map<String, IndexManifest.TableStats> tables = new LinkedHashMap<>();
        tables.put("edges", new IndexManifest.TableStats(100, 4, 4096));
        tables.put("nodes", new IndexManifest.TableStats(30, 1, 512));
        return new IndexManifest(7, "2026-10-02T10:00:00Z", IndexManifest.Builder.APPEND, "1.4.0", "hash_mod", 64, 122880,
                mapping, mapping.hash(), "ds-é 1", "rel-hash", "base-fp", "Asia/Kolkata", tables, 3,
                List.of(new IndexManifest.Delta("d000001", 10, 99)), "v000006");
    }

    static IndexMapping mapping() {
        return new IndexMapping("a_party", "b_party", "type", "ts", "Asia/Kolkata", "dur", List.of("cell", "imei"));
    }

    @Test
    void everyFieldRoundTrips(@TempDir Path tmp) throws Exception {
        IndexManifest m = sample(mapping());
        assertEquals(m, IndexManifest.fromJson(m.toJson()));
        m.write(tmp);
        assertEquals(m, IndexManifest.read(tmp));
    }

    @Test
    void nullableFieldsRoundTrip() {
        IndexMapping mp = new IndexMapping("s", "d", null, null, null, null, null);
        IndexManifest m = new IndexManifest(1, "x", IndexManifest.Builder.FULL, "1", "f", 1, 1, mp, mp.hash(), "d", "r", "b", "UTC", null, 0, null, null);
        IndexManifest back = IndexManifest.fromJson(m.toJson());
        assertEquals(m, back);
        assertNull(back.parent());
        assertNull(back.mapping().weightColumn());
        assertTrue(back.deltas().isEmpty());
    }

    @Test
    void unknownFieldsAreIgnoredButANewerFormatVersionIsRefused() {
        IndexManifest m = sample(mapping());
        String extra = m.toJson().replaceFirst("\\{", "{\"futureField\":{\"x\":1},");
        assertEquals(m, IndexManifest.fromJson(extra));
        String newer = m.toJson().replace("\"formatVersion\" : 1", "\"formatVersion\" : 2");
        assertThrows(IllegalArgumentException.class, () -> IndexManifest.fromJson(newer));
    }

    @Test
    void missingRequiredFieldAndMappingHashMismatchAreRefused() {
        String json = sample(mapping()).toJson();
        assertThrows(IllegalArgumentException.class, () -> IndexManifest.fromJson(json.replace("\"dataset\"", "\"datasetX\"")));
        assertThrows(IllegalArgumentException.class, () -> IndexManifest.fromJson(json.replace("\"srcColumn\" : \"a_party\"", "\"srcColumn\" : \"other\"")));
        assertThrows(IllegalArgumentException.class, () -> IndexManifest.fromJson("[]"));
        assertThrows(IllegalArgumentException.class, () -> IndexManifest.fromJson("not json"));
    }

    @Test
    void mappingHashIsDeterministicAndSensitive() {
        assertEquals(mapping().hash(), mapping().hash());
        assertEquals(16, mapping().hash().length());
        assertNotEquals(mapping().hash(), new IndexMapping("a_party", "b_party", "type", "ts", "Asia/Kolkata", "dur", List.of("imei", "cell")).hash());
        assertNotEquals(new IndexMapping("ab", "c", null, "t", null, null, null).hash(), new IndexMapping("a", "bc", null, "t", null, null, null).hash());
        assertNotEquals(new IndexMapping("a", "b", null, "t", null, null, null).hash(), new IndexMapping("a", "b", null, "t", null, "", null).hash());
        assertNotEquals(mapping().hash(), new IndexMapping("a_party", "b_party", "other", "ts", "Asia/Kolkata", "dur", List.of("cell", "imei")).hash());
        assertNotEquals(mapping().hash(), new IndexMapping("a_party", "b_party", "type", "ts", "UTC", "dur", List.of("cell", "imei")).hash(),
                "the time zone is part of the mapping");
    }
}
