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
//public class SbinHuaMscAsnTest {
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
//        dataFilePath = "..\\generic-asn-reader\\data\\sbin";
//
//        Map<String, Object> fileStruct = new LinkedHashMap<>();
//        fileStruct.put("FILE_HEADER", "");
//        fileStruct.put("RECORD_HEADER", "");
//        fileStruct.put("RECORD_PADDING", "");
//
//        Transformer tfm = new Transformer(txDefFile);
//        DataDef conf = new DataDef(dataDefFile,"CallEventDataFile", 5);
//
//        List<Path> pathList = Transformer.findFiles(dataFilePath, "", dataFileNamePat, 2);
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
////                                    System.out.println(value);
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
//                                                }
////                                                else if (v instanceof Map) {
////                                                    List<Map<String, Object>> txRec = tfm.flatten((Map<String, Object>) v);
////                                                    count.getAndIncrement();
////                                                }
//                                            }
//                                    );
//
//                                    break;
//                                case "trailerRecord": // TrailerRecord
////                                    System.out.println(value);
//                                    break;
//                                case "extensions": // SET OF ManagementExtension
////                                    System.out.println(value);
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
//}
//
