package com.gamma.skybase.decoder.asn2;

import com.gamma.skybase.transformer2.Transformer;
import com.gamma.skybase.utils.Pair;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.skybase.decoder.asn2.utils.FileUtils.findFiles;

public class NRTRDE {


    public static void main(String[] args) throws Exception {

        String srcDir = "C:\\data\\ssd\\nrtrde";
        String fileNamePattern = "[A-Za-z0-9]+";
        String dataDefFile = "./config/nrtrde/nrtrde_2.1.asn";
        String jsonPath = "./config/nrtrde/nrtrde_tx.json";

        DataDef conf = new ASNConf(dataDefFile, "Nrtrde",  24);
        Transformer tfm = new Transformer(jsonPath);

        Map<String, Object> headerInfo = new LinkedHashMap<>();
        headerInfo.put("FILE_HEADER", "");
        headerInfo.put("RECORD_HEADER", "");
        headerInfo.put("RECORD_PADDING", "");

        List<Path> pathList = findFiles(srcDir, "", fileNamePattern, 1);
        for (Path path : pathList) {
                if (path.toString().endsWith(".doc")) continue;
                if (!path.toString().endsWith(".dat")) continue;
            System.out.println("\n\n===========\nReading " + path);
            try (InputStream inputStream = Files.newInputStream(path.toFile().toPath())) {

                ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo); // Replace with your file path
                long count = 0;
                List<Map<String, Object>> records = new ArrayList<>();
                while (reader.hasNext()) {
                    try {
//                        System.out.println("----------------------------------------------------");

                        Map<String, Object> parsedDataNodes = reader.next();
                        String key = parsedDataNodes.keySet().stream().findFirst().orElse("");
                        Map<String, Object> value = (Map<String, Object>) parsedDataNodes.get(key);

//                        List<Map<String, Object>> txRec = tfm.transform(Pair.of("", value));

                        count++;
//                        System.out.println(count + "->" + parsedDataNodes.size());
                    } catch (Exception e) {
                        e.printStackTrace();
                        count++;
                    }
                }
                System.out.println("Wrote " + reader.getRecCount()+ " lines");
            }
        }
    }
}

