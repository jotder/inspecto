package com.gamma.skybase.decoder.ascii;

import com.gamma.skybase.decoder.asn2.utils.FileUtils;
//import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

//import static org.junit.jupiter.api.Assertions.*;

class FixedLengthFileReaderTest {

    private static String readStringFromFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

//    @Test
    void testProcessFixedLengthFiles() throws Exception {
        String srcDir = "./data/tap_files"; // Using relative path
        Path csvPath = Paths.get("./config/tap/MBF_TAP.CSV");

//        assertTrue(Files.exists(csvPath), "Schema file not found: " + csvPath);

        Map<String, List<FieldConf>> schemas = loadSchemas(csvPath);
//        assertFalse(schemas.isEmpty(), "Schemas could not be loaded");

        List<Path> pathList = FileUtils.findFiles(srcDir, "", "[A-Za-z0-9]+", 1);
//        assertFalse(pathList.isEmpty(), "No files to process in " + srcDir);

        for (Path path : pathList) {
            if (path.toString().contains(".") || path.toFile().isDirectory()) {
                continue;
            }

            System.out.println("\nReading " + path);
            AtomicReference<Map<String, Object>> headerRecord = new AtomicReference<>(new LinkedHashMap<>());
            List<Map<String, Object>> recordList = new ArrayList<>();
            AtomicReference<Map<String, Object>> trailerRecord = new AtomicReference<>(new LinkedHashMap<>());

            try (Stream<String> lines = Files.lines(path)) {
                lines.forEach(line -> {
                    String recType = line.substring(0, 2);
                    Map<String, Object> record = null;
                    switch (recType) {
                        case "MO":
                        case "MT":
                            record = decodeRec(line, schemas.get("MOT"));
                            break;
                        case "GP":
                        case "WL":
                            record = decodeRec(line, schemas.get("PDR"));
                            break;
                        case "#@":
                            headerRecord.set(decodeHeader(line, schemas.get("#@")));
                            break;
                        case "TR":
                            trailerRecord.set(decodeRec(line, schemas.get("TR")));
                            break;
                        default:
                            System.out.println("Unknown record type: " + recType);
                    }
                    if (record != null) {
                        recordList.add(record);
                    }
                });
            }

//            assertNotNull(headerRecord.get(), "Header record should not be null");
//            assertFalse(recordList.isEmpty(), "No records were processed from file: " + path);
//            assertNotNull(trailerRecord.get(), "Trailer record should not be null");
        }
    }

    private Map<String, List<FieldConf>> loadSchemas(Path csvPath) throws IOException {
        Map<String, List<FieldConf>> schemas = new LinkedHashMap<>();
        String tagDEF = readStringFromFile(csvPath);
        try (Stream<String> lines = new BufferedReader(new StringReader(tagDEF)).lines()) {
            lines.skip(1).forEach(l -> {
                String[] sa = l.split(",");
                if (sa.length > 3) {
                    String recordType = sa[0].trim();
                    String name = sa[1].trim();
                    String pos = sa[2].trim();
                    String len = sa[3].trim();
                    String type = (sa.length > 4) ? sa[4].trim() : "string";
                    String method = (sa.length > 5) ? sa[5].trim() : null;

                    FieldConf tcfg = new FieldConf(recordType, name, pos, len, type, method);
                    schemas.computeIfAbsent(recordType, k -> new ArrayList<>()).add(tcfg);
                }
            });
        }
        return schemas;
    }

    private static Map<String, Object> decodeRec(String line, List<FieldConf> gp) {
        Map<String, Object> rec = new LinkedHashMap<>();
        if (gp == null) return rec;
        gp.forEach(e -> {
            try {
                String v = line.substring(e.getPos() - 1, e.getPos() + e.getLength() - 1);
                rec.put(e.getFieldName(), v.trim());
            } catch (Exception ex) {
                System.err.println("Error decoding field: " + e.getFieldName() + " -> " + ex.getMessage());
            }
        });
        return rec;
    }

    private static Map<String, Object> decodeHeader(String line, List<FieldConf> confs) {
        Map<String, Object> hr = new LinkedHashMap<>();
        if (confs == null) return hr;
        String[] fields = line.split(",");
        try {
            hr.put(confs.get(0).getFieldName(), fields[0].substring(2, 5));
            hr.put(confs.get(1).getFieldName(), fields[0].substring(fields[0].indexOf('=') + 1));
            hr.put(confs.get(2).getFieldName(), fields[1].substring(fields[1].indexOf('=') + 1));
            hr.put(confs.get(3).getFieldName(), fields[2].substring(fields[2].indexOf('=') + 1));
            hr.put(confs.get(4).getFieldName(), fields[3].substring(fields[3].indexOf('=') + 1));
            hr.put(confs.get(5).getFieldName(), fields[4].substring(fields[4].indexOf('=') + 1));
            hr.put(confs.get(6).getFieldName(), fields[5].substring(fields[5].indexOf('=') + 1));
            hr.put(confs.get(7).getFieldName(), fields[6].substring(fields[6].indexOf('=') + 1));
        } catch (Exception e) {
            System.err.println("Error decoding header: " + e.getMessage());
        }
        return hr;
    }

    private static class FieldConf {
        private final String id;
        private final String fieldName;
        private final int length;
        private final String dataType;
        private final String func;
        private final int pos;

        public FieldConf(String recordType, String fieldName, String pos, String length, String dataType, String func) {
            this.id = recordType;
            this.fieldName = fieldName;
            this.dataType = dataType;
            this.func = func;
            try {
                String trimmedPos = pos.trim();
                this.pos = trimmedPos.contains("-") ? Integer.parseInt(trimmedPos.substring(0, trimmedPos.indexOf("-"))) : Integer.parseInt(trimmedPos);
                this.length = Integer.parseInt(length.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid position or length value for field " + fieldName, e);
            }
        }

        public String getId() {
            return id;
        }

        public String getFieldName() {
            return fieldName;
        }

        public int getLength() {
            return length;
        }

        public String getDataType() {
            return dataType;
        }

        public String getFunc() {
            return func;
        }

        public int getPos() {
            return pos;
        }

        @Override
        public String toString() {
            return "FieldConf{" +
                    "id='" + id + '\'' +
                    ", fieldName='" + fieldName + '\'' +
                    ", length=" + length +
                    ", dataType='" + dataType + '\'' +
                    ", func='" + func + '\'' +
                    ", pos=" + pos +
                    '}';
        }
    }
}
