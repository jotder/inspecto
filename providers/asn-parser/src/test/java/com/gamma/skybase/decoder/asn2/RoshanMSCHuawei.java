package com.gamma.skybase.decoder.asn2;

import com.gamma.skybase.transformer2.Transformer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.skybase.decoder.asn2.utils.FileUtils.findFiles;


public class RoshanMSCHuawei {

    public static String readStringFromFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8); // or any other appropriate charset
    }

    public static void main(String[] args) throws Exception {

        String srcDir = ".\\data\\mcit\\roshan\\MSC\\HUAWEI\\KBL144MSS_20230615143229_07579615_15062023_1432_26691.dat";
        String fileNamePattern = "[A-Za-z0-9]+.dat";
        String csvPath = "./config/roshan_msc_huawei/roshan_msc_huawei.csv";
        String jsonPath = "./config/roshan_msc_huawei/roshan_msc_huawei.json";

        Map<String, Object> fileStruct = new LinkedHashMap<>();
        fileStruct.put("FILE_HEADER","" );
        fileStruct.put("RECORD_HEADER","" );
        fileStruct.put("RECORD_PADDING","" );

        DataDef conf = new CSVConf(csvPath);
        Transformer tfm = new Transformer(jsonPath);

//        conf.setConfig("FILE_HEADER", FILE_HEADER);
//        conf.setConfig("RECORD_HEADER", RECORD_HEADER);
//        conf.setFlattenConfig(config);
//        CSVConf txConf = conf.setFlatConfig(flattenDEF);
//        List<Path> pathList = Utils.findFiles(srcDir, "", p, 1);

        List<Path> pathList = findFiles(srcDir, "", fileNamePattern, 4);
        for (Path path : pathList) {
            try {
//                if (path.toString().endsWith(".doc")) continue;
//                if (!path.toString().endsWith(".dat")) continue;

                System.out.println("\nReading " + path);
                InputStream inputStream = Files.newInputStream(path.toFile().toPath());

                ASN1Reader reader = new ASN1Reader(inputStream, conf, fileStruct); // Replace with your file path
                long count = 0;
                List<Map<String, Object>> records = new ArrayList<>();
                while (reader.hasNext()) {
                    try {
                        Map<String, Object> parsedDataNodes = reader.next();
//                        Map<String, Object> namedDataNodes = reader.getNamedData(parsedDataNodes);
                        records.add(parsedDataNodes);

//                        Map<String, List<Object>> namedSubRecordNodes = tfm.getSubRecordNodes(parsedDataNodes);

                        parsedDataNodes.forEach((k, dataNodes) -> {
                            if (dataNodes instanceof Map) {
                                Map<String, Object> dataNode = (Map<String, Object>) dataNodes;
                                String key = parsedDataNodes.keySet().stream().findFirst().orElse("");
                                Map<String, Object> txRecList = (Map<String, Object>) parsedDataNodes.get(key);

//                                List<Map<String, Object>> txRecList = tfm.transform(Pair.of(key, value));

//                                flattenedDataNode.forEach(e -> {
//                                    System.out.println(e);
//                                });

//                                flattenedDataNode.forEach(dataNode -> {
//                                    Map<String, Object> dataNode1 = (Map<String, Object>) dataNode;
//                                    Map<String, Object> namedDataNode = tfm.getNamedData(parsedDataNodes);
//                                    Map<String, List<Object>> fSubRecord = tfm.getSubRecordNodes(parsedDataNodes);
//                                });

//                                System.out.println(flattenedDataNode);
                            }
                        });
//                        List<Map<String, Object>> result = tfm.getFlattenRecords(parsedDataNodes);

                    } catch (Exception e) {
                        e.printStackTrace();
                        count++;
                    }
                }
                System.out.println("Wrote " + reader.getRecCount() + " lines");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}

