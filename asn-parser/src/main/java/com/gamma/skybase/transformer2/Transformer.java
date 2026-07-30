package com.gamma.skybase.transformer2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class Transformer {
    private static final Logger logger = LoggerFactory.getLogger(Transformer.class);
    static List<Map<String, Object>> records = new ArrayList<>();
    static JsonNode txConfig;
    String recordType;

    public Transformer(String path) throws IOException {
        String txText = TransformUtils.readStringFromFile(Paths.get(path));
        List<String> readLines = new BufferedReader(new StringReader(txText)).lines().skip(0).map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("--")).map(l -> l.replaceAll("\\s+", " ").trim()).map(l -> {
            if (l.contains("--")) return l.substring(0, l.indexOf("--")).trim();
            return l.trim();
        }).collect(Collectors.toList());

        txConfig = new ObjectMapper().readTree(String.join("\n", readLines));
    }

    public List<Map<String, Object>> transform(String recordType, Map<String, Object> recNode) {
        if (recNode.isEmpty()) return new ArrayList<>();

        this.recordType = recordType;
        JsonNode conf = txConfig.get(recordType);
        TransformUtils.setCache(txConfig.get("@simpleLookup")); //keep cache handy

        //       System.out.println(toPrettyJson(recNode));
        List<Map<String, Object>> x = transformRecord(recordType, conf, recNode);

        return x;
    }

    private static List<Map<String, Object>> transformRecord(String parent, JsonNode nodeConfig, Map<String, Object> valueNode) {
        Map<String, Object> resultFields = new LinkedHashMap<>();
        List<Map<String, Object>> fields = handleMap(parent, nodeConfig, valueNode, resultFields);
//        Map<String, Object> resultFields = new LinkedHashMap<>();
        //for parent node
//        JsonNode keepSource = nodeConfig.get("@keepSource");
//        JsonNode derivedFields = nodeConfig.get("@derivedFields");
//        Map<String, Object> newFields = processDerivedFields(derivedFields, valueNode);
//        fields.putAll(newFields);
//        JsonNode autoJoin = eventConfig.get("@autoJoin");
//        JsonNode comment = eventConfig.get("@comment");
//        JsonNode fieldConfig = eventConfig.get("@keepSource");
//        List<Map<String, Object>> derivedFields = new ArrayList<>();
//        JsonNode conf = nodeConfig.get("@derivedFields");
//        if (conf != null) {
//            Map<String, Object> dFields = processDerivedFields(conf, valueNode);
//            derivedFields.add(dFields);
//        }

//        List<Map<String, Object>> x = cartesianJoin(fields, new ArrayList<>(derivedFields));
        return fields;
    }

    private static List<Map<String, Object>> handleMap(String parent, JsonNode eventConfig, Map<String, Object> event, Map<String, Object> resultFields) {
        Map<String, List<Map<String, Object>>> subRecords = new LinkedHashMap<>();
//        Map<String, Object> transformedFields = new LinkedHashMap<>();

        for (Map.Entry<String, Object> kv : event.entrySet()) {

            String fieldName = kv.getKey();
            Object fieldValue = kv.getValue();
            JsonNode fieldConfig = null;
            if (eventConfig != null) fieldConfig = eventConfig.get(fieldName);
            if ((fieldConfig == null || fieldConfig.isEmpty()) && txConfig.has(fieldName))
                fieldConfig = txConfig.get(fieldName);

            final JsonNode config = fieldConfig;
            AtomicBoolean autoJoin = new AtomicBoolean(true);

            if (config != null && !config.isEmpty()) config.fieldNames().forEachRemaining(cfgName -> {
                JsonNode aj = config.get("@autoJoin");
                if (aj != null)
                    autoJoin.set(Boolean.parseBoolean(aj.toString()));

                switch (cfgName) {
                    case "@keepSource":
                        boolean b = config.get("@keepSource").asBoolean();
                        if (b)
                            resultFields.put(fieldName, fieldValue);
                        break;

                    case "@transform":
                        Map<String, Object> tx = processLeafObject(fieldName, parent, config, fieldValue);
                        resultFields.putAll(tx);
                        autoJoin.set(false);
                        break;

                    case "@derivedFields":
                        if (event.isEmpty())
                            System.out.println("!!!!---- event data empty !!");
                        else
                            processDerivedFields(config.get("@derivedFields"), event, resultFields);
                        break;

                    case "@comment":
                        break;

                    default:
//                            no cfg handle next foe nested element
//                            transformed.set(true);
//                            t = new LinkedHashMap<>();
//                            t = processLeafObject(cfgName, fieldName, config, fieldValue);
//                            resultFields.putAll(t);
                        break;
                }
            });

            Map<String, Object> tx;
            if (fieldValue instanceof Map<?, ?>) { // Map
                Map<String, Object> val = (Map<String, Object>) fieldValue;
                if (config == null || val.isEmpty())
                    logger.debug("Config is null or value is empty for field: {}", fieldName);
                if (autoJoin.get()) {
                    List<Map<String, Object>> temp = handleMap(parent, config, val, resultFields);
                    if (!subRecords.containsKey(fieldName)) subRecords.put(fieldName, temp);
                    else //
                        subRecords.get(fieldName).addAll(temp);
                }
            } else if (fieldValue instanceof List<?>) {
                if (autoJoin.get()) { // default autoJoin
                    Map<String, List<Map<String, Object>>> temp = handleList(fieldName, parent, config, (List<Object>) fieldValue);
                    temp.forEach((k, v) -> {
                        if (!subRecords.containsKey(k)) subRecords.put(k, v);
                        else //
                            subRecords.get(k).addAll(v);
                    });
                }
            } else {    // Leaf Object
                tx = processLeafObject(fieldName, parent, config, fieldValue);
                resultFields.putAll(tx);
            }
        }

//        JsonNode keepSource = eventConfig.get("@keepSource");
        if (eventConfig != null) {
            JsonNode derivedFields = eventConfig.get("@derivedFields");
            if (derivedFields != null) {
                if (event.isEmpty())
                    System.out.println("!!!!---- Error event data empty !!");
                processDerivedFields(derivedFields, event, resultFields); // Process derived fields for the current level
            }
        }
        List<Map<String, Object>> mapRecord = combineResults(resultFields, subRecords);
        return mapRecord;
    }

    private static Map<String, Object> processLeafObject(String name, String parent, JsonNode conf, Object value) {

        Map<String, Object> txMap = new LinkedHashMap<>();
        if (conf != null) {
            Map<String, Object> result = checkConstantAndInvoke(name, conf, value);
            if (result != null) {
                for (Map.Entry<String, Object> txm : result.entrySet()) {
                    String newKey = getModifyKey(txm.getKey(), parent, conf);
                    txMap.put(newKey, txm.getValue());
                }
            } else {
                String newKey = getModifyKey(name, parent, conf);
                txMap.put(newKey, value);
            }
        } else txMap.put(name, value);
        return txMap;
    }

    public static Map<String, Object> checkConstantAndInvoke(String key, JsonNode conf, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            MethodAndParams methodAndParams = new MethodAndParams(conf);
            if (methodAndParams.isConstant()) {
                result.put(key, methodAndParams.getConstantValue());
                return result;
            }

            String methodName = methodAndParams.methodName;
            if (!methodAndParams.isConstant()) {
                try {
                    List<Object> params = methodAndParams.getParamValues(value, result);
                    result = applyTransformation(key, value, methodName, params);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            } //
            else result.put(key, value);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    public static Map<String, Object> populateDerivedField(String key, JsonNode conf, Map<String, Object> values, Map<String, Object> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            MethodAndParams mp = new MethodAndParams(conf);
            if (mp.isConstant()) {
                result.put(key, mp.getConstantValue());
                return result;
            }

            if (mp.isVariable()) {
                Object value = mp.evaluate(values, fields);
                result.put(key, value);
                return result;
            }

            List<Object> params = mp.getParamValues(values, fields);
            result = applyTransformation(key, values, mp.methodName, params);

        } catch (Exception e) {
//            e.printStackTrace();
//            logger.error(e.getMessage());
        }
        return result;
    }

    private static Map<String, List<Map<String, Object>>> handleList(String name, String parent, JsonNode config, List<Object> values) {
        Map<String, List<Map<String, Object>>> subRecLists = new LinkedHashMap<>();
//        Map<String, Object> fields = new LinkedHashMap<>();

        AtomicBoolean isListOfMap = new AtomicBoolean(true);
        values.forEach(e -> {
            if (!(e instanceof Map<?, ?>)) isListOfMap.set(false);
        });

        if (isListOfMap.get()) {                                                        // Maps -- multiple sub record
            List<Map<String, Object>> subRecords = flattenList(name, config, (List<Map<String, Object>>) (List<?>) values);
//            List<Map<String, Object>> subRecords = new ArrayList<>();
            List<Map<String, Object>> t = subRecLists.get(name);
            if (t != null)//
                t.addAll(subRecords);
            else //
                subRecLists.put(name, new ArrayList<>(subRecords));
        }

//        JsonNode txCfg = config.get("@transform");
//        JsonNode delCfg = config.get("@delimited");
//        if (txCfg != null && txCfg.isEmpty()) {                         // custom transform fn first priority / reduce
//            Map<String, Object> tx = processLeafObject(name, parent, txCfg, values);
//            fields.putAll(tx);
//        } //
//        else if (delCfg != null && !delCfg.isEmpty())                   // Lists of leaf object  //reduce?
//        {
//            fields.put(name, TransformUtils.getAsDSV(values, delCfg.asText().trim()));
//        } //
//        else {                                                         // list of Map
////            if (!values.isEmpty()) {
//            Object value = values.getFirst();
//            if (value instanceof Map) {
//                values.forEach(node -> {
//                    List<Map<String, Object>> result = handleMap(parent, config, (Map<String, Object>) node);
//                });
//            } else if (value instanceof List) {
//                values.forEach(node -> {
//                    Map<String, List<Map<String, Object>>> result = handleList(parent, parent, config, (List<Object>) node);
//                });
//            } else {
//                String result = Utils.toPrettyJson(values);
//                List<Map<String, Object>> p = subRecLists.get(name);
////                    p.add(result);
//                System.out.println();
//            }
//        }
////                  JsonNode txCfg = config.get("name");
////                  Map<String, List<Map<String, Object>>> temp = handleList(name, parent, config, values);
////                  temp.forEach((k, v) -> {
////                      if (subRecordList.containsKey(k)) {
////                      } else
////                          subRecordList.put(k, v);
////                  });
////            }
//
        return subRecLists;
    }

    private static void processDerivedFields(JsonNode config, Map<String, Object> valueNode, Map<String, Object> fields) {
        config.fieldNames().forEachRemaining(field -> {
            JsonNode fieldConfig = config.get(field);
            Map<String, Object> derivedfields = populateDerivedField(field, fieldConfig, valueNode, fields);
            derivedfields.forEach((key, value) -> {
                if (value instanceof Map)
                    fields.putAll((Map<? extends String, ?>) value);
                else
                    fields.put(key, value);
            });
        });
    }


    private static List<Map<String, Object>> combineResults(Map<String, Object> record, Map<String, List<Map<String, Object>>> subRecords) {
        Optional<List<Map<String, Object>>> joinedSubRecords = subRecords.values().stream().reduce(Transformer::cartesianJoin);

        List<Map<String, Object>> joinedRecords = new ArrayList<>();
        if (joinedSubRecords.isPresent()) {
            List<Map<String, Object>> srs = joinedSubRecords.get();
            srs.forEach(e -> {
                Map<String, Object> x = new LinkedHashMap<>(record);
                x.putAll(e);
                joinedRecords.add(x);
            });
        } else {
            if (!record.isEmpty()) joinedRecords.add(new LinkedHashMap<>(record));
        }

        return joinedRecords;
    }

    static String getMethod(String def) {
        if (def == null || def.isEmpty()) return "";
        if (def.contains("(")) def = def.substring(0, def.indexOf('('));
        return def.trim();
    }

    static ArrayList<String> getParams(String def) {
        ArrayList<String> params = null;

        if (def == null || def.trim().isEmpty()) return params;
        else def = def.trim();
        int open = def.indexOf('(');
        int close = def.lastIndexOf(')');
        if (open != -1) {
            String paramsConf = def.substring(open + 1, close).trim();
            if (!paramsConf.isEmpty()) {
                params = new ArrayList<>();
                for (String p : paramsConf.split(","))
                    if (!p.trim().isEmpty()) params.add(p.trim());
            }
        }
        return params;
    }

    private static class MethodAndParams {
        String methodName = "";
        ArrayList<String> params = new ArrayList<>();

        public MethodAndParams(JsonNode conf) {
            JsonNode tx = conf.get("@transform");
            if (conf.getNodeType().equals(JsonNodeType.STRING)) {
                this.methodName = getMethod(conf.asText());
                ArrayList<String> p = getParams(conf.asText());
                if (p != null) this.params.addAll(p);
            } else if (tx != null) {
                this.methodName = getMethod(tx.asText());
                ArrayList<String> p = getParams(tx.asText());
                if (p != null) this.params.addAll(p);
            }
        }

        boolean isConstant() {
            return methodName.startsWith("\"") && methodName.endsWith("\"");
        }

        boolean isVariable() {
            return methodName.startsWith("$") || methodName.startsWith("@");
        }

        String getConstantValue() {
            return methodName.substring(1, methodName.length() - 1);
        }

        List<Object> getParamValues(Object values, Map<String, Object> fields) throws Exception {
            List<Object> inputVals = new ArrayList<>();

            if (!params.isEmpty()) {// "@self","$$", $
                for (String param : params) {
                    Object pv = getParamValue(param, values, fields);
                    if (pv == null) {
                        throw new Exception("Param value could not be evaluated for param: " + param);
                    }
                    inputVals.add(pv);
                }
            }

            return inputVals;
        }

        Object getParamValue(String param, Object value, Map<String, Object> fields) {
            if (isConstant()) return null;

            Map<String, Object> valueNode;
            if (value != null) if (value instanceof Map) valueNode = (Map<String, Object>) value;
            else valueNode = null;
            else valueNode = null;

            if (param.equalsIgnoreCase("@self")) return value;

            String p = param;
            if (param.startsWith("$$")) p = param.substring(2);
            else if (param.startsWith("$")) p = param.substring(1);

            if (param.startsWith("$$")) {
                Object v1 = valueNode.get(p);
                if (v1 == null && fields != null) v1 = fields.get(p);
                Object v2 = null;
                if (v1 != null) v2 = valueNode.get(v1);
                return v2;
            }

            if (param.startsWith("$")) {
                Object v = valueNode.get(p);
                if (v == null)
                    v = fields.get(p);
                return v;
            }
            return param;
        }

        public Object evaluate(Map<String, Object> values, Map<String, Object> fields) {
            Object x = getParamValue(methodName, values, fields);
            if (x == null)
                logger.debug("Could not evaluate variable: " + methodName);
            return x;
        }
    }

    private static Map<String, Object> applyTransformation(String key, Object input, String method, List<Object> params) {
        Object newValue = input;
        boolean transformed = false;
        if (method != null && !method.isEmpty()) {
            newValue = invokeDynamic(method, params);
            transformed = true;
        }
        Map<String, Object> txValues = new LinkedHashMap<>();
        if (transformed && newValue instanceof Map)
            ((Map<?, ?>) newValue).forEach((k, v) -> txValues.put(k.toString(), v));
        else txValues.put(key, newValue);

        return txValues;
    }

    static String getModifyKey(String key, String parent, JsonNode conf) {
        JsonNode t = conf.get("@rename");
        String name = "";
        if (t != null) name = t.asText().trim();

        t = conf.get("@prefix");
        String pre = "";
        if (t != null) pre = t.asText().trim();

        t = conf.get("@useParentKeyAsSuffix");
        boolean useParentKeyAsSuffix = false;
        if (t != null) useParentKeyAsSuffix = t.asBoolean();

        boolean useParentKeyAsPrefix = false;
        t = conf.get("@useParentKeyAsPrefix");
        if (t != null) useParentKeyAsPrefix = t.asBoolean();

        t = conf.get("@suffix");
        String post = "";
        if (t != null) post = t.asText().trim();

        if (!name.isEmpty()) return name;

        if (useParentKeyAsPrefix) pre = parent + "_";
        if (useParentKeyAsSuffix) post = "_" + parent;
        return pre + key + post;
    }

    public static Object invokeDynamic(String methodName, List<Object> rawInputs) {
        Object value = null;
        Class<?>[] paramTypes = new Class[rawInputs.size()];
        try {
            for (int i = 0; i < rawInputs.size(); i++)
                paramTypes[i] = rawInputs.get(i).getClass();

            Class<?>[] inputTypes = rawInputs.stream().map(Object::getClass).toArray(Class[]::new);
            Method m = TransformUtils.findMatchingMethod(TransformUtils.class, methodName, inputTypes);
            if (m == null) throw new NoSuchMethodException("No matching overload");
            value = m.invoke(null, rawInputs.toArray());

        } catch (SecurityException | NoSuchMethodException | IllegalArgumentException e) {
//            System.out.println("!error invoking method: " + methodName);
//            System.out.println("Method: " + methodName);
//            System.out.println("Expected params:");
            for (Class<?> c : paramTypes)
                System.out.println("  " + c);
            e.printStackTrace();
        } catch (InvocationTargetException e) {
//            System.out.println("!error invoking method: " + methodName);
            e.printStackTrace();
        } catch (IllegalAccessException e) {
//            System.out.println("!error invoking method: " + methodName);
            e.printStackTrace();
        } catch (Exception e) {
//            System.out.println("!error invoking method: " + methodName);
//            e.printStackTrace();
        }
        return value;
    }

    public static List<Map<String, Object>> flattenList(String parent, JsonNode keyCfg, List<Map<String, Object>> subRec) {
        List<Map<String, Object>> subRecords = new ArrayList<>();

        subRec.forEach(e -> {
            List<Map<String, Object>> x = transformRecord(parent, keyCfg, e);
            subRecords.addAll(x);
        });

        if (keyCfg == null) return subRecords;
        else {
            List<String> keys = getGroupByKeys(keyCfg);
            List<List<Map<String, Object>>> subRecordGroups = new ArrayList<>();
            if (!keys.isEmpty()) { // grouping conf available for flatten
                Map<Object, List<Map<String, Object>>> subRecordGroupsMap = TransformUtils.groupedBy(subRecords, keys); // group by
                subRecordGroups.addAll(subRecordGroupsMap.values());
            } else subRecordGroups.add(subRecords);

            subRecordGroups.forEach(group -> { // reduce all sub record
                Optional<Map<String, Object>> b = reduce(keyCfg, group);
                b.ifPresent(records::add);
            });
        }

//        List<Map<String, Object>> newList = new ArrayList<>();
//        subRecords.forEach(m -> {
//            LinkedHashMap<String, Object> mapWithUpdatedKeys = new LinkedHashMap<>();
//            m.forEach((k, v) -> mapWithUpdatedKeys.put(prefix + k + postfix, v));
//            newList.add(mapWithUpdatedKeys);
//        });
        return subRecords;
    }

    public static Optional<Map<String, Object>> reduce(JsonNode conf, List<Map<String, Object>> mapsList) {
        JsonNode cnf = conf.get("@reduce");
        Optional<Map<String, Object>> reduced = mapsList.stream().reduce((m1, m2) -> {
            Map<String, Object> result = new LinkedHashMap<>(m1);
            m2.forEach((k, v) -> {
                if (cnf != null) {
                    JsonNode op = cnf.get(k);
                    if (op != null) {
                        Object[] p = {m1.get(k), v};
                        Object o = null;
//                                try {
//                                    o = execMethod(op.asText(), p);
//                                } catch (NoSuchMethodException e) {
//                                    e.printStackTrace();
////                            throw new RuntimeException(e);
//                                } catch (InvocationTargetException e) {
//                                    e.printStackTrace();
////                            throw new RuntimeException(e);
//                                } catch (IllegalAccessException e) {
//                                    e.printStackTrace();
////                            throw new RuntimeException(e);
//                                }
                        result.put(k, o);
                    } else // Override or add
                        result.put(k, v);
                } else // Override or add
                    result.put(k, v);
            });
            return result;
        });

        JsonNode transformCfg = conf.get("@transform");
//        Map<String, List<String>> cfg = getTxConf(transformCfg);
        reduced.ifPresent(r -> {
//                    for (Map.Entry<String, List<String>> entry : cfg.entrySet()) {
//                        String k = entry.getKey();
//                        List<String> v1 = entry.getValue();
//                        String op = v1.get(0).trim();
//                        Object[] p = getParams(v1, r);
//                        Object o = null;
//                        try {
//                            o = execMethod(op, p);
//                        } catch (NoSuchMethodException e) {
//                            e.printStackTrace();
////                            throw new RuntimeException(e);
//                        } catch (InvocationTargetException e) {
//                            e.printStackTrace();
////                            throw new RuntimeException(e);
//                        } catch (IllegalAccessException e) {
//                            e.printStackTrace();
////                            throw new RuntimeException(e);
//                        }
//                        r.put(k, o);
//                    }
        });
        return reduced;
    }

    public static List<String> getGroupByKeys(JsonNode conf) { // Array of $fieldName
        JsonNode t = conf.get("@group");
        List<String> groupByKeys = new ArrayList<>();
        if (t != null && t.isArray()) for (JsonNode e : t)
            groupByKeys.add(e.asText());
        return groupByKeys;
    }

    public static List<Map<String, Object>> cartesianJoin(List<Map<String, Object>> rs1, List<Map<String, Object>> rs2) {
        List<Map<String, Object>> recSet = new ArrayList<>();
        rs1.forEach(r1 -> rs2.forEach(r2 -> {
            Map<String, Object> x = new LinkedHashMap<>(r1);
            x.putAll(r2);
            recSet.add(x);
        }));
        return recSet;
    }

//    private static Optional<Map<String, Object>> handleDirectAssignment(String methodName, String key,
//                                                                        Map<String, Object> valueNode, Map<String, Object> txValues) {
//        if (methodName != null && !methodName.isEmpty()) {
//            Map<String, Object> t = new HashMap<>();
//            if (methodName.startsWith("\"")) {
//                String val = methodName.substring(1);
//                if (val.endsWith("\""))
//                    val = val.substring(0, val.length() - 1);
//                t.put(key, val);
//                return Optional.of(t);
//            } else if (valueNode != null && methodName.startsWith("$$")) {
//                String p = methodName.substring(2);
//                Object tv = valueNode.get(p);
//                if (tv == null) tv = txValues.get(p);
//                if (tv != null) {
//                    Object v = valueNode.get(tv);
//                    if (v == null) v = txValues.get(p);
//                    t.put(key, v);
//                }
//                return Optional.of(t);
//            } else if (valueNode != null && methodName.startsWith("$")) {
//                String p = methodName.substring(1);
//                Object x = valueNode.get(p);
//                if (x == null) x = txValues.get(p);
//                if (x != null)
//                    t.put(key, valueNode.get(p));
//                return Optional.of(t);
//            }
//        }
//        return Optional.empty();
//    }

//    Map<String, Object> getNamedData(Map<String, Object> taggedRecord) {
//        LinkedHashMap<String, Object> temp = new LinkedHashMap<>();
//        for (Map.Entry<String, Object> entry : taggedRecord.entrySet()) {
//            String name = entry.getKey();
//            Object val = entry.getValue();
//            if (val instanceof Map) {
//                Map<String, Object> map = getNamedData((Map<String, Object>) val);
//                temp.put(name, map);
//                appendSubRecords(name, val);
//            } else if (val instanceof List) {
//                List<Object> list = getNamedList((List<Object>) val);
//                temp.put(name, list);
//                list.forEach(v -> appendSubRecords(name, v));
//            } else
//                temp.put(name, val);
//        }
//        return temp;
//    }
//
//    List<Object> getNamedList(List<Object> val) {
//        List<Object> sList = new ArrayList<>();
//        for (Object x : val) {
//            if (x instanceof Map) {
//                Map<String, Object> map = getNamedData((Map<String, Object>) x);
//                sList.add(map);
//            } else if (x instanceof List) {
//                List<Object> l = getNamedList((List<Object>) x);
//                sList.add(l);
//            } else
//                sList.add(x);
//        }
//        return sList;
//    }
//
//    Map<String, List<Object>> subRecords = new LinkedHashMap<>();
//
//    void appendSubRecords(String recType, Object node) {
//        List<Object> l = subRecords.get(recType);
//        if (l == null) {
//            l = new ArrayList<>();
//            l.add(node);
//            subRecords.put(recType, l);
//        } else
//            l.add(node);
//    }


//    public static Object[] getParams(List<String> v, Map<String, Object> r) {
//        Object[] o = new Object[v.size() - 1];
//        for (int i = 1; i < v.size(); i++) {
//            String e = v.get(i);
//            if (e.startsWith("$")) {
//                Object x = r.get(e.substring(1));
//                if (x == null) x = "";
//                o[i - 1] = x;
//            } else if (e.contains("::")) {
//                String[] t = e.split("::");
//                if (t.length > 1) {
//                    String type = t[1];
//                    if (type.equalsIgnoreCase("int") || type.equalsIgnoreCase("integer"))
//                        o[i - 1] = Integer.parseInt(t[0]);
//                } else o[i - 1] = t[0];
//            } else
//                o[i - 1] = e;
//        }
//        return o;
//    }


//    public static Map<String, String> getMapConf(JsonNode t) { //Map of $fieldName : operation
//        Map<String, String> m = new LinkedHashMap<>();
//        if (t != null)
//            t.fieldNames().forEachRemaining(e -> m.put(e, t.get(e).asText()));
//        return m;
//    }
//
//    public void mergeSubRecords
//            (Map<String, List<Map<String, Object>>> root, Map<String, List<Map<String, Object>>> temp) {
//        temp.forEach((recType, node) -> root.forEach((t, n) -> {
//            List<Map<String, Object>> l = temp.get(t);
//            if (l == null)
//                temp.put(t, n);
//            else
//                l.addAll(n);
//        }));
//    }

//    public static Map<String, List<String>> getTxConf(JsonNode t) { // Array of Map of op and $fieldName as params
//        Map<String, List<String>> transform = new LinkedHashMap<>();
//        if (t != null && t.isArray())
//            for (JsonNode e : t) {
//                e.fields().forEachRemaining(entry -> {
//                    String k = entry.getKey();
//                    JsonNode v = entry.getValue();
//                    if (v != null && v.isArray()) {
//                        List<String> l = new ArrayList<>();
//                        for (JsonNode f : v) l.add(f.asText());
//                        transform.put(k, l);
//                    }
//                });
//            }
//        return transform;
//    }

//    public static Object execMethod(String methodName, Object[] o) throws
//            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
//        Method method;
//        try {
//            if (o.length == 2) {
//                method = clazz.getMethod(methodName, Object.class, Object.class);
//                return method.invoke(null, o[0], o[1]);
//            } else if (o.length == 1) {
//                method = clazz.getMethod(methodName, Object.class);
//                return method.invoke(null, o[0]);
//            } else if (o.length == 3) {
//                method = clazz.getMethod(methodName, Object.class, Object.class, Object.class);
//                return method.invoke(null, o[0], o[1], o[1]);
//            }
//        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
//            AtomicReference<String> m1 = new AtomicReference<>("");
//            Arrays.asList(o).forEach(x -> m1.set(m1.get() + ", param:" + x));

////            System.out.println("Tx Failed ! op: " + op + ", " + m);
////            logger.error(e.getMessage(), e);

//        }
//        return "";
//    }

//     JsonNode finalNodeConfig = elementConfig;
//     List<Map<String, Object>> subRecords =
//             subRecord.entrySet().stream()
//                     .findFirst()
//                     .map(e -> {
//                         String k = e.getKey();
//                         List<Map<String, Object>> v = (List<Map<String, Object>>) e.getValue();
//
//                         JsonNode confList = finalNodeConfig.get(elementName);
//                         {
//                             List<Map<String, Object>> val = new ArrayList<>();
//                             if (confList != null)
//                                 val = flattenList(elementName, confList, v);
//
//                             List<Map<String, Object>> t = subRecordLists.get(k);
//                             if (t != null)
//                                 t.addAll(val);
//                             else
//                                 subRecordLists.put(k, new ArrayList<>(val));
//                             return val;
//                         }
//
//                     }).orElse(new ArrayList<>());  // default value}
//                }

// @join with parent

//    private static void handleMapElement(String name, JsonNode conf, Map<String, Object> value, Map<String, List<Map<String, Object>>> subRecordLists) {
//        if (conf == null) {
//            return;
//        }
//        // check for global config --update config
//        JsonNode config = conf.get("@useGlobalConfig");
//        if (config != null && config.asBoolean())
//            conf = txConfig.get(name);
//
//        List<Map<String, Object>> txValues;
//        JsonNode txConf = conf.get("@transform");
//        if (txConf != null && !txConf.asText().isEmpty()) {
//            Map<String, Object> val = transformValue(name, conf, value);
//            txValues = Collections.singletonList(val);
//        } else
//            txValues = transformRecord(name, conf, value);
//
//        List<Map<String, Object>> t = subRecordLists.get(name);
//        if (t != null)
//            t.addAll(txValues);
//        else
//            subRecordLists.put(name, new ArrayList<>(txValues));
//    }


}
