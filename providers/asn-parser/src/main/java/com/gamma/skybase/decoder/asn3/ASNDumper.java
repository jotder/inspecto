package com.gamma.skybase.decoder.asn3;

import com.gamma.skybase.decoder.asn2.ASNConf;
import com.gamma.skybase.decoder.asn2.DataDef;

import java.nio.file.Paths;
import java.util.Map;

public class ASNDumper {
    String srcDir = "./data/rtdms/mtna/INexamples/OCC/CDRCCN_4004_150331-0539-OCC2-260217-1349-68547.ber";
    Map<String, Object> headerInfo;
    Map<String, Object> fileStruct;
    DataDef conf;

    public static void main(String[] args) throws Exception {
        ASNDumper dumper = new ASNDumper();
        String defFil = "config/rtdms/mtna/gsn/atoma_eric.asn";
        DataDef conf = new ASNConf(defFil, "GPRSCallEventRecord", 104); // "CallEventRecord", 5
//        System.out.println(conf);

        dumper.parse();
    }

    void parse() throws Exception {

        ASNStreamReader reader = new ASNStreamReader(conf, headerInfo, new MappedFileSource(Paths.get(srcDir)));

//        byte[] data = Files.readAllBytes(Paths.get("tapfile.tap"));
//        reader = new ASNStreamReader(new ByteArraySource(data));

        while (reader.hasNext()) {
            TLVNode node = reader.readNextRecord();
//            System.out.println(node);
        }
    }

}
