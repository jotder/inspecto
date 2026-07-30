package com.gamma.skybase.decoder.asn2;

import java.util.function.Predicate;

public class TxConfig {
    public String name;
    String flattenDEF;




//    public void parseConfig(JsonNode node) {
//        if (node.isObject()) {
//            node.fields().forEachRemaining(entry -> {
//                String key = entry.getKey();
//                switch (key) {
//                    case "MAP":
//                        JsonNode value = entry.getValue();
//                        setReduceConfig(value);
//                }
//
//                parseConfig(entry.getValue());
//            });
//        } else if (node.isArray()) {
//            for (JsonNode element : node) {
//                parseConfig(element);
//            }
//        } else {
//            // Leaf node, do nothing
//        }
//    }



//    public static void getValue(JsonNode node) {
//        JsonNode t = node.get("delimited");
//        if (t != null)
//            delimiter = t.asText();
//        t = node.get("prefix");
//        if (t != null)
//            prefix = t.asText();
//        t = node.get("postfix");
//        if (t != null)
//            postfix = t.asText();
//    }

    //     "RECORD".equals(tc.fieldType)
    Predicate<String> subRecordTest = t ->
            "RECORD".equalsIgnoreCase(t)
                    || "rec".equalsIgnoreCase(t)
                    || "arr".equalsIgnoreCase(t)
//                    || "map".equalsIgnoreCase(t)
//                    || "ARRAY".equalsIgnoreCase(t)
                    || "UNION".equalsIgnoreCase(t);

    public boolean isSubRecord(String type) {
//        return !(subRecordTest.test(fieldType) && reduce.isEmpty());
        boolean x = subRecordTest.test(type);
        return x;
    }

//    public JsonNode get(String key) {
//        return config.get(key);
//    }

//    public boolean isDateFormat() {
//        if (format != null)
//            return "timestamp".equalsIgnoreCase(fieldType);
//        return false;
//    }

//    public boolean isNumberFormat() {
//        if (format != null) {
//            return "int".equalsIgnoreCase(fieldType)
//                    || "integer".equalsIgnoreCase(fieldType)
//                    || "long".equalsIgnoreCase(fieldType)
//                    || "float".equalsIgnoreCase(fieldType)
//                    || "double".equalsIgnoreCase(fieldType);
//        }
//        return false;
//    }

    // Map<String, String> reduceConf; // member field names and respective reduce operations


//    String delimiter = "", prefix = "", postfix = "";

}