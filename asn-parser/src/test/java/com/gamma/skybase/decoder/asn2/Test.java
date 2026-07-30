package com.gamma.skybase.decoder.asn2;//package com.gamma.skybase.decoder.asn;
//
//import java.io.*;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.util.List;
//import java.util.concurrent.atomic.AtomicReference;
//import java.util.stream.Stream;
//
//public class Test {
//
//
//    private static void createSchema(String content) {
//        try (Stream<String> lines = new BufferedReader(new StringReader(content)).lines()) {
//
//            AtomicReference<String> tableName = new AtomicReference<>("");
//
//            lines.skip(0)
//                    .forEach(l -> {
//                        l = l.trim();
//                        if (l.isEmpty()) {
//                            System.out.println(")");
//                            System.out.println("stored as parquet");
//                            System.out.println("PARTITIONED BY (event_date string)");
//                            System.out.println("LOCATION '/user/hive/warehouse/cbs.db/" + tableName + "'");
//                            System.out.println("TBLPROPERTIES ('parquet.compress' = 'SNAPPY');\n\n");
//                        }
//                        String[] sa = l.split(",");
//                        if (sa.length == 2) {
//                            tableName.set(sa[0].trim());
//                            System.out.println("\nCREATE external TABLE  cbs.dump_" + tableName + " (");
//                        }
//                        String fieldName = "", dataType = "";
//                        if (sa.length > 2) {
//                            fieldName = sa[1].trim();
//                            fieldName = fieldName.replace(" ", "_").toLowerCase();
//
//                            dataType = sa[2].trim();
//                            if (dataType.startsWith("VARCHAR"))
//                                dataType = "STRING";
//                            else if (dataType.startsWith("NUMBER")) {
//                                if (dataType.contains("(")) {
//                                    String sizeStr = dataType.substring(dataType.indexOf('(') + 1, dataType.indexOf(')'));
//                                    if (sizeStr.contains(","))
//                                        dataType = "DOUBLE";
//                                    else {
//                                        int n = Integer.parseInt(sizeStr);
//                                        if (n < 9) dataType = "INT";
//                                        else dataType = "LONG";
//                                    }
//                                }
//                            } else dataType = "STRING";
//                            System.out.println(fieldName + " \t" + dataType + ",");// + sa[2]);
//                        }
//                    });
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    public static String readStringFromFile(Path path) throws IOException {
//        byte[] bytes = Files.readAllBytes(path);
//        return new String(bytes, StandardCharsets.UTF_8); // or any other appropriate charset
//    }
//
//    public static void main(String[] args) throws IOException {
//        String dataDefFile = "./config/Schema.csv";
//        String tagDEF = readStringFromFile(Paths.get(dataDefFile));
//        createSchema(tagDEF);
//
//        // Given byte array
//        byte[] byteArray = {(byte) 0xB6, (byte) 0x2D, (byte) 0xDA, (byte) 0x00};
//
//        // Decode
//        String decodedValue = decodeByteArray(byteArray);
//        System.out.println(decodedValue);  // Output: 2011-12-11 11.29
//
//
//        List<Path> pathList = Utils.findFiles("C:\\data\\SBIN\\IMS\\IMS CDR Sample\\CDR example", "", ".*\\.dat$", 4);
//        for (Path path : pathList) {
//            try {
////                if (path.toString().endsWith(".doc")) continue;
////                if (!path.toString().endsWith(".dat")) continue;
//                System.out.println("\nReading " + path);
//                InputStream in = new FileInputStream(path.toFile());
//                BufferedInputStream bis = new BufferedInputStream(in);
//                byte[] b = new byte[4];
//                int fileLength = bis.read(b);
////                long fl = Decoder.toLong(b);
////                System.out.println("File Length : " + path.toFile().length() + " - " + fl);
//
//                b = new byte[4];
//                int headerLength = bis.read(b);
////                long hl = Decoder.toLong(b);
////                System.out.println("Header Length : " + hl);
//
//                b = new byte[1];
//                int releasedIdentifier = bis.read(b);
////                long ri = Decoder.toLong(b);
////                System.out.println("Released Identifier : " + ri);
//
//                b = new byte[1];
//                int dataRecFormat = bis.read(b);
////                long drf = Decoder.toLong(b);
////                System.out.println("Data Record Format : " + drf);
//
//                b = new byte[4];
//                int fileOpeningTs = bis.read(b);
//                String fots = Decoder.toTimeStamp(b);
//                System.out.println("File Opening Ts : " + fots);
//
//                b = new byte[4];
//                int lastCDR = bis.read(b);
//                String lcdr = Decoder.toTimeStamp(b);
//                System.out.println("File Opening Ts : " + lcdr);
//
//                b = new byte[4];
//                int noOfCDR = bis.read(b);
////                long nocdr = Decoder.toLong(b);
////                System.out.println("File Opening Ts : " + nocdr);
//
//                b = new byte[4];
//                int fsn1 = bis.read(b);
////                long fsn = Decoder.toLong(b);
////                System.out.println("File Sequence No : " + fsn);
//
//                b = new byte[1];
//                int fctr = bis.read(b);
////                long x = Decoder.toLong(b);
////                System.out.println("File Closer Trigger Reason : " + x);
//
//                b = new byte[20];
//                int ip = bis.read(b);
//                String ipa = new String(b, StandardCharsets.UTF_8);
//                System.out.println("File Opening Ts : " + ipa);
//
//                b = new byte[1];
//                int lci = bis.read(b);
////                x = Decoder.toLong(b);
////                System.out.println("Lost CDR Indication : " + x);
//
//                b = new byte[2];
//                int locdrroutingFil = bis.read(b);
////                x = Decoder.toLong(b);
////                System.out.println("Length Of CDR Routing Filter : " + x);
//
//                b = new byte[2];
//                int cdrLen = bis.read(b);
////                x = Decoder.toLong(b);
////                System.out.println("CDR Len : " + x);
//
//
//                b = new byte[1];
//                bis.read(b);
////                x = Decoder.toLong(b);
////                System.out.println("Rel/Ver Id : " + x);
//
//                b = new byte[1];
//                bis.read(b);
////                x = Decoder.toLong(b);
////                System.out.println("Rec fmt/ TS No : " + x);
//
//
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
//    public static String decodeByteArray(byte[] byteArray) {
//        // Ensure the byte array has the correct length
//        if (byteArray.length != 4) {
//            throw new IllegalArgumentException("Byte array must be exactly 4 bytes long");
//        }
//
//        // Extract bytes
//        int byte1 = byteArray[0] & 0xFF;
//        int byte2 = byteArray[1] & 0xFF;
//        int byte3 = byteArray[2] & 0xFF;
//        int byte4 = byteArray[3] & 0xFF;
//
//        // Convert first byte to some form of date part (e.g., year/month)
//        int year = (byte1 >> 4) & 0xF;  // Extract high nibble for year
//        int month = byte1 & 0xF;        // Extract low nibble for month
//
//        // Convert second byte to day
//        int day = byte2;
//
//        // Combine third and fourth bytes to form a number
//        int combinedValue = (byte3 << 8) | byte4;
//
//        // Convert combined value to a decimal number with appropriate scaling (assume scaling factor 100)
//        double decimalValue = combinedValue / 100.0;
//
//        // Format the date as "YY-MM-DD" and value as "value"
//        String dateStr = String.format("20%02d-%02d-%02d", year, month, day);
//        String valueStr = String.format("%.2f", decimalValue);
//
//        return dateStr + " " + valueStr;
//    }
//}
