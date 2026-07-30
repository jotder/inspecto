package com.gamma.skybase.decoder.asn2;

import com.gamma.skybase.transformer2.Transformer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

class RTDMS_ASN_Test {

    static Map<String, Object> headerInfo = new LinkedHashMap<>();
    static String srcDir, fileNamePattern = "[A-Za-z0-9_]+", defFil, txConf;

    public static void main(String[] args) throws Exception {
        headerInfo.put("FILE_HEADER", "");
        headerInfo.put("RECORD_HEADER", "");
        headerInfo.put("RECORD_PADDING", "");

        // testMTNAhuwMsc();
        // testMTNAhuwIMS();
        // testAftelIMS();
        // testMtnaCCN();
        // testMtnaSDP();
//        testMtnaOCC();
        // testMtnaOCCGprs();
        // todo
        // testMtnaOCS();
//         testMtnaGSN();
        pgwParse();
    }

    private static void testMTNAhuwMsc() throws IOException {
        srcDir = "/home/gamma/Documents/Gamma/File/RTDMS/MTN/huwMsc/PPGMSC01_AP1_20251126151515_00617091.dat";
        // srcDir =
        // "/home/gamma/Documents/Gamma/File/RTDMS/MTN/msc/huwMsc/PPGMSC01_AP1_20251106152012_00588576.dat";

        defFil = "config/rtdms/mtna/huwMsc/2980-gmsc.asn"; // huwMsc.asn;
        DataDef conf = new ASNConf(defFil, "CallEventDataFile", 17);
        // String template = conf.getTxTemplate();
        // System.out.println(template);

        txConf = "config/rtdms/mtna/huwMsc/huwMsc.json";
        Transformer tfm = new Transformer(txConf);

        TestASNFiles.parseGMSC(conf, headerInfo, tfm, srcDir);
    }

    private static void testMTNAhuwIMS() throws IOException {
        srcDir = "/home/gamma/Documents/Gamma/File/RTDMS/MTN/ims/PPCCF01_AP1_20251124153458_03927853.dat";
        defFil = "config/rtdms/mtna/huwIMS/huwIMS.asn";
        txConf = "config/rtdms/mtna/huwIMS/huwIMS.json";
        DataDef conf = new ASNConf(defFil, "IMSRecord", 12);
        Transformer tfm = new Transformer(txConf);

        TestASNFiles.mtnHuwImsParse(conf, headerInfo, tfm, srcDir);
    }

    private static void testAftelIMS() throws IOException {
        srcDir = "/home/gamma/Documents/Gamma/File/RTDMS/AFTEL/ims/KABUL2025112352683.dat";

        defFil = "config/rtdms/aftel/ims/aftelIMS.asn";
        DataDef conf = new ASNConf(defFil, "IMSRecord", 12);

        conf.getTxTemplate();

        txConf = "config/rtdms/aftel/ims/ims_tx_new.json";
        Transformer tfm = new Transformer(txConf);

        TestASNFiles.aftelIMSParse(conf, headerInfo, tfm, srcDir);
    }

    private static void testMtnaCCN() throws IOException {
        srcDir = "/home/gamma/Documents/Gamma/File/RTDMS/MTN/ccnVoiceSms/CDRCCN_4004_150331-0539-CCN_58-251102-0802-256817";
        // srcDir =
        // "/home/gamma/Documents/Gamma/File/RTDMS/MTN/occGprs/CDRCCN_4004_150331-0539-OCC1-250818-0000-33561.ber";
        fileNamePattern = "";
        defFil = "config/rtdms/mtna/ccn/mtnCCN.asn";
        DataDef conf = new ASNConf(defFil, "ChargingDataOutputRecord", 11);

        // String template = conf.getTxTemplate();
        // System.out.println(template);

        txConf = "config/rtdms/mtna/ccn/ccn_tx.json";
        Transformer tfm = new Transformer(txConf);
        TestASNFiles.mtnCCNParse(conf, headerInfo, tfm, srcDir);
    }

    @SuppressWarnings("unused")
    private static void testMtnaOCCGprs() throws IOException {
        srcDir = "/home/gamma/Documents/Gamma/File/RTDMS/MTN/occGprs/CDRCCN_4004_150331-0539-OCC1-250818-0000-33561.ber";
        defFil = "config/rtdms/mtna/ccn/mtnCCN.asn";
        DataDef conf = new ASNConf(defFil, "ChargingDataOutputRecord", 11);
        txConf = "config/rtdms/mtna/ccn/ccn_gprs_tx.json";
        Transformer tfm = new Transformer(txConf);

        TestASNFiles.mtnCCNParse(conf, headerInfo, tfm, srcDir);
    }

    private static void testMtnaOCC() throws IOException {
        srcDir = "data/rtdms/mtna/INexamples/OCC";
//        srcDir = "data/rtdms/mtna/INexamples/OCC/New folder";
        defFil = "config/rtdms/mtna/occ/mtnOCC.asn";
        txConf = "config/rtdms/mtna/occ/mtn_occ_tx.json";
        fileNamePattern = "";

        DataDef conf = new ASNConf(defFil, "ChargingDataOutputRecord", 11);
        Transformer tfm = new Transformer(txConf);
        for (File f : Objects.requireNonNull(Paths.get(srcDir).toFile().listFiles())) {
            if (!f.isDirectory())
                TestASNFiles.mtnOCCParse(conf, headerInfo, tfm, f.getAbsolutePath());
        }
    }

    private static void pgwParse() throws IOException {
        String defFil = "/home/gamma/Documents/Gamma/Work/asn-decoders/asn-parser-v2/config/zain/sudan/pgw/huwSgsn.asn";
        DataDef conf = new ASNConf(defFil, "CallEventRecord", 5);
        String fileName = "/home/gamma/Documents/Gamma/File/NorthSudan/pgw/KTNvUGW64_20260620045204_15808.dat";
        String template = conf.getTxTemplate();
        TestASNFiles.PGW(conf, headerInfo,fileName );
    }


    private static void testMtnaGSN() throws IOException {
        srcDir = "data/rtdms/mtna/gsn";
        // srcDir =
        // "/home/gamma/Documents/Gamma/File/RTDMS/MTN/msc/huwMsc/PPGMSC01_AP1_20251106152012_00588576.dat";

        defFil = "config/rtdms/mtna/gsn/atoma_eric.asn"; // "; // mtn_ggsn.asn;
        DataDef conf = new ASNConf(defFil, "GPRSCallEventRecord", 104); // "CallEventRecord", 5
        String template = conf.getTxTemplate();
        System.out.println(template);

        txConf = "config/rtdms/mtna/gsn/atoma_eric.json";
        Transformer tfm = new Transformer(txConf);
        for (File f : Objects.requireNonNull(Paths.get(srcDir).toFile().listFiles())) {
            if (!f.isDirectory())
                TestASNFiles.mtnGprsParse(conf, headerInfo, tfm, f.getAbsolutePath());
        }
    }

    private static void testMtnaSDP() throws IOException {
        srcDir = "data/rtdms/mtna/IN examples/SDP";
        fileNamePattern = "^SDPOUTPUTCDR_\\d+_[a-zA-Z0-9]+_ADM_\\d+_\\d+-\\d+\\.ASN\\.backup$";
        defFil = "config/rtdms/mtna/sdp/sdp.asn";
        txConf = "config/rtdms/mtna/sdp/sdp_tx.json";
        DataDef conf = new ASNConf(defFil, "SDPCallDataRecord", 11);
        conf.getTxTemplate();
        // Transformer tfm = new Transformer(txConf);
        // TestASNFiles.parse(conf, headerInfo, tfm, srcDir, fileNamePattern, true);
    }

}
