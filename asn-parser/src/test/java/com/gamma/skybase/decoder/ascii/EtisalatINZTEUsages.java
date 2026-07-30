package com.gamma.skybase.decoder.ascii;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.gamma.skybase.decoder.asn2.utils.FileUtils.findFiles;

public class EtisalatINZTEUsages {

    public static void main(String[] args) throws Exception {
        String srcDir = ".\\data\\mcit\\etisalat\\IN\\Usage cdrs\\cdr_IN_USAGE_ZTE";

        EtisalatINZTEUsages usages = new EtisalatINZTEUsages();
        String p = "[A-Za-z0-9]+";
        List<Path> pathList = findFiles(srcDir, "", p, 1);
        for (Path path : pathList) {
            usages.parse(path.toFile().getAbsolutePath());

            FileWriter fw = new FileWriter(path.toFile().getName());
            Map<String, Object> rec = getEmptyRecord(recordType);
            fw.write(String.join(",", rec.keySet()) + "\n");

            while (usages.hasNext()) {
                rec = usages.next();
                System.out.println("\n" + rec);
                fw.write(rec.values().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(",")) + "\n");
            }
            fw.close();

        }
    }

    private static final LinkedHashMap<String, FieldMD> schema = new LinkedHashMap<>();

    static { // Schema gen
        Path path = Paths.get(".\\data\\mcit\\etisalat\\IN\\Usage cdrs\\All.csv");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String schematext = new String(bytes, StandardCharsets.UTF_8); // or any other appropriate charset
        try (Stream<String> lines = new BufferedReader(new StringReader(schematext)).lines()) {
            lines.skip(1)
                    .forEach(l -> {
                        String[] sa = l.split(",");
                        if (sa.length > 3) {
                            String length = sa[3].trim();
                            String type = sa[2].trim();
                            String name = sa[1].trim();
                            String id = sa[0].trim();
                            FieldMD x = schema.get(id);
                            FieldMD tcfg = new FieldMD(id, name, type, length);
                            if (x != null) {
                                boolean b = x.toString().equalsIgnoreCase(tcfg.toString());
                                if (!b)
                                    System.out.println("\n" + x + "\n" + tcfg);
                            } else {
                                schema.put(id, tcfg);
                            }
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static String[] voice = {"1", "2", "3", "4", "6", "7", "8", "9", "15", "16", "17", "20", "21", "22", "23", "28", "32", "33", "34", "35", "36", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50", "51", "52", "53", "66", "67", "68", "69", "74", "82", "83", "86", "96", "97", "98", "99", "100", "112", "122", "128", "136", "137", "171", "175", "187", "201", "203", "206", "208", "209", "211", "212", "215", "216", "220", "221", "222", "223", "224", "225", "228", "230", "231", "232", "233", "235", "236", "237", "239", "240", "242", "246", "251", "252", "259", "260", "264", "267", "270", "271", "272", "273", "278", "281", "286", "297", "298", "307", "308", "309", "310", "311", "314", "315", "317", "318", "319", "320", "322", "324", "325", "326", "327", "328", "329", "335", "336", "338", "342", "343", "344", "351", "353", "354", "355", "358", "361", "362", "364", "366", "368", "373", "374", "375", "379", "380", "385", "387", "393", "400", "404", "408", "420", "421", "424", "425", "426", "427", "428", "429", "444", "445", "451", "452", "453", "454", "470", "471", "480", "494", "499", "503", "504", "505", "511", "512", "513", "514", "515", "516", "517", "519", "556", "597", "600", "601", "605", "611", "612", "615", "616", "620", "622", "623", "635", "658", "678", "706", "707", "710", "712", "715", "716", "730", "770", "771", "804", "805", "806", "807", "808", "811", "813", "814", "815", "816", "822", "823", "846", "848", "849", "872", "873", "874", "879", "884", "885", "886", "887", "888", "893", "897", "902", "904", "905", "906", "907", "908", "909", "910", "911", "913", "915", "919", "920", "925", "926", "927", "934", "944", "949", "950", "951", "952", "953", "982", "987", "995", "1000", "1015", "1018", "1019", "1037", "1045", "1046", "1047", "1048", "1049", "1051", "1052", "1058", "1060", "1087", "1088", "1093", "1094", "1095", "1096", "1097", "1098", "1119", "1126", "1127", "1128", "1129", "1130", "1139", "1140", "1141", "1146", "1147", "1151", "1152", "1153", "1154", "1155", "1156", "1157", "1159", "1160", "1161", "1162", "1170", "1173", "1174", "1175", "1176", "1177", "1180", "1181", "1182", "1183", "1185", "1186", "1187", "1188", "1189", "1190", "1191", "1192", "1193", "1214", "1223", "1224", "1227", "1228", "1229", "1242", "1243", "1245", "1249", "1250", "1251", "1252", "1253", "1254", "1255", "1256", "1257", "1258", "1265", "1268", "1269", "1299", "1300", "1301", "1311", "1312", "1313", "1315", "1320", "1321", "1322", "1324", "1331", "1333", "1341", "1346", "1347", "1380", "1381", "1384", "1450", "1451", "1452", "1453", "1454", "1455", "1456", "1457", "1458", "1459", "1460", "1461", "1469", "1470", "1471", "1472", "1473", "1474", "1475", "1476", "1477", "1478", "1479", "1480", "1483", "1489", "1498", "1531", "1532", "1540", "1541", "1559", "1561", "1602", "1627", "1629", "1641", "1642", "1643", "1659", "1660", "1674", "1675", "1676", "1677", "1678", "1679", "1680", "1695", "1704", "1705"
    };

    static String[] data = {"1", "3", "4", "7", "15", "16", "18", "20", "21", "22", "23", "40", "42", "43", "44", "45", "46", "47", "48", "49", "66", "67", "68", "69", "74", "82", "96", "108", "109", "112", "114", "122", "128", "171", "175", "187", "188", "194", "201", "203", "206", "208", "209", "211", "212", "215", "216", "220", "221", "222", "223", "224", "227", "228", "231", "232", "233", "234", "235", "237", "239", "240", "242", "245", "247", "251", "259", "260", "264", "267", "270", "271", "272", "273", "274", "278", "280", "281", "291", "293", "297", "298", "300", "301", "307", "308", "309", "310", "311", "314", "315", "317", "319", "320", "322", "334", "335", "336", "338", "340", "341", "342", "343", "345", "346", "347", "348", "349", "353", "354", "355", "356", "357", "361", "362", "364", "368", "373", "374", "375", "376", "377", "378", "379", "380", "387", "393", "400", "417", "418", "420", "421", "422", "423", "424", "425", "426", "427", "428", "429", "430", "431", "432", "439", "444", "445", "451", "452", "453", "454", "470", "471", "480", "494", "499", "503", "504", "505", "506", "507", "511", "512", "513", "514", "515", "516", "517", "519", "552", "553", "554", "555", "556", "592", "593", "594", "597", "600", "601", "605", "611", "612", "613", "614", "615", "616", "617", "618", "620", "622", "623", "624", "625", "635", "658", "678", "686", "687", "691", "706", "707", "710", "711", "712", "715", "716", "730", "731", "770", "771", "772", "804", "805", "806", "807", "808", "809", "810", "811", "813", "814", "815", "816", "817", "822", "823", "834", "835", "836", "837", "838", "839", "841", "842", "843", "846", "848", "849", "850", "851", "852", "856", "857", "858", "859", "872", "873", "874", "879", "884", "885", "886", "887", "888", "893", "896", "897", "902", "904", "905", "906", "907", "908", "909", "910", "911", "913", "915", "919", "920", "924", "925", "926", "927", "930", "931", "934", "944", "949", "950", "951", "952", "953", "982", "987", "992", "993", "994", "1000", "1015", "1018", "1021", "1022", "1037", "1045", "1046", "1047", "1048", "1049", "1058", "1060", "1087", "1093", "1094", "1095", "1096", "1097", "1098", "1111", "1118", "1139", "1140", "1141", "1150", "1151", "1152", "1153", "1154", "1155", "1156", "1157", "1159", "1160", "1166", "1167", "1168", "1169", "1170", "1171", "1173", "1174", "1175", "1176", "1177", "1180", "1181", "1182", "1183", "1185", "1186", "1187", "1188", "1189", "1190", "1191", "1192", "1193", "1196", "1211", "1208", "1214", "1215", "1223", "1224", "1227", "1228", "1229", "1238", "1242", "1243", "1245", "1249", "1250", "1251", "1252", "1253", "1254", "1255", "1256", "1257", "1258", "1265", "1268", "1269", "1299", "1300", "1301", "1304", "1311", "1312", "1313", "1315", "1320", "1321", "1322", "1324", "1325", "1331", "1333", "1341", "1346", "1347", "1380", "1381", "1383", "1384", "1400", "1405", "1410", "1415", "1425", "1432", "1433", "1434", "1435", "1440", "1445", "1450", "1451", "1452", "1453", "1454", "1455", "1456", "1457", "1458", "1459", "1460", "1461", "1462", "1463", "1464", "1469", "1470", "1471", "1472", "1473", "1474", "1475", "1476", "1477", "1478", "1479", "1480", "1489", "1498", "1519", "1531", "1532", "1535", "1540", "1541", "1542", "1543", "1544", "1545", "1559", "1602", "1627", "1631", "1641", "1642", "1643", "1659", "1660", "1674", "1675", "1676", "1677", "1678", "1679", "1680", "1695", "1701", "1702", "1703", "1704", "1705", "1711", "1712"
    };

    static String[] sms = {"28", "1", "2", "3", "4", "6", "7", "8", "9", "15", "16", "17", "20", "21", "22", "23", "32", "33", "34", "35", "36", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50", "51", "52", "53", "66", "67", "68", "69", "74", "82", "83", "96", "112", "122", "128", "136", "137", "171", "175", "187", "201", "203", "206", "208", "209", "211", "220", "221", "222", "223", "224", "228", "232", "233", "235", "237", "239", "240", "242", "246", "252", "259", "260", "264", "267", "270", "271", "272", "273", "278", "297", "298", "307", "308", "309", "310", "311", "313", "315", "317", "320", "321", "322", "323", "324", "329", "331", "332", "333", "335", "336", "338", "345", "346", "353", "354", "355", "358", "361", "364", "368", "373", "379", "380", "393", "398", "400", "420", "421", "424", "425", "426", "427", "428", "429", "444", "445", "451", "452", "453", "454", "480", "499", "503", "504", "505", "511", "512", "513", "514", "515", "516", "517", "519", "597", "600", "601", "605", "611", "612", "615", "616", "620", "622", "623", "635", "658", "678", "706", "707", "710", "712", "715", "770", "804", "805", "806", "807", "808", "811", "813", "814", "815", "816", "822", "823", "846", "848", "849", "872", "873", "874", "879", "884", "885", "886", "887", "888", "893", "897", "902", "904", "905", "906", "907", "908", "909", "910", "911", "913", "915", "919", "920", "934", "944", "949", "950", "951", "952", "953", "982", "987", "995", "1000", "1018", "1019", "1037", "1045", "1046", "1047", "1049", "1058", "1060", "1087", "1095", "1139", "1140", "1183", "1214", "1227", "1228", "1229", "1242", "1243", "1249", "1250", "1251", "1252", "1253", "1254", "1255", "1256", "1257", "1258", "1300", "1301", "1312", "1313", "1315", "1324", "1331", "1333", "1341", "1346", "1347", "1384", "1450", "1451", "1452", "1453", "1454", "1455", "1456", "1457", "1458", "1459", "1460", "1461", "1469", "1470", "1471", "1472", "1473", "1474", "1475", "1476", "1477", "1478", "1479", "1480", "1489", "1498", "1531", "1532", "1540", "1541", "1559", "1602", "1641", "1659", "1660", "1674", "1675", "1676", "1677", "1678", "1679", "1680", "1695"
    };
    static String[] abnormal_IN = {};
    static String[] abnormal_PS = {};

    private static Map<String, Object> getEmptyRecord(String recordType) {
        String[] fields = new String[0];
        switch (recordType) {
            case "data":
                fields = data;
                break;
            case "voice":
                fields = voice;
                break;
            case "sms":
                fields = sms;
                break;
            case "abnormal_IN":
                break;
            case "abnormal_PS":
                break;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        for (String field : fields) {
            try {
                FieldMD f = schema.get(field);
                record.put(f.name, "");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return record;
    }

    List<List<String>> records = new ArrayList<>();

    public static String readStringFromFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8); // or any other appropriate charset
    }

    static String recordType;

    public void parse(String srcFile) throws IOException {
        String fileName = new File(srcFile).getName();
        System.out.println("--------- Processing " + fileName + " ----------");
        if (fileName.startsWith("voice"))
            recordType = "voice";
        else if (fileName.startsWith("data"))
            recordType = "data";
        else if (fileName.startsWith("sms"))
            recordType = "sms";


        AtomicBoolean recordStart = new AtomicBoolean(false);
        AtomicReference<List<String>> block = new AtomicReference<>(new ArrayList<>());

        String content = readStringFromFile(Paths.get(srcFile));
        try (Stream<String> lines = new BufferedReader(new StringReader(content)).lines()) {
            lines.filter(l -> !l.trim().isEmpty())
                    .forEach(l -> {
                        if (l.trim().equals("{")) {
                            recordStart.getAndSet(true);
                            block.set(new ArrayList<>());
                        } else if (l.trim().equals("}")) {
                            recordStart.getAndSet(false);
                            records.add(block.get());
                        } else
                            block.get().add(l.trim());
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean hasNext() {
        return !records.isEmpty();
    }

    public synchronized Map<String, Object> next() {
        List<String> lines = records.remove(0);
        Map<String, Object> rec = getEmptyRecord(recordType);
        lines.forEach(l -> {
            try {
                String[] field = l.split("=");
//
                String k = field[0].trim();
                FieldMD fieldDef = schema.get(k);
                if (!rec.containsKey(fieldDef.name)) {
                    System.out.println(k);
                    System.out.println(fieldDef);
                } else {
                    String v = field[1];
                    rec.put(fieldDef.name, fieldDef.getValue(v));
                }
            } catch (Exception e) {
//                System.out.println();
            }
        });
        return rec;
    }

    static class FieldMD {
        public String no, length, type, name;

        public FieldMD(String no, String name, String tType, String length) {
            this.no = no;
            this.name = name;
            this.type = tType;
            this.length = length;
        }

        public Object getValue(String strVal) {
            switch (type) {
                case "long":
                    try {
                        return Long.parseLong(strVal);
                    } catch (Exception _) {
                        _.printStackTrace();
                    }
                default:
                    return strVal;
            }
        }

        @Override
        public String toString() {
            return this.no + "\t" + this.name + "\t" + this.type + "\t" + this.length;
        }
    }

}

