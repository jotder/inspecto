package com.gamma.skybase.decoder.asn2;

import com.gamma.skybase.transformer2.Transformer;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.skybase.decoder.asn2.utils.FileUtils.findFiles;

//import org.junit.jupiter.api.Test;
//import static org.junit.jupiter.api.Assertions.assertNotNull;
//import static org.junit.jupiter.api.Assertions.assertTrue;

class ZainHuwIMS {

    public static void main(String args[]) throws Exception {
        String srcDir = "data/zain/sudan/ims/";
        String fileNamePattern = "";
        String dataDefFile = "config\\zain\\sudan\\ims\\huwIMS.asn";
        String jsonPath =    "config\\zain\\sudan\\ims\\zain_ims_tx2.json";

        DataDef conf = new ASNConf(dataDefFile, "IMSRecord", 12);
        Transformer tfm = new Transformer(jsonPath);

        Map<String, Object> headerInfo = new LinkedHashMap<>();
        headerInfo.put("HEADER_LENGTH", 50L);
        headerInfo.put("RECORD_HEADER_LENGTH", 4L);
        headerInfo.put("RECORD_PADDING", "");

        List<Path> pathList = findFiles(srcDir, "", fileNamePattern, 1);
//        assertTrue(pathList.size() > 0, "No files found to process");

        for (Path path : pathList) {
            System.out.println("\n\n===========\nReading " + path);
            try (InputStream inputStream = Files.newInputStream(path)) {
                ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
                List<Map<String, Object>> records = new ArrayList<>();
                while (reader.hasNext()) {
                    try {
                        Map<String, Object> parsedDataNodes = reader.next();
//                        assertNotNull(parsedDataNodes, "Parsed data nodes should not be null");
                        String key = parsedDataNodes.keySet().stream().findFirst().orElse("");
//                        Map<String, Object> o = (Map<String, Object>) parsedDataNodes.get(key);

//                        List<Map<String, Object>> txRec = tfm.transform(Pair.of(key, o));
//                        List<Map<String, Object>> txRec = tfm.transform(parsedDataNodes);
//                        assertNotNull(txRec, "Transformed records should not be null");
//                        records.addAll(txRec);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
//                System.out.println("Wrote " + reader.getRecCount() + " lines");
//                assertTrue(reader.getRecCount() > 0, "No records processed from file: " + path);
            }
        }
    }
}
