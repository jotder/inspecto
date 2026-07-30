package com.gamma.skybase.decoder.asn2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.stream.Stream;

public class CSVConf extends DataDef {
    public CSVConf(String dataDefFile) throws IOException {
        super(dataDefFile, 24);
    }


    @Override
    String getTxTemplate() {
        return null;
    }

//    public Asn1Element getTagDefByName(String key) {
//        return tagNameConf.get(key);
//    }

    private void buildTagDefMap(String schemaAsCSV) {
        try (Stream<String> lines = new BufferedReader(new StringReader(schemaAsCSV)).lines()) {
            lines.skip(1)
                    .forEach(l -> {
                        String[] sa = l.split(",");
                        String aliases = null;
                        if (sa.length > 6) aliases = sa[5].trim();
                        String method = "";
                        if (sa.length > 5) method = sa[5].trim();
                        String format = "";
                        if (sa.length > 4) format = sa[4].trim();
                        String type = "long";
                        if (sa.length > 3) type = sa[3].trim();
                        if (sa.length > 2) {
                            String name = sa[2].trim();
                            String id = sa[1].trim();
                            String recType = sa[0].trim();
//                            TagConfig tcfg = new TagConfig(recType, id, name, type, format, method, aliases);
//                            tagNoConf.put(id, tcfg);
//                            tagNameConf.put(name, tcfg);
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
