package com.gamma.asn.transform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Faithful port of the legacy transformer2.Transformer row semantics, minus its static
 * state: one engine per pipeline, safe to hold N per JVM (REDESIGN.md §4.4).
 *
 * Semantics kept verbatim (validated against the golden-corpus rows):
 * - per-field config lookup falls back to a same-named TOP-LEVEL config section;
 * - map values flatten into the shared row; list-of-map values become sub-record sets
 *   that cartesian-join with each other and overlay the row fields;
 * - {@code @transform}/{@code "fn(args)"} specs: {@code "literal"}, {@code $field},
 *   {@code $$indirect}, {@code @self}; a failed/unknown function yields a null value;
 *   a $param that evaluates to null drops the field (legacy exception path);
 * - {@code @keepSource}, {@code @derivedFields}, {@code @rename}/{@code @prefix}/
 *   {@code @suffix}/{@code @useParentKeyAsPrefix|Suffix}, {@code @autoJoin};
 * - legacy {@code @group}/{@code @reduce} only ever appended to a static list nobody
 *   read — they do not affect row output and are deliberately not implemented.
 */
public final class LegacyTransformEngine {

    private final Map<String, Object> txConfig;
    private final FunctionRegistry functions;

    public LegacyTransformEngine(TxConfig config, FunctionRegistry functions) {
        this.txConfig = config.root();
        this.functions = functions;
    }

    public List<Map<String, Object>> transform(String recordType, Map<String, Object> record) {
        if (record.isEmpty()) {
            return new ArrayList<>();
        }
        Object conf = txConfig.get(recordType);
        return transformRecord(recordType, conf, record);
    }

    private List<Map<String, Object>> transformRecord(String parent, Object nodeConfig,
                                                      Map<String, Object> valueNode) {
        Map<String, Object> resultFields = new LinkedHashMap<>();
        return handleMap(parent, nodeConfig, valueNode, resultFields);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> handleMap(String parent, Object eventConfig,
                                                Map<String, Object> event,
                                                Map<String, Object> resultFields) {
        Map<String, List<Map<String, Object>>> subRecords = new LinkedHashMap<>();

        for (Map.Entry<String, Object> kv : event.entrySet()) {
            String fieldName = kv.getKey();
            Object fieldValue = kv.getValue();
            Object config = get(eventConfig, fieldName);
            if (isNullOrEmpty(config) && txConfig.containsKey(fieldName)) {
                config = txConfig.get(fieldName);
            }

            boolean autoJoin = true;
            if (config instanceof Map<?, ?> cfgMap && !cfgMap.isEmpty()) {
                Object aj = cfgMap.get("@autoJoin");
                if (aj != null) {
                    autoJoin = Boolean.parseBoolean(String.valueOf(aj));
                }
                if (cfgMap.containsKey("@keepSource")
                        && Boolean.parseBoolean(String.valueOf(cfgMap.get("@keepSource")))) {
                    resultFields.put(fieldName, fieldValue);
                }
                if (cfgMap.containsKey("@transform")) {
                    resultFields.putAll(processLeafObject(fieldName, parent, config, fieldValue));
                    autoJoin = false;
                }
                if (cfgMap.containsKey("@derivedFields") && !event.isEmpty()) {
                    processDerivedFields(cfgMap.get("@derivedFields"), event, resultFields);
                }
            }

            if (fieldValue instanceof Map<?, ?> val) {
                if (autoJoin) {
                    List<Map<String, Object>> temp =
                            handleMap(parent, config, (Map<String, Object>) val, resultFields);
                    subRecords.computeIfAbsent(fieldName, k -> new ArrayList<>()).addAll(temp);
                }
            } else if (fieldValue instanceof List<?> list) {
                if (autoJoin && list.stream().allMatch(e -> e instanceof Map<?, ?>)) {
                    List<Map<String, Object>> flattened = new ArrayList<>();
                    for (Object element : list) {
                        flattened.addAll(transformRecord(fieldName, config,
                                (Map<String, Object>) element));
                    }
                    subRecords.computeIfAbsent(fieldName, k -> new ArrayList<>()).addAll(flattened);
                }
            } else {
                resultFields.putAll(processLeafObject(fieldName, parent, config, fieldValue));
            }
        }

        Object derived = get(eventConfig, "@derivedFields");
        if (derived != null && !event.isEmpty()) {
            processDerivedFields(derived, event, resultFields);
        }
        return combineResults(resultFields, subRecords);
    }

    private Map<String, Object> processLeafObject(String name, String parent, Object conf,
                                                  Object value) {
        Map<String, Object> txMap = new LinkedHashMap<>();
        if (conf == null) {
            txMap.put(name, value);
            return txMap;
        }
        Map<String, Object> result = checkConstantAndInvoke(name, conf, value);
        if (result != null) {
            for (Map.Entry<String, Object> e : result.entrySet()) {
                txMap.put(modifyKey(e.getKey(), parent, conf), e.getValue());
            }
        } else {
            txMap.put(modifyKey(name, parent, conf), value);
        }
        return txMap;
    }

    private Map<String, Object> checkConstantAndInvoke(String key, Object conf, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        MethodAndParams mp = new MethodAndParams(conf);
        if (mp.isConstant()) {
            result.put(key, mp.constantValue());
            return result;
        }
        try {
            List<Object> params = mp.paramValues(value, result);
            result = applyTransformation(key, value, mp.methodName, params);
        } catch (Exception e) {
            // legacy: logged and returned the (empty) result — the field is dropped
        }
        return result;
    }

    private Map<String, Object> populateDerivedField(String key, Object conf,
                                                     Map<String, Object> values,
                                                     Map<String, Object> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            MethodAndParams mp = new MethodAndParams(conf);
            if (mp.isConstant()) {
                result.put(key, mp.constantValue());
                return result;
            }
            if (mp.isVariable()) {
                result.put(key, mp.paramValue(mp.methodName, values, fields));
                return result;
            }
            List<Object> params = mp.paramValues(values, fields);
            result = applyTransformation(key, values, mp.methodName, params);
        } catch (Exception e) {
            // legacy: swallowed — derived field silently absent
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void processDerivedFields(Object config, Map<String, Object> valueNode,
                                      Map<String, Object> fields) {
        if (!(config instanceof Map<?, ?> cfg)) {
            return;
        }
        for (Map.Entry<?, ?> e : cfg.entrySet()) {
            Map<String, Object> derived = populateDerivedField(String.valueOf(e.getKey()),
                    e.getValue(), valueNode, fields);
            derived.forEach((k, v) -> {
                if (v instanceof Map) {
                    fields.putAll((Map<String, Object>) v);
                } else {
                    fields.put(k, v);
                }
            });
        }
    }

    private Map<String, Object> applyTransformation(String key, Object input, String method,
                                                    List<Object> params) {
        Object newValue = input;
        boolean transformed = false;
        if (method != null && !method.isEmpty()) {
            newValue = functions.invoke(method, params);
            transformed = true;
        }
        Map<String, Object> txValues = new LinkedHashMap<>();
        if (transformed && newValue instanceof Map<?, ?> m) {
            m.forEach((k, v) -> txValues.put(String.valueOf(k), v));
        } else {
            txValues.put(key, newValue);
        }
        return txValues;
    }

    private static List<Map<String, Object>> combineResults(
            Map<String, Object> record, Map<String, List<Map<String, Object>>> subRecords) {
        Optional<List<Map<String, Object>>> joined =
                subRecords.values().stream().reduce(LegacyTransformEngine::cartesianJoin);
        List<Map<String, Object>> out = new ArrayList<>();
        if (joined.isPresent()) {
            for (Map<String, Object> sub : joined.get()) {
                Map<String, Object> row = new LinkedHashMap<>(record);
                row.putAll(sub);
                out.add(row);
            }
        } else if (!record.isEmpty()) {
            out.add(new LinkedHashMap<>(record));
        }
        return out;
    }

    private static List<Map<String, Object>> cartesianJoin(List<Map<String, Object>> rs1,
                                                           List<Map<String, Object>> rs2) {
        List<Map<String, Object>> recSet = new ArrayList<>();
        for (Map<String, Object> r1 : rs1) {
            for (Map<String, Object> r2 : rs2) {
                Map<String, Object> x = new LinkedHashMap<>(r1);
                x.putAll(r2);
                recSet.add(x);
            }
        }
        return recSet;
    }

    private static String modifyKey(String key, String parent, Object conf) {
        if (!(conf instanceof Map<?, ?> c)) {
            return key;
        }
        Object rename = c.get("@rename");
        if (rename != null && !String.valueOf(rename).trim().isEmpty()) {
            return String.valueOf(rename).trim();
        }
        String pre = c.get("@prefix") != null ? String.valueOf(c.get("@prefix")).trim() : "";
        String post = c.get("@suffix") != null ? String.valueOf(c.get("@suffix")).trim() : "";
        if (truthy(c.get("@useParentKeyAsPrefix"))) {
            pre = parent + "_";
        }
        if (truthy(c.get("@useParentKeyAsSuffix"))) {
            post = "_" + parent;
        }
        return pre + key + post;
    }

    private static boolean truthy(Object v) {
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static Object get(Object config, String name) {
        return config instanceof Map<?, ?> m ? m.get(name) : null;
    }

    private static boolean isNullOrEmpty(Object config) {
        if (config == null) {
            return true;
        }
        if (config instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return config instanceof String s && s.isEmpty();
    }

    /** "fn(p1, p2)" / "\"literal\"" / "$var" spec, from a string or an @transform entry. */
    private final class MethodAndParams {

        final String methodName;
        final List<String> params = new ArrayList<>();

        MethodAndParams(Object conf) {
            String spec = null;
            if (conf instanceof String s) {
                spec = s;
            } else if (conf instanceof Map<?, ?> m && m.get("@transform") != null) {
                spec = String.valueOf(m.get("@transform"));
            }
            if (spec == null || spec.isEmpty()) {
                this.methodName = "";
                return;
            }
            String def = spec.trim();
            this.methodName = def.contains("(")
                    ? def.substring(0, def.indexOf('(')).trim() : def;
            int open = def.indexOf('(');
            int close = def.lastIndexOf(')');
            if (open != -1 && close > open) {
                for (String p : def.substring(open + 1, close).split(",")) {
                    if (!p.trim().isEmpty()) {
                        params.add(p.trim());
                    }
                }
            }
        }

        boolean isConstant() {
            return methodName.startsWith("\"") && methodName.endsWith("\"") && methodName.length() >= 2;
        }

        boolean isVariable() {
            return methodName.startsWith("$") || methodName.startsWith("@");
        }

        String constantValue() {
            return methodName.substring(1, methodName.length() - 1);
        }

        List<Object> paramValues(Object values, Map<String, Object> fields) throws Exception {
            List<Object> in = new ArrayList<>();
            for (String param : params) {
                Object pv = paramValue(param, values, fields);
                if (pv == null) {
                    throw new Exception("param value could not be evaluated: " + param);
                }
                in.add(pv);
            }
            return in;
        }

        @SuppressWarnings("unchecked")
        Object paramValue(String param, Object value, Map<String, Object> fields) {
            if (param.equalsIgnoreCase("@self")) {
                return value;
            }
            Map<String, Object> valueNode =
                    value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            if (param.startsWith("$$")) {
                String p = param.substring(2);
                Object v1 = valueNode.get(p); // legacy NPEs on non-map values — kept
                if (v1 == null && fields != null) {
                    v1 = fields.get(p);
                }
                return v1 == null ? null : valueNode.get(v1);
            }
            if (param.startsWith("$")) {
                String p = param.substring(1);
                Object v = valueNode.get(p);
                if (v == null) {
                    v = fields.get(p);
                }
                return v;
            }
            return param;
        }
    }
}
