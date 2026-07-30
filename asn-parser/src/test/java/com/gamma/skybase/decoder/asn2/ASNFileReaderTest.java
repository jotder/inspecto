package com.gamma.skybase.decoder.asn2;//package com.gamma.skybase.decoder.asn;
//
//import java.io.InputStream;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.LinkedHashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.atomic.AtomicLong;
//
//public class ASNFileReaderTest {
//
//
//    public static void main(String[] args) throws Exception {
//
//        String baseDir = "C:\\data\\Huawei_sudan\\";
//        String dataFilePath, dataDefFile, dataFileNamePat, txDefFile;
//
//        dataFileNamePat = ".*\\.dat$";
//        dataDefFile = "./config/sbin/msc_hua/msc_hua.asn";
//        txDefFile = "./config/sbin/msc_hua/msc_hua_tx.json";
////        dataFilePath = baseDir + "CloudEPC CDR\\CDR Samples\\pgwcdr\\Combined";
//        dataFilePath = "..\\generic-asn-reader\\data\\sbin";
//
////        dataFilePath = baseDir + "CloudEPC CDR\\CDR Samples\\pgwcdr\\uncombined";
////        dataFilePath = baseDir + "CloudEPC CDR\\CDR Samples\\sgwcdr\\Uncombined";
////        dataFilePath = baseDir + "CloudEPC CDR\\CDR Samples\\sgwcdr\\Uncombined";
////        dataFilePath = "C:\\data\\ssd\\data\\huawei-ims";
////        dataFilePath =
//
////        dataFilePath = "C:\\data\\Huawei_sudan\\GMSC(SE2980) offline CDR\\GMSC(SE2980) offline CDR Sample";
////        dataFilePath = baseDir + "GMSC(SE2980) offline CDR\\GMSC(SE2980) offline CDR Sample\\GWI";
////        dataFilePath = baseDir + "GMSC(SE2980) offline CDR\\GMSC(SE2980) offline CDR Sample\\GWO";
////        dataFilePath = baseDir + "GMSC(SE2980) offline CDR\\GMSC(SE2980) offline CDR Sample\\TRANSIT";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\01. Basic Call CDR";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\02. Basic Call - Cancel Event CDR";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\03. Basic Call - Callee Busy CDR";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\04. Video Call CDR";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\05. eSRVCC CDR";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\06. CFU";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\07. CFB";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\08. CFNRC";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\09. CFNR";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\10. Call Hold";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\11. CW";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\13. 3PTY";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\17. CRBT";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\20. Audio-Video Switchover";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\21. SMS service";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\22. CLIP";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\ATS\\VoLTE&VoWIFI&2G 3G\\23. CLIR";
////    XX  dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\E-CSCF\\Description of SBC CDRs";
////        dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\E-CSCF\\VoLTE&2G 3G\\ECSCF\\Emergency Calls-2G 3G";
////    XX  dataFilePath = baseDir + "IMS offline CDR\\IMS_CDR_Sample_en\\E-CSCF\\VoLTE&2G 3G\\ECSCF\\Emergency Calls-VoLTE";
////        dataFilePath = "C:\\data\\Huawei_sudan\\remscggsnhlrcdrstructureanddecoderfornewhuawei";
////        schemafilePath = "E:\\nifi-asn-service\\nifi-asn-service-controller\\gamma-asn-controller\\config\\ggsn.csv";
//
////        String dataFilePath = "C:\\data\\sudan\\new\\data\\ims\\analyze";
////        String dataFilePath = "C:\\data\\Huawei_sudan";
////        String dataFilePath = "C:\\data\\SBIN\\IMS\\IMS CDR Sample\\CDR example\\ATS\\VoBB(FMC)\\04. Excessively long call";
////        Path csvPath = Paths.get("./config/hua_ims/huawei_ims_ns.csv");
////        Path jsonPath = Paths.get("./config/hua_ims/huawei_ims.json");
//////      FILE_HEADER = "FIXED"; //UNTIL or FIXED
////        FILE_HEADER = "50";
////        RECORD_HEADER = "4";
//
////        String dataFilePath = "C:/data/ssd/data/huawei_mgcf";
////        String dataFilePath = "C:\data\SBIN\IMS\IMS CDR Sample\CDR example";
////        Path csvPath = Paths.get("./config/hua_mgcf/mgcf.csv");
////        Path jsonPath = Paths.get("./config/hua_mgcf/mgcf_tx.json");
//
//        Map<String, Object> fileStruct = new LinkedHashMap<>();
//        fileStruct.put("FILE_HEADER", "");
//        fileStruct.put("RECORD_HEADER", "");
//        fileStruct.put("RECORD_PADDING", "");
//
////        String dataFilePath = "C:\\data\\ssd\\data\\tap\\TAPOUT";
////        String fileNamePattern = "[A-Za-z0-9]+";
////        Path dataDefFile = Paths.get("./config/tap/tap.csv");
////        Path jsonPath = Paths.get("./config/tap/tap_tx.json");
//
//        Transformer tfm = new Transformer(txDefFile);
//        DataDef conf = new DataDef(dataDefFile, "CallEventDataFile", 5);
//
//        List<Path> pathList = Utils.findFiles(dataFilePath, "", dataFileNamePat, 2);
//
//        for (Path path : pathList) {
//            try {
//                System.out.println("\nReading " + path);
//                InputStream inputStream = Files.newInputStream(path.toFile().toPath());
//
//                ASN1Reader reader = new ASN1Reader(inputStream, conf, fileStruct); // Replace with your file path
//                AtomicLong count = new AtomicLong();
//                while (reader.hasNext()) {
//                    try {
//                        Map<String, Object> node = reader.next();
//                        node.forEach((key, value) -> {
//                            switch (key) {
//                                case "headerRecord": // HeaderRecord
//                                    System.out.print("");
//                                    break;
//                                case "callEventRecords": // CallEventRecords
//                                    ((Map<String, Object>) value).forEach((k, v) -> {
//                                                switch (k) {
//                                                    case "moCallRecord": // MOCallRecord
//                                                        break;
//                                                    case "mtCallRecord": // MTCallRecord
//                                                        break;
//                                                    case "roamingRecord": // RoamingRecord
//                                                        break;
//                                                    case "incGatewayRecord": // IncGatewayRecord
//                                                        break;
//                                                    case "outGatewayRecord": // OutGatewayRecord
//                                                        break;
//                                                    case "transitRecord": // TransitCallRecord
//                                                        break;
//                                                    case "moSMSRecord": // MOSMSRecord
//                                                        break;
//                                                    case "mtSMSRecord": // MTSMSRecord
//                                                        break;
//                                                    case "ssActionRecord": // SSActionRecord
//                                                        break;
//                                                    case "hlrIntRecord": // HLRIntRecord
//                                                        break;
//                                                    case "locUpdateVLRRecord": // LocUpdateVLRRecord
//                                                        break;
//                                                    case "commonEquipRecord": // CommonEquipRecord
//                                                        break;
//                                                    case "recTypeExtensions": // SET OF ManagementExtension
//                                                        break;
//                                                    case "termCAMELRecord": // TermCAMELRecord
//                                                        break;
//                                                    case "mtLCSRecord": // MTLCSRecord
//                                                        break;
//                                                    case "moLCSRecord": // MOLCSRecord
//                                                        break;
//                                                    case "niLCSRecord": // NILCSRecord
//                                                        break;
//                                                    case "groupCallRecord": // GroupCallRecord
//                                                        break;
//                                                    case "soCallRecord": // SOCallRecord
//                                                        break;
//                                                    case "stCallRecord": // STCallRecord
//                                                        break;
//                                                    case "soSMSRecord": // SOSMSRecord
//                                                        break;
//                                                    case "stSMSRecord": // STSMSRecord
//                                                        break;
//                                                    case "forwardCallRecord": // MOCallRecord
//                                                        break;
//                                                    default:
//                                                }
//
//                                                if (v instanceof List) {
//                                                    ((List<?>) v).forEach(record -> {
//                                                        if (record instanceof Map) {
//                                                            List<Map<String, Object>> txRec = tfm.transform((Map<String, Object>) record);
//
//                                                            count.getAndIncrement();
//                                                        }
//                                                    });
//                                                } else if (v instanceof Map) {
//                                                    List<Map<String, Object>> txRec = tfm.transform((Map<String, Object>) v);
//
//                                                    count.getAndIncrement();
//                                                }
//                                            }
//                                    );
//
//                                    break;
//                                case "trailerRecord": // TrailerRecord
//                                    break;
//                                case "extensions": // SET OF ManagementExtension
//                                    break;
//                            }
//                        });
//
//                        System.out.println("Wrote " + reader.recCount + " lines");
//
//                    } catch (Exception e) {
//                        e.printStackTrace();
//                        inputStream.close();
//                        count.getAndIncrement();
//                    }
//                }
//
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
////    private static Map<String, Object> getRecords(Object v) {
////        Map<String, Object> record = new LinkedHashMap<>();
////        if (v instanceof List) {
////            List<?> l = (List<?>) v;
////            l.forEach();
////        } else if (v instanceof Map) {
////            Map<String, Object> m = (Map<String, Object>) v;
////        }
////        return null;
////    }
//
//}
//
