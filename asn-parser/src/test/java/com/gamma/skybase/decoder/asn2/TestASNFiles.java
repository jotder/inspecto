package com.gamma.skybase.decoder.asn2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.skybase.decoder.asn3.ASNStreamReader;
import com.gamma.skybase.decoder.asn3.MappedFileSource;
import com.gamma.skybase.transformer2.Transformer;
import com.gamma.skybase.utils.Utils;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static com.gamma.skybase.decoder.asn2.utils.FileUtils.findFiles;

public class TestASNFiles {

    static void parse(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir, String fileNamePattern) throws IOException {
        List<Path> pathList = findFiles(srcDir, "", fileNamePattern, 1);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        pathList.forEach(path -> {
            System.out.println("\n\n===========\nReading " + path);
            FileWriter rwFileWriter = null, txFileWriter = null;
            try {

//                if (generateJsonFile) {
//                    rwFileWriter = new FileWriter(path.toAbsolutePath() + ".json");
//                    txFileWriter = new FileWriter(path.toAbsolutePath() + "_tx.json");
//                    // To make it a valid JSON array
//                    rwFileWriter.write("[\n");
//                    txFileWriter.write("[\n");
//                }
                InputStream inputStream = Files.newInputStream(path);
                ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
                boolean firstTxRecord = true;
                while (reader.hasNext()) {
                    try {
                        Map<String, Object> parsedDataNodes = reader.next();
//                        System.out.println(Utils.toPrettyJson(parsedDataNodes));
//                        String raw = mapper.writeValueAsString(parsedDataNodes.values());
//
//                        if (rwFileWriter != null) {
//                            // Add comma if not the first record
//                            if (reader.getRecCount() > 1) {
//                                rwFileWriter.write(",\n");
//                            }
//                            rwFileWriter.write(raw);
//                        }
                        if (parsedDataNodes == null || parsedDataNodes.isEmpty()) {
                            System.out.println("Empty / null event received from parser end : " + parsedDataNodes + " @ " + reader.getRecCount());
                        } else {
                            try {
                                List<Map<String, Object>> txRecList = tfm.transform("onlineCreditControlRecord", parsedDataNodes);
                                System.out.println(Utils.toPrettyJson(txRecList));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                        }
//                        Map<String, Object> value = (Map<String, Object>) parsedDataNodes.get(key);
//                        if ( !txRecList.isEmpty() && txFileWriter != null) {
//                            for (Map<String, Object> txRec : txRecList) {
//                                System.out.println(txRec);
//
//                                if (!firstTxRecord) txFileWriter.write(",\n");
//
//                                String tx = mapper.writeValueAsString(txRec);
//                                txFileWriter.write(tx);
//                                firstTxRecord = false;
//                            }
//                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

//                // Close JSON array
//                if (rwFileWriter != null) {
//                    rwFileWriter.write("\n]");
//                }
////                if (txFileWriter != null) {
//                txFileWriter.write("\n]");
////                }

//                System.out.println("\n\nWrote " + reader.getRecCount() + " lines");
            } catch (IOException e) {
//                throw new RuntimeException(e);
            } finally {
//                try {
//                    // Safely close writers
//                    if (rwFileWriter != null) {
//                        rwFileWriter.close();
//                    }
//                    if (txFileWriter != null) {
//                        txFileWriter.close();
//                    }
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }
            }
        });
    }


    static void mtnHuwImsParse(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir) throws IOException {
        try (InputStream inputStream = Files.newInputStream(Paths.get(srcDir))) {
            ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();
                    if (parsedDataNodes != null && !parsedDataNodes.isEmpty()) {
                        List<Map<String, Object>> txRecList = tfm.transform("onlineCreditControlRecord", parsedDataNodes);
                        System.out.println(Utils.toPrettyJson(txRecList));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
        }
    }


    static void mtnOCCParse(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir) throws IOException {

        try {
            ASNStreamReader reader = new ASNStreamReader(conf, headerInfo, new MappedFileSource(Paths.get(srcDir)));
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();
                    if (parsedDataNodes != null && !parsedDataNodes.isEmpty()) {
//                        Object triggerTime = parsedDataNodes.get("triggerTime");
//                        if (triggerTime != null && !triggerTime.toString().isEmpty()) {
//                            if (parsedDataNodes.get("recordIdentificationNumber").toString().equals("0850B5D3"))
//                                System.out.println(Utils.toPrettyJson(parsedDataNodes));
//
//                            List<Map<String, Object>> txRecList = tfm.transform("onlineCreditControlRecord", parsedDataNodes);
//                            System.out.println(Utils.toPrettyJson(txRecList));
//                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


    }

    static void mtnGprsParse(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir) {
        try {
            System.out.println("Processing: " + srcDir);
            ASNStreamReader reader = new ASNStreamReader(conf, headerInfo, new MappedFileSource(Paths.get(srcDir)));
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();
                    if (parsedDataNodes != null && !parsedDataNodes.isEmpty()) {
//                            List<Map<String, Object>> txRecList = tfm.transform("onlineCreditControlRecord", parsedDataNodes);
//                            System.out.println(Utils.toPrettyJson(txRecList));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    static void PGW(DataDef conf, Map<String, Object> headerInfo, String srcDir) {
        try {
            System.out.println("Processing: " + srcDir);
            ASNStreamReader reader = new ASNStreamReader(conf, headerInfo, new MappedFileSource(Paths.get(srcDir)));
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();
                    if (parsedDataNodes != null && !parsedDataNodes.isEmpty()) {
                        if (parsedDataNodes != null) {
                            if (parsedDataNodes instanceof Map) {
                                Map<String, Object> m = (Map<String, Object>) parsedDataNodes.get("pGWRecord");
                                Object imsi = m.get("servedIMSI");
                                if (imsi != null && imsi.toString().equalsIgnoreCase("634012000266757")) {
                                    System.out.println(parsedDataNodes);
                                }
                            }

                        }
//                        System.out.println(Utils.toPrettyJson(parsedDataNodes));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    static void mtnCCNParse(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir) throws
            IOException {
        try (InputStream inputStream = Files.newInputStream(Paths.get(srcDir))) {
            ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();
                    if (parsedDataNodes != null && !parsedDataNodes.isEmpty()) {
                        Object triggerTime = parsedDataNodes.get("triggerTime");
                        if (triggerTime != null && !triggerTime.toString().isEmpty()) {
                            if (parsedDataNodes.get("recordIdentificationNumber").toString().equals("174EDBF8"))
                                System.out.println(Utils.toPrettyJson(parsedDataNodes));

                            List<Map<String, Object>> txRecList = tfm.transform("onlineCreditControlRecord", parsedDataNodes);
                            System.out.println(Utils.toPrettyJson(txRecList));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
//                throw new RuntimeException(e);
        }
    }


    static void aftelIMSParse(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir) throws
            IOException {
        try (InputStream inputStream = Files.newInputStream(Paths.get(srcDir))) {
            ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();

                    String key = parsedDataNodes.get("recordType").toString();
                    List<Map<String, Object>> txRecList;
                    switch (key) {
                        case "63":
                        case "64":
                        case "65":
                        case "67":
                        case "68":
                        case "69":
                        case "70":
                        case "82":
                        case "83":
                        case "91":
                        case "200":
                        case "205":
                        case "253":
                            txRecList = Collections.singletonList(parsedDataNodes);
                            System.out.println(txRecList);
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    static void parseGMSC(DataDef conf, Map<String, Object> headerInfo, Transformer tfm, String srcDir) throws
            IOException {

        try (InputStream inputStream = Files.newInputStream(Paths.get(srcDir))) {
            ASN1Reader reader = new ASN1Reader(inputStream, conf, headerInfo);
            while (reader.hasNext()) {
                try {
                    Map<String, Object> parsedDataNodes = reader.next();
                    for (Map.Entry<String, Object> rec : parsedDataNodes.entrySet()) {
                        String key = rec.getKey();
                        Map<String, List<Object>> records = (Map<String, List<Object>>) rec.getValue();
                        switch (key) {
                            case "headerRecord":
                                break;
                            case "callEventRecords":
                                records.forEach((recordType, list) -> {
                                    System.out.println("\n-------- transforming " + recordType + " --------");
                                    list.forEach(r -> {
                                        Map<String, Object> record = (Map<String, Object>) r;
                                        System.out.println(Utils.toPrettyJson(record));

                                        List<Map<String, Object>> txRecList = null;
                                        switch (recordType) {
                                            case "moCallRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "mtCallRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "roamingRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "incGatewayRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "outGatewayRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "transitRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "moSMSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "mtSMSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "ssActionRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "hlrIntRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "locUpdateVLRRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "commonEquipRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "recTypeExtensions":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "termCAMELRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "mtLCSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "moLCSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "niLCSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "mSCsRVCCRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "mtrfCallRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "soCallRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "stCallRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "soSMSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            case "stSMSRecord":
                                                txRecList = tfm.transform(recordType, record);
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                                break;
                                            default:
                                                System.out.println(Utils.toPrettyJson(txRecList));
                                        }
                                    });
                                });
                                break;
                            case "trailerRecord":
                                break;

                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
