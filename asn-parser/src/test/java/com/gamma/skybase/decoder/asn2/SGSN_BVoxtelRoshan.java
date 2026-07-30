package com.gamma.skybase.decoder.asn2;

import com.gamma.skybase.decoder.asn2.utils.FileUtils;
import com.gamma.skybase.transformer2.Transformer;
import com.gamma.skybase.utils.Pair;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


class SGSN_BVoxtelRoshan {

    void testProcessSgsnFiles() throws Exception {
        String srcDir = ".\\data\\mcit\\roshan\\sgsn\\";
        String fileNamePattern = "[A-Za-z0-9]+";
        String dataDefFile = "./config/rtdms/sgsn_roshan/gsn.asn";
        String jsonPath = "./config/nrtrde/nrtrde_tx.json";

        DataDef conf = new ASNConf(dataDefFile, "CallEventRecord", 5);
//        conf.getTxTemplate();

        Transformer tfm = new Transformer(jsonPath);

        Map<String, Object> headerInfo = new LinkedHashMap<>();
        headerInfo.put("FILE_HEADER", "");
        headerInfo.put("RECORD_HEADER", "");
        headerInfo.put("RECORD_PADDING", "");

        List<Path> pathList = FileUtils.findFiles(srcDir, "", fileNamePattern, 1);

        for (Path path : pathList) {
            System.out.println("\n\n===========\nReading " + path);

            try (InputStream inputStream = Files.newInputStream(path)) {
                ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
                List<Map<String, Object>> records = new ArrayList<>();

                while (reader.hasNext()) {
                    try {
                        Map<String, Object> parsedDataNodes = reader.next();
                        records.add(parsedDataNodes);
                        String key = parsedDataNodes.keySet().stream().findFirst().orElse("");
                        Map<String, Object> value = (Map<String, Object>) parsedDataNodes.get(key);

//                        List<Map<String, Object>> txRec = tfm.transform(Pair.of(key, value));

//                        List<Map<String, Object>> txRec = tfm.transform(parsedDataNodes);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                Path tempJsonFile = Files.createTempFile(path.toFile().getName(), ".json");
                try (FileWriter writer = new FileWriter(tempJsonFile.toFile())) {
                    Gson gson = new GsonBuilder().setPrettyPrinting().create();
                    writer.write(gson.toJson(records));
                }
            }
        }
    }
}
