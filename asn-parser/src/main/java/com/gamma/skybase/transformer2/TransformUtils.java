package com.gamma.skybase.transformer2;

import com.fasterxml.jackson.databind.JsonNode;
//import com.gamma.components.commons.DateUtility;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

public class TransformUtils {
    private static JsonNode cache;

    private static final ThreadLocal<SimpleDateFormat> sdfT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd HH:mm:ss"));
    private static final ThreadLocal<SimpleDateFormat> sdf = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));
    private static final ThreadLocal<SimpleDateFormat> sdfS = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyMMddHHmmss Z"));

    public static void setCache(JsonNode cache) {
        if (TransformUtils.cache == null)
            TransformUtils.cache = cache;
    }

    public static Object ccnEventType(Map<String, Object> node, String serviceIdentifier) {
        String eventType = "";

        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> {
                    return record.get(serviceIdentifier);
                }) //   1. Map each record (Map) to its 'serviceIdentifier' value.
                .filter(Objects::nonNull)   //  2. Filter out any null values (in case the field is missing or not a String).
                .collect(toList());  //     3.  Collect the valid service identifier strings into a List.
        String serviceId = x.get(0).toString();
        if (serviceId.equals("0")) {
            eventType = "voice";
        } else if (serviceId.equals("4")) {
            eventType = "SMS";
        } else if (serviceId.equals("5")) {
            eventType = "GPRS";
        } else if (serviceId.equals("7")) {
            eventType = "Video Telephony";
        }
        return eventType;
    }

    public static Object ccnEventTypeKey(Map<String, Object> node, String serviceIdentifier) {
        String eventTypeKey = "";
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> {
                    return record.get(serviceIdentifier);
                }) //   1. Map each record (Map) to its 'serviceIdentifier' value.
                .filter(Objects::nonNull)   //  2. Filter out any null values (in case the field is missing or not a String).
                .collect(toList());  //     3.  Collect the valid service identifier strings into a List.
        String serviceId = x.get(0).toString();
        if (serviceId.equals("0")) {
            eventTypeKey = "1";
        } else if (serviceId.equals("4")) {
            eventTypeKey = "2";
        } else if (serviceId.equals("5")) {
            eventTypeKey = "4";
        } else if (serviceId.equals("7")) {
            eventTypeKey = "7";
        }
        return eventTypeKey;
    }

    public static String ccnSubscriberType(Map<String, Object> node, String cCAccountData, String serviceClassID) {
        BigInteger x = ccnServiceKey(node, cCAccountData, serviceClassID);
        String st = "";
        if (x.intValue() < 300) st = "PREPAID";
        if (x.intValue() == 307) st = "POSTPAID";
        if (x.intValue() == 308) st = "CORP";
        return st;
    }

    public static String ccnFnfNumber(Map<String, Object> node, String cCAccountData, String familyAndFriendsNo) {
        Map<String, Object> node1 = unifyCCRecord(node);
        String family = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> record.get(cCAccountData))
                .filter(Objects::nonNull)
                .map(obj -> (Map<String, Object>) obj)
                .map(ccData -> ccData.get(familyAndFriendsNo))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst()
                .orElse(null);

        String fnfNum = "";
        if (family != null) {
            fnfNum = ltrim(family.substring(2), '0');
            if (!fnfNum.startsWith("93")) {
                fnfNum = "93" + fnfNum;
            }
        }
        return fnfNum;
    }

    public static Map<String, String> occServedSubscriptionIds(Map<String, Object> node) {
        Map<String, String> result = new HashMap<>();
        safeGetList(node, "servedSubscriptionIDs").stream()
                .map(item -> safeGetMap(item, "subscriptionID"))
                .filter(Objects::nonNull)
                .forEach(sub -> {
                    Object typeObj = sub.get("subscriptionIDType");
                    Object valueObj = sub.get("subscriptionIDValue");

                    if (typeObj == null || valueObj == null) return;
                    int type = ((Number) typeObj).intValue();
                    String value = String.valueOf(valueObj);

                    if (type == 0) {
                        result.put("SERVED_MSISDN", value);
                    } else if (type == 1) {
                        result.put("SERVED_IMSI", value);
                    }
                });
        return result;
    }

    public static String ccnCellId(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);

        List<Map<String, Object>> params =
                safeGetList(node1, "creditControlRecord").stream()
                        .map(record -> safeGetMap(record, "chargingContextSpecific"))
                        .flatMap(ccs -> safeGetList(ccs, "contextParameter").stream())
                        .collect(Collectors.toList());

        int[] ids = {16778249, 16778250, 16778251};

        for (int id : ids) {
            for (Map<String, Object> param : params) {
                Integer pid = safeGetInteger(param, "parameterID");
                if (pid != null && pid == id) {
                    String val = safeGetString(safeGetMap(param, "parameterValue"), "string");
                    return (val != null ? val : "41240");
                }
            }
        }
        return "41240";
    }

    public static String ccnSmscCenter(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);

        return safeGetList(node1, "creditControlRecord").stream()
                .map(record -> safeGetMap(record, "chargingContextSpecific"))
                .flatMap(ccs -> safeGetList(ccs, "contextParameter").stream())
                .filter(param -> {
                    Integer pid = safeGetInteger(param, "parameterID");
                    return pid != null && pid == 16778225;
                })
                .map(param -> {
                    Map<String, Object> pv = safeGetMap(param, "parameterValue");
                    return safeGetString(pv, "string");
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

//    public static Map<String, String> getStartEndTime(Map<String, Object> node) throws ParseException {
//
//        Map<String, Object> node1 = unifyCCRecord(node);
//        List<Map<String, Object>> list = (List<Map<String, Object>>) node1.get("creditControlRecord");
//        Map<String, String> result = new HashMap<>();
//        if (list == null || list.isEmpty()) return result;
//
//        Map<String, Object> first = list.get(0);
//        String firstTrigger = (String) first.get("triggerTime");
//        result.put("startTime", getConvertedXdrDate(firstTrigger));
//        result.put("xdrDate", getConvertedXdrDate(firstTrigger));
//        result.put("eventTimeSlot", eventTimeSlot);
//
//        Map<String, Object> last = list.get(list.size() - 1);
//        String lastEvent = (String) last.get("eventTime");
//        result.put("endTime", getConvertedDate(lastEvent));
//
//        return result;
//    }

//    public static String getConvertedDate(String timestamp) throws ParseException {
//        String formatter = "yyyyMMddHHmmssZ";
//        Date date = DateUtility.convertString2JavaUtilDate(timestamp, formatter);
//        ZoneId zoneId = ZoneId.of(ZoneOffset.ofTotalSeconds(270 * 60).getId());
//
//        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(date.toInstant(), zoneId);
//        String clientLocalTime = zonedDateTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
//        String clientLocalDate = zonedDateTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
//        return clientLocalTime;
//    }

    static String eventTimeSlot;

//    public static String getConvertedXdrDate(String timestamp) throws ParseException {
//        String formatter = "yyyyMMddHHmmssZ";
//        Date date = DateUtility.convertString2JavaUtilDate(timestamp, formatter);
//        ZoneId zoneId = ZoneId.of(ZoneOffset.ofTotalSeconds(270 * 60).getId());
//
//        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(date.toInstant(), zoneId);
//        String clientLocalTime = zonedDateTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
//        eventTimeSlot = zonedDateTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd HH:00:00"));
//        return clientLocalTime;
//    }

    public static String ccnServedMsrn(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        Object creditControlRecord = node1.get("creditControlRecord");
        List<String> y = new ArrayList<>();
        if (creditControlRecord instanceof List) {
            y = safeGetList(node1, "creditControlRecord").stream()
                    .map(record -> {
                        return safeGetMap(record, "chargingContextSpecific");
                    })
                    .flatMap(ccs -> {
                        return safeGetList(ccs, "contextParameter").stream();
                    })
                    .map(param -> {
                        return safeGetMap(param, "parameterValue");
                    })
                    .map(paramValue -> {
                        return safeGetString(paramValue, "string");
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return y.isEmpty() ? "" : y.get(0);
    }

    public static String ccnChargedAccName(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(node1, "creditControlRecord");
        String accGroupId = ccrList.stream()
                .map(record -> safeGetMap(record, "cCAccountData"))
                .map(ccAccData -> ccAccData.get("accountGroupID"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst()
                .orElse("");
        return accGroupId;
    }

    public static Map<String, Object> ccnDedicatedCharge(Map<String, Object> node) {

        Map<String, Object> node1 = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(node1, "creditControlRecord");

        Map<Integer, Long> amountSum = new LinkedHashMap<>();    // Sum per DA ID
        Map<Integer, Integer> acctIdOrder = new LinkedHashMap<>();  // Order of first appearance

        int orderIndex = 1;

        for (Map<String, Object> acc : ccrList) {
            Map<String, Object> accData = safeGetMap(acc, "cCAccountData");
            Map<String, Object> dedAccs = safeGetMap(accData, "dedicatedAccounts");
            List<Map<String, Object>> dedList = safeGetList(dedAccs, "dedicatedAccount");
            for (Map<String, Object> da : dedList) {
                Integer daId = getInt(da.get("dedicatedAccountID"));
                if (daId == null) continue;
                acctIdOrder.putIfAbsent(daId, orderIndex++);
                Map<String, Object> valBefore = safeGetMap(da, "dedicatedAccountChange");
                if (valBefore == null) continue;
                Long amount = getLong(valBefore.get("amount"));
                if (amount == null) amount = 0L;
                amountSum.put(daId, amountSum.getOrDefault(daId, 0L) + amount);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        int idx = 1;

        for (Integer daId : acctIdOrder.keySet()) {
            long total = amountSum.getOrDefault(daId, 0L);
            double finalCharge = total / 1000000.0;
            finalCharge = Double.parseDouble(String.format("%.2f", finalCharge));

            result.put("dedi_acc_id" + idx, daId);
            result.put("dedi_acc_amt" + idx, finalCharge);
            idx++;
        }
        return result;
    }

    public static Map<String, Object> occGprsDedicatedCharge(Map<String, Object> node) {

        Map<String, Object> unified = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(unified, "creditControlRecord");
        Map<Integer, Long> amountSum = new LinkedHashMap<>();
        Map<Integer, Integer> decimalsMap = new HashMap<>();
        Map<Integer, Integer> acctIdOrder = new LinkedHashMap<>();

        int orderIndex = 1;
        if (ccrList == null) return new LinkedHashMap<>();

        for (Map<String, Object> ccr : ccrList) {
            Map<String, Object> accData = safeGetMap(ccr, "cCAccountData");
            if (accData == null) continue;
            List<Map<String, Object>> dedAccList = safeGetInnerList(accData, "dedicatedAccounts");
            if (dedAccList == null) continue;
            for (Map<String, Object> wrapper : dedAccList) {
                Map<String, Object> da = safeGetMap(wrapper, "dedicatedAccount");
                if (da == null) continue;
                Integer daId = getInt(da.get("dedicatedAccountID"));
                if (daId == null) continue;
                acctIdOrder.putIfAbsent(daId, orderIndex++);
                Map<String, Object> change = safeGetMap(da, "dedicatedAccountChange");
                if (change == null) continue;
                Long amount = getLong(change.get("amount"));
                Integer decimals = getInt(change.get("decimals"));
                if (amount == null) amount = 0L;
                if (decimals == null) decimals = 0;
                amountSum.put(daId, amountSum.getOrDefault(daId, 0L) + amount);
                decimalsMap.putIfAbsent(daId, decimals);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        int idx = 1;

        for (Integer daId : acctIdOrder.keySet()) {
            long total = amountSum.getOrDefault(daId, 0L);
            int decimals = decimalsMap.getOrDefault(daId, 0);
            double divisor = Math.pow(10, decimals);
            double finalCharge = total / divisor;
            finalCharge = Double.parseDouble(String.format("%.2f", finalCharge));
            result.put("dedi_acc_id" + idx, daId);
            result.put("dedi_acc_amt" + idx, finalCharge);
            idx++;
        }
        return result;
    }

    private static Integer getInt(Object obj) {
        try {
            return obj == null ? null : Integer.parseInt(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long getLong(Object obj) {
        try {
            return obj == null ? null : Long.parseLong(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }


    public static String ccnSrvTypeKey(Map<String, Object> node, String cCAccountData, String serviceClassID) {
        BigInteger x = ccnServiceKey(node, cCAccountData, serviceClassID);
        String st = "";
        if (x.intValue() >= 300) st = "1";
        else st = "2";
        return st;
    }

    public static int ccnBillablePulse(BigInteger originalDur, String pulse) {
        BigInteger[] x = originalDur.divideAndRemainder(new BigInteger(pulse));
        return (x[1].intValue() == 0) ? x[0].intValue() : x[0].intValue() + 1;
    }

    public static BigInteger ccnOriginalDur(Map<String, Object> node) {
        BigInteger totalTimeUnit = BigInteger.ZERO;

        Map<String, Object> node1 = unifyCCRecord(node);
        Object p = node1.get("creditControlRecord");
        if (p instanceof Map) {

        } else if (p instanceof List) {
            List<Map<String, Object>> a = (List<Map<String, Object>>) node1.get("creditControlRecord");
            List x = a.stream()
                    .map(m -> m.get("usedServiceUnits")).collect(toList());
            totalTimeUnit = calculateTotalSum(x, "timeUnit");
            BigInteger totalTariffChangeUsage = calculateTotalSum(x, "tariffChangeUsage");
        }
        return totalTimeUnit;
    }

    public static BigInteger occGprsOriginalDur(Map<String, Object> node) {
        Map<String, Object> unified = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(unified, "creditControlRecord");
        if (ccrList == null) return BigInteger.ZERO;
        BigInteger totalOctets = BigInteger.ZERO;
        for (Map<String, Object> ccr : ccrList) {
            List<Map<String, Object>> usuList = safeGetList(ccr, "usedServiceUnits");
            if (usuList == null) continue;
            for (Map<String, Object> usuWrap : usuList) {
                Map<String, Object> usu = safeGetMap(usuWrap, "usedServiceUnit");
                if (usu == null) continue;
                BigInteger value = toBigInteger(usu.get("totalOctetsUnit"));
                if (value != null)
                    totalOctets = totalOctets.add(value);
            }
        }
        return totalOctets;
    }

    public static int ccnZeroDurationInd(BigInteger duration) {
        return duration.intValue() == 0 ? 1 : 0;
    }

    public static Object ccnEventDir(Map<String, Object> node, String serviceScenario) {
        String eventDir = "";
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> {
                    return record.get(serviceScenario);
                }) //   1. Map each record (Map) to its 'serviceIdentifier' value.
                .filter(Objects::nonNull)   //  2. Filter out any null values (in case the field is missing or not a String).
                .collect(toList());  //     3.  Collect the valid service identifier strings into a List.
        String serviceId = x.get(0).toString();
        if (serviceId.equals("0")) {
            eventDir = "1";
        } else if (serviceId.equals("1")) {
            eventDir = "3";
        } else if (serviceId.equals("2")) {
            eventDir = "2";
        }
        return eventDir;
    }

    //      find unique roamingPosition
    public static String ccnRoamingPosition(Map<String, Object> node) {

        Map<String, Object> node1 = unifyCCRecord(node);
        Object recordValue = node1 != null ? node1.get("creditControlRecord") : null;   // 1. Safely get the value associated with the "creditControlRecord" key.

        @SuppressWarnings("unchecked")          // 2. Safely cast the record value to a List of Maps (ArrayList@1826).
                List<Map<String, Object>> creditControlRecord = (recordValue instanceof List)
                ? (List<Map<String, Object>>) recordValue : Collections.emptyList();

        List<Object> x = creditControlRecord.stream()
                .filter(Objects::nonNull)        // Filter out any null elements in the creditControlRecord list
                .map(record -> record.get("roamingPosition")) // 3. Map each record (the innermost LinkedHashMap@1829, LinkedHashMap@1829, etc.) to its 'roamingPosition' value.
//                .filter(String.class::isInstance) // 4. Filter out values that are null or not BigInteger (for type safety).
                .map(Object::toString) // 5. Cast the remaining objects to BigInteger.
                .collect(toList());  // 6. Collect the results into a List<BigInteger>.

        return x.isEmpty() ? "" : x.get(0).toString();
    }

    public static Map<String, String> ccnGsnAddres(Map<String, Object> node) {
        Map<String, Object> unified = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(unified, "creditControlRecord");

        Map<String, String> result = new HashMap<>();
        if (ccrList == null) return result;

        Object sgsnAddr = null, ggsnAddr = null;
        String sgsnMccMnc = null, ggsnMccMnc = null, chargingChar = null, apn = null, chargingId = null, ratType = null, qosClassId = null, rateEventType = null;

        for (Map<String, Object> ccr : ccrList) {
            Map<String, Object> context = safeGetMap(ccr, "chargingContextSpecific");
            if (context == null) continue;

            List<Map<String, Object>> params = safeGetList(context, "contextParameter");
            if (params == null) continue;

            for (Map<String, Object> p : params) {

                Object id = p.get("parameterID");
                if (id == null) continue;

                Map<String, Object> val = safeGetMap(p, "parameterValue");
                if (val == null) continue;

                switch (id.toString()) {

                    case "16778229":  // SGSN_ADDRESS
                        Object sgsnOct = val.get("octetString");
                        if (sgsnOct != null) {
                            byte[] b = hexToBytes(sgsnOct.toString());
                            sgsnAddr = ipAddress(b);
                        }
                        break;

                    case "16778239":  // SGSN_MCC_MNC
                        if (val.get("string") != null) {
                            sgsnMccMnc = val.get("string").toString();
                        }
                        break;

                    case "16778230":  // GGSN_ADDRESS
                        Object ggsnOct = val.get("octetString");
                        if (ggsnOct != null) {
                            byte[] b = hexToBytes(ggsnOct.toString());
                            ggsnAddr = ipAddress(b);
                        }
                        break;

                    case "16778233":  // GGSN_MCC_MNC
                        if (val.get("string") != null) {
                            ggsnMccMnc = val.get("string").toString();
                        }
                        break;

                    case "16778238":  // CHARGING_CHARACTERSTICS
                        if (val.get("string") != null) {
                            chargingChar = val.get("string").toString();
                        }
                        break;
                    case "16778235":  // APN
                        if (val.get("string") != null) {
                            apn = val.get("string").toString();
                        }
                        break;
                    case "16778226":  // CHARGING_ID
                        if (val.get("unsigned32") != null) {
                            chargingId = val.get("unsigned32").toString();
                        }
                        break;
                    case "16778240":  // RAT_TYPE
                        if (val.get("unsigned32") != null) {
                            ratType = val.get("unsigned32").toString();
                        }
                        break;
                    case "16778265":  // QOS_CLASS_ID
                        if (val.get("unsigned32") != null) {
                            qosClassId = val.get("unsigned32").toString();
                        }
                        break;
                    case "16778218":  // RATE_EVENT_TYPE
                        if (val.get("string") != null) {
                            rateEventType = val.get("string").toString();
                        }
                        break;
                }
            }
        }

        if (sgsnAddr != null) result.put("SGSN_ADDRESS", sgsnAddr.toString());
        if (sgsnMccMnc != null) result.put("SGSN_MCC_MNC", sgsnMccMnc);
        if (ggsnAddr != null) result.put("GGSN_ADDRESS", ggsnAddr.toString());
        if (ggsnMccMnc != null) result.put("GGSN_MCC_MNC", ggsnMccMnc);
        if (chargingChar != null) result.put("CHARGING_CHARACTERSTICS", chargingChar);
        if (apn != null) result.put("APN", apn);
        if (chargingId != null) result.put("CHARGING_ID", chargingId);
        if (ratType != null) result.put("RAT_TYPE", ratType);
        if (qosClassId != null) result.put("QOS_CLASS_ID", qosClassId);
        if (rateEventType != null) result.put("RATE_EVENT_TYPE", rateEventType);
        return result;
    }

    public static Map<String, String> occGprsGsnAddres(Map<String, Object> node) {

        Map<String, Object> unified = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(unified, "creditControlRecord");

        Map<String, String> result = new HashMap<>();
        if (ccrList == null) return result;

        Object sgsnAddr = null, ggsnAddr = null;
        String sgsnMccMnc = null, ggsnMccMnc = null, chargingChar = null,
                apn = null, chargingId = null, ratType = null,
                qosClassId = null, rateEventType = null;

        for (Map<String, Object> ccr : ccrList) {
            List<Map<String, Object>> ctxList = safeGetList(ccr, "chargingContextSpecific");
            if (ctxList == null) continue;
            for (Map<String, Object> ctx : ctxList) {
                Map<String, Object> p = safeGetMap(ctx, "contextParameter");
                if (p == null) continue;

                Object id = p.get("parameterID");
                if (id == null) continue;
                Map<String, Object> val = safeGetMap(p, "parameterValue");
                if (val == null) continue;
                switch (id.toString()) {
                    case "16778229":  // SGSN_ADDRESS
                        Object sgsnOct = val.get("octetString");
                        if (sgsnOct != null) {
                            sgsnAddr = ipAddress(hexToBytes(sgsnOct.toString()));
                        }
                        break;
                    case "16778239":  // SGSN_MCC_MNC
                        if (val.get("string") != null)
                            sgsnMccMnc = val.get("string").toString();
                        break;
                    case "16778230":  // GGSN_ADDRESS
                        Object ggsnOct = val.get("octetString");
                        if (ggsnOct != null) {
                            ggsnAddr = ipAddress(hexToBytes(ggsnOct.toString()));
                        }
                        break;
                    case "16778233":  // GGSN_MCC_MNC
                        if (val.get("string") != null)
                            ggsnMccMnc = val.get("string").toString();
                        break;
                    case "16778238":  // CHARGING_CHARACTERSTICS
                        if (val.get("string") != null)
                            chargingChar = val.get("string").toString();
                        break;
                    case "16778235":  // APN
                        if (val.get("string") != null)
                            apn = val.get("string").toString();
                        break;
                    case "16778226":  // CHARGING_ID
                        if (val.get("unsigned32") != null)
                            chargingId = val.get("unsigned32").toString();
                        break;
                    case "16778240":  // RAT_TYPE
                        if (val.get("unsigned32") != null)
                            ratType = val.get("unsigned32").toString();
                        break;
                    case "16778265":  // QOS_CLASS_ID
                        if (val.get("unsigned32") != null)
                            qosClassId = val.get("unsigned32").toString();
                        break;
                    case "16778218":  // RATE_EVENT_TYPE
                        if (val.get("string") != null)
                            rateEventType = val.get("string").toString();
                        break;
                }
            }
        }

        if (sgsnAddr != null) result.put("SGSN_ADDRESS", sgsnAddr.toString());
        if (sgsnMccMnc != null) result.put("SGSN_MCC_MNC", sgsnMccMnc);
        if (ggsnAddr != null) result.put("GGSN_ADDRESS", ggsnAddr.toString());
        if (ggsnMccMnc != null) result.put("GGSN_MCC_MNC", ggsnMccMnc);
        if (chargingChar != null) result.put("CHARGING_CHARACTERSTICS", chargingChar);
        if (apn != null) result.put("APN", apn);
        if (chargingId != null) result.put("CHARGING_ID", chargingId);
        if (ratType != null) result.put("RAT_TYPE", ratType);
        if (qosClassId != null) result.put("QOS_CLASS_ID", qosClassId);
        if (rateEventType != null) result.put("RATE_EVENT_TYPE", rateEventType);
        return result;
    }

    public static BigInteger ccnGprsTotalVolume(Map<String, Object> node) {
        Map<String, Object> unified = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(unified, "creditControlRecord");
        if (ccrList == null) return BigInteger.ZERO;
        BigInteger totalOctets = BigInteger.ZERO;
        for (Map<String, Object> ccr : ccrList) {
            List<Map<String, Object>> usuList = safeGetList(ccr, "usedServiceUnits");
            if (usuList == null) continue;
            for (Map<String, Object> usuWrap : usuList) {
                Map<String, Object> usu = safeGetMap(usuWrap, "usedServiceUnit");
                if (usu == null) continue;
                BigInteger value = toBigInteger(usu.get("totalOctetsUnit"));
                if (value != null)
                    totalOctets = totalOctets.add(value);
            }
        }
        return totalOctets;
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("ddMMyyHHmmxxxx"); // handles +0430

    public static String ccnEventEndDate(String dt, BigInteger dur) {
        dt = dt.replace(" ", "");
        OffsetDateTime odt = OffsetDateTime.parse(dt, FORMATTER);
        odt = odt.plusSeconds(dur.longValue());
        return odt.format(FORMATTER);
    }

    static Map<String, Object> unifyCCRecord(Map<String, Object> node) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object creditControlRecord = node.get("creditControlRecord");
        if (creditControlRecord instanceof List) {
            return node;
        } else if (creditControlRecord instanceof Map) {
            List<Map<String, Object>> l = new ArrayList<>();
            l.add((Map<String, Object>) creditControlRecord);
            result.put("creditControlRecord", l);
        }
        return result;
    }

    public static String ccnOtherMsisdn(Map<String, Object> node) {
        //    parameterID" ."parameterValue" .partyInformation" ."msisdn" :
        // 1. Start by streaming all entries in the "creditControlRecord" list
        Map<String, Object> node1 = unifyCCRecord(node);
        Object creditControlRecord = node1.get("creditControlRecord");
        List<String> y = new ArrayList<>();
        if (creditControlRecord instanceof List) {
            y = safeGetList(node1, "creditControlRecord").stream()
                    .map(record -> {
                        return safeGetMap(record, "chargingContextSpecific");
                    })  // 2. Get the "chargingContextSpecific" map for each record
                    .flatMap(ccs -> {
                        return safeGetList(ccs, "contextParameter").stream();
                    })  // 3. FlatMap to get the stream of inner "contextParameter" lists
                    .map(param -> {
                        return safeGetMap(param, "parameterValue");
                    })   // 4. Map to the "parameterValue" map
                    .map(paramValue -> {
                        return safeGetMap(paramValue, "partyInformation");
                    })   // 5. Map to the "partyInformation" map
                    .map(partyInfo -> {
                        return safeGetString(partyInfo, "msisdn");
                    })   // 6. Map to extract the final "msisdn" string
                    .filter(Objects::nonNull)    // 7. Filter out any entries that were null or did not contain a String msisdn
                    .collect(toList());  // 8. Collect the valid msisdn strings into a List
        } else if (creditControlRecord instanceof Map) {
            Map<String, Object> ccRecord = (Map<String, Object>) creditControlRecord;
            List<Map<String, Object>> temp = safeGetList(safeGetMap(ccRecord, "chargingContextSpecific"), "contextParameter");
            y = temp.stream().map(param -> {
                return safeGetMap(param, "parameterValue");
            })   // 4. Map to the "parameterValue" map
                    .map(paramValue -> {
                        return safeGetMap(paramValue, "partyInformation");
                    })   // 5. Map to the "partyInformation" map
                    .map(partyInfo -> {
                        return safeGetString(partyInfo, "msisdn");
                    })   // 6. Map to extract the final "msisdn" string
                    .filter(Objects::nonNull)    // 7. Filter out any entries that were null or did not contain a String msisdn
                    .collect(toList());  // 8. Collect the valid msisdn strings into a List}
        }
        return y.isEmpty() ? "" : y.get(0);
    }

    public static Object ccnRateEventType(Map<String, Object> node, String serviceIdentifier) {
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> {
                    return record.get(serviceIdentifier);
                }) //   1. Map each record (Map) to its 'serviceIdentifier' value.
                .filter(Objects::nonNull)   //  2. Filter out any null values (in case the field is missing or not a String).
//                .map(s -> {
//                    return Integer.parseInt(s.toString());
//                })  // 3. Filter out any null values (in case the field is missing or not a String).
                .collect(toList());  //     3.  Collect the valid service identifier strings into a List.
        return x.isEmpty() ? "" : x.get(0);
    }

    //SERVICE_KEY, SUBSCRIBER_TYPE
    public static BigInteger ccnServiceKey(Map<String, Object> node, String cCAccountData, String serviceClassID) {

        Map<String, Object> node1 = unifyCCRecord(node);

        return safeGetList(node1, "creditControlRecord").stream()
                .map(record -> safeGetMap(record, cCAccountData))      // extract cCAccountData map
                .filter(Objects::nonNull)
                .map(ccData -> ccData.get(serviceClassID))            // extract serviceClassID
                .filter(Objects::nonNull)
                .map(obj -> new BigInteger(obj.toString()))           // convert to BigInteger
                .findFirst()                                          // take ONLY the FIRST
                .orElse(BigInteger.ZERO);                             // default value
    }

    public static Map<String, String> ccnCharge(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(node1, "creditControlRecord");
        double totalChange = 0.0, amtDiv = 0.0;

        for (Map<String, Object> record : ccrList) {
            Object accObj = record.get("cCAccountData");
            List<Map<String, Object>> accList = new ArrayList<>();

            if (accObj instanceof Map) {
                accList.add((Map<String, Object>) accObj);
            } else if (accObj instanceof List) {
                accList = (List<Map<String, Object>>) accObj;
            }
            for (Map<String, Object> acc : accList) {

                Map<String, Object> before = safeGetMap(acc, "accountValueBefore");
                Map<String, Object> after = safeGetMap(acc, "accountValueAfter");

                Object amtBefore = before.get("amount");

                Object amtAfter = after.get("amount");

                Double substract = Double.parseDouble(amtBefore.toString()) - Double.parseDouble(amtAfter.toString());
                Double div = substract / 1000000;
                totalChange += div;

                Map<String, Object> accountValueDeducted = safeGetMap(acc, "accountValueDeducted");
                Object amt = accountValueDeducted.get("amount");
                amtDiv = Double.parseDouble(amt.toString()) / 1000000;
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        result.put("CHARGE", String.valueOf(totalChange));
        result.put("VALUE_DEDUCTED", String.valueOf(amtDiv));
        return result;
    }

    private static BigInteger toBigInteger(Object amount) {
        if (amount == null) return BigInteger.ZERO;
        if (amount instanceof BigInteger) return (BigInteger) amount;

        try {
            return new BigInteger(amount.toString());
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }


    public static String selectWhere(Map<String, Object> node, String recKey, String findEle, String eleVal, String valKey) {
        String result = "";
        Object subscriptionIDList = node.get(recKey);
        if (subscriptionIDList instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) subscriptionIDList;
            result = list.stream()
//                    .filter(Objects::nonNull)
                    .filter(m -> eleVal.equals(m.get(findEle).toString()))
                    .map(m -> m.get(valKey).toString())
                    .findFirst()
                    .map(Object::toString)
                    .orElse(null);   // or default if missing
        }
        return result;
    }


    /**
     * Helper method to safely extract the inner usage list from a single entry map.
     * This handles if the entry is null, or if the "usedServiceUnit" key is missing/wrong type.
     * * @param entry A single map from the main list 'x'.
     *
     * @return A Stream of the innermost usage maps, or an empty stream if data is missing or invalid.
     */
    private static Stream<Map<String, Object>> getUsageStream(Map<String, Object> entry) {
        if (entry == null) return Stream.empty();

        Object serviceUnitObj = entry.get("usedServiceUnit");

        if (serviceUnitObj instanceof List) { // Check if the object is an instance of List and cast it safely
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> usageList = (List<Map<String, Object>>) serviceUnitObj;
            return usageList.stream().filter(Objects::nonNull); // Filter out any null maps within the usageList before streaming
        }

        return Stream.empty();
    }

    /**
     * Calculates the total sum of a specific BigInteger field using streams.
     * * @param x The main list of maps.
     *
     * @param fieldName The name of the field to sum ("timeUnit" or "tariffChangeUsage").
     * @return The total sum as a BigInteger.
     */
    public static BigInteger calculateTotalSum(List<Map<String, Object>> x, String fieldName) {
        return Optional.ofNullable(x)       // Use Optional.ofNullable to handle a null input list 'x' gracefully
                .orElseGet(ArrayList::new)  // If 'x' is null, substitute with an empty list
                .stream()
                // 1. flatMap: Flattens the nested structure. For each entry in 'x', it extracts and streams the list of innermost usage maps.
                .flatMap(TransformUtils::getUsageStream)
                // 2. map: Extracts the target field (timeUnit or tariffChangeUsage) from the map.  It uses Optional to safely handle null or incorrect types for the field value,
                // mapping it to BigInteger.ZERO if the value is missing or invalid.
                .map(usageMap -> {
                    Object value = usageMap.get(fieldName);
                    if (value instanceof BigInteger) return (BigInteger) value;
                    return BigInteger.ZERO;                                         // Treats absent/null/wrong type as 0
                })
                .reduce(BigInteger.ZERO, BigInteger::add);// 3. reduce: Sums up all the BigInteger values. The starting identity is BigInteger.ZERO.
    }

    // Safely casts an Object to a List of Maps, or returns an empty list.
    private static List<Map<String, Object>> safeGetList(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyList();

        Object listObj = map.get(key);
        if (listObj instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) listObj;
            return list.stream().filter(Objects::nonNull).collect(toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> safeGetInnerList(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyList();
        Object obj = map.get(key);
        // Case 1: Already List
        if (obj instanceof List) {
            return ((List<?>) obj).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .collect(Collectors.toList());
        }
        // Case 2: Map
        if (obj instanceof Map) {
            Map<String, Object> inner = (Map<String, Object>) obj;
            if (inner.containsKey("parameterID")) {
                return Collections.singletonList(inner);
            }
            Object nested = inner.get(key);
            if (nested instanceof List) {
                return ((List<?>) nested).stream()
                        .filter(Map.class::isInstance)
                        .map(m -> (Map<String, Object>) m)
                        .collect(Collectors.toList());
            }
            if (nested instanceof Map) {
                return Collections.singletonList((Map<String, Object>) nested);
            }
        }
        return Collections.emptyList();
    }

    // Safely casts an Object to a Map, or returns an empty map.
    private static Map<String, Object> safeGetMap(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyMap();
        Object mapObj = map.get(key);
        if (mapObj instanceof Map) {
            Map<String, Object> nestedMap = (Map<String, Object>) mapObj;
            return nestedMap;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeGetInnerMap(Object obj) {
        if (obj == null) return Collections.emptyMap();
        if (obj instanceof Map)
            return (Map<String, Object>) obj;
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (!list.isEmpty() && list.get(list.size() - 1) instanceof Map)
                return (Map<String, Object>) list.get(list.size() - 1);
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeGetMapOccGprs(Object map, String key) {
        if (map == null)
            return Collections.emptyMap();
        if (map instanceof Map) {
            return (Map<String, Object>) map;
        }
        if (map instanceof List) {
            List<?> list = (List<?>) map;
            if (!list.isEmpty()) {
                Object last = list.get(list.size() - 1);
                if (last instanceof Map) {
                    return (Map<String, Object>) last;
                }
            }
        }
        return Collections.emptyMap();
    }

    public static long safeGetLong(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(obj));
        } catch (Exception e) {
            return 0L;
        }
    }

    public static int safeGetInt(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(obj));
        } catch (Exception e) {
            return 0;
        }
    }

    // Safely extracts a String value from a Map, returning null if not found or not a String.
    private static String safeGetString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return (value instanceof String) ? (String) value : null;
    }

    public static Integer safeGetInteger(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // Safely extracts a String value from a Map, returning null if not found or not a String.
    private static Object getAccountValueBefore(Map<String, Object> map) {
        if (map == null) return null;
        Object x = map.get("accountValueBefore");
        return ((Map) x).get("amount");
    }

    private static Object getAccountValueAfter(Map<String, Object> map) {
        if (map == null) return null;
        Object x = map.get("accountValueAfter");
        return ((Map) x).get("amount");
    }

    //---------------------------------

    public static Map<String, Object> mtn_gmsc_supplServicesUsed(Map<String, Object> node) {

        List<Map<String, Object>> data = (List<Map<String, Object>>) node.get("suppServiceUsed");

        Map<String, Object> v = new LinkedHashMap<>();
        String f1 = "ssCode";
        String csv = data.stream()
                .map(row -> row.getOrDefault(f1, ""))
                .map(Object::toString)
                .collect(joining("|"));
        v.put(f1, csv);

        String f2 = "ssTime";
        csv = data.stream()
                .map(row -> row.getOrDefault(f2, ""))
                .map(Object::toString)
                .collect(joining("|"));
        v.put(f2, csv);
        return v;
    }

    public static String fetchValue(String cacheName, String key) {
        JsonNode t = cache.get(cacheName);
        JsonNode t1 = null;
        if (t != null) t1 = t.get(key);
        String v = null;
        if (t1 != null) v = t1.asText();
        return v;
    }

    public static String fetchValue(String cacheName, BigInteger key) {
        JsonNode t = cache.get(cacheName);
        JsonNode t1 = null;
        if (t != null) t1 = t.get(key.toString());
        String v = null;
        if (t1 != null) v = t1.asText();
        return v;
    }

    public static String fetchValue(String cacheName, String key, String defaultVal) {
        String v = fetchValue(cacheName, key);
        return (v == null) ? defaultVal : v;
    }

    public static Object normalization(Object number) {
        Object normNum = "";
        if (number != null && !number.toString().isEmpty() && !"null".equalsIgnoreCase(number.toString())) {
            if (number.toString().startsWith("0")) {
                normNum = ltrim(number.toString(), '0');
            } else if (number.toString().startsWith("19")) {
                normNum = number.toString().substring(2);
            }

            if (number.toString().length() <= 9 && !number.toString().startsWith("93")) {
                normNum = "93" + number;
            }
        }
        return normNum;
    }

    public static String convertedDate(String timeStamp, String srcFormat, String tgtFormat) throws ParseException {
        if (timeStamp != null) {
            Date dob = sdfS.get().parse(timeStamp);
            String genFullDate = sdf.get().format(dob);
            return sdfT.get().format(dob);
        }
        return "";
    }

    public static String convertedDate(String timeStamp) throws ParseException {
        if (timeStamp != null) {
            Date dob = sdfS.get().parse(timeStamp);
            String genFullDate = sdf.get().format(dob);
            return sdfT.get().format(dob);
        }
        return "";
    }

//    private static String genFullDate;

//    public static String convertedClientDate(Object timeStamp, String pattern) throws ParseException {
//        if (timeStamp != null) {
//            Date date = DateUtility.convertString2JavaUtilDate(timeStamp.toString(), pattern);
//            ZoneId zoneId = ZoneId.of(ZoneOffset.ofTotalSeconds(270 * 60).getId());
//
//            ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(date.toInstant(), zoneId);
//            String clientLocalTime = zonedDateTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
//            String clientLocalDate = zonedDateTime.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
//            return clientLocalTime;
//        }
//        return "";
//    }

    public static String formatTelURI(String uri) {
        if (uri == null) return "";

        uri = uri.trim();
        if (uri.isEmpty()) return uri;

        String normalized = uri.toLowerCase();// Normalize prefix (case-insensitive)
        // Remove "tel:" prefix if present
        if (normalized.startsWith("tel:"))
            uri = uri.substring(4).trim(); // remove exactly the visible "tel:"

        return uri;
    }

    public static Map<String, Object> billablePulse(String dur, String divisor) {
        BigInteger d = new BigInteger(dur);
        return billablePulse(d, divisor);
    }

    public static Map<String, Object> billablePulse(BigInteger dur, String divisor) {
        Map<String, Object> mv = new HashMap<>();
        Long BILLABLE_PULSE = dur.longValue() / Integer.parseInt(divisor);
        int ZERO_DUR_IND = 0;
        if (dur.toString().equals("0"))
            ZERO_DUR_IND = 1;
        mv.put("BILLABLE_PULSE", BILLABLE_PULSE);
        mv.put("ZERO_DURATION_IND", ZERO_DUR_IND);
        return mv;
    }

    public static String formatSipURI(String uri) {
        if (uri == null) return "";

        uri = uri.trim();
        if (uri.isEmpty()) return uri;

        String normalized = uri.toLowerCase();// Normalize prefix (case-insensitive)
        // Remove "tel:" prefix if present
        if (normalized.startsWith("sip:"))
            uri = uri.substring(4).trim(); // remove exactly the visible "tel:"

        return uri;
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

    public static String readStringFromFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); // or any other appropriate charset
    }

    public static Method findMatchingMethod(Class<?> clazz, String name, Class<?>[] inputTypes) {
        Method[] methods = clazz.getMethods();

        for (Method m : methods) {
            if (!m.getName().equals(name)) continue;

            Class<?>[] paramTypes = m.getParameterTypes();
            if (paramTypes.length != inputTypes.length) continue;

            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!isCompatible(inputTypes[i], paramTypes[i])) {
                    match = false;
                    break;
                }
            }
            if (match)
                return m;
        }
        return null;
    }

    private static boolean isCompatible(Class<?> actual, Class<?> expected) {

        // exact match
        if (expected.isAssignableFrom(actual))
            return true;

        // primitive vs wrapper
        if (expected == int.class && actual == Integer.class) return true;
        if (expected == long.class && actual == Long.class) return true;
        if (expected == boolean.class && actual == Boolean.class) return true;
        return expected == double.class && actual == Double.class;
    }

    public static Map<Object, List<Map<String, Object>>> groupedBy(List<Map<String, Object>> list, List<String> keys) {
        return list.stream().collect(groupingBy(m -> keys.stream().map(x -> m.get(x).toString())
                .collect(joining("-")), mapping(Function.identity(),
                toList())));
    }

    public static Object formatObject(Object value, String pattern) {
        if (pattern != null) {
            DateFormat f = new SimpleDateFormat(pattern); // ("yyMMddHHmmss Z");
            try {
                return f.parse(value.toString());
            } catch (ParseException e) {
                return value;
            }
        }
        return value;
    }

    public static Object formatDate(Object value, String pattern) throws ParseException {
        if (pattern != null) {
            DateFormat f = new SimpleDateFormat(pattern); // ("yyMMddHHmmss Z");
            Date parsedDate = f.parse(value.toString());
            String genFullDate = sdf.get().format(parsedDate);
            return sdfT.get().format(parsedDate);
        }
        return "";
    }

    public static String psv(Object str1, Object str2) {
        return str1.toString() + '|' + str2.toString();
    }

    public static long count(Object d1) {
        try {
            return ((Number) d1).longValue() + 1;
        } catch (Exception ignore) {
//            ignore.printStackTrace();
        }
        return 0;
    }

    public static Object sum(Object d1, Object d2) {
        return add(d1, d2);
    }

    public static Object add(Object d1, Object d2) {
        if (d1 == null) d1 = 0;
        if (d2 == null) d2 = 0;
        try {
            if (d1 instanceof Double) return ((Number) d1).doubleValue() + ((Number) d2).doubleValue();
            else if (d1 instanceof Float) return ((Number) d1).floatValue() + ((Number) d2).floatValue();
            else if (d1 instanceof Long) return (double) ((Number) d1).longValue() + ((Number) d2).longValue();
            else if (d1 instanceof Integer) return ((Number) d1).intValue() + ((Number) d2).intValue();
        } catch (Exception ignore) {
//            ignore.printStackTrace();
        }
        return d1;
    }

    public static Object mul(Object d1, Object d2) {
        if (d1 == null) d1 = 1;
        if (d2 == null) d2 = 1;
        try {
            if (d1 instanceof Double) return ((Number) d1).doubleValue() * ((Number) d2).doubleValue();
            else if (d1 instanceof Float) return ((Number) d1).floatValue() * ((Number) d2).floatValue();
            else if (d1 instanceof Long) return (double) ((Number) d1).longValue() * ((Number) d2).longValue();
            else if (d1 instanceof Integer) return ((Number) d1).intValue() * ((Number) d2).intValue();
        } catch (Exception ignore) {
        }
        return d1;
    }

    public static Object sub(Object d1, Object d2) {
        if (d1 == null) d1 = 0;
        if (d2 == null) d2 = 0;
        try {
            if (d1 instanceof Double) return ((Number) d1).doubleValue() - ((Number) d2).doubleValue();
            else if (d1 instanceof Float) return ((Number) d1).floatValue() - ((Number) d2).floatValue();
            else if (d1 instanceof Long) return (double) ((Number) d1).longValue() - ((Number) d2).longValue();
            else if (d1 instanceof Integer) return ((Number) d1).intValue() - ((Number) d2).intValue();
        } catch (Exception ignore) {
        }
        return d1;
    }

    public static Object div(Object d1, Object d2) {
        if (d1 == null) d1 = 1;
        if (d2 == null) d2 = 1;
        if (((Number) d2).intValue() != 0) {
            try {
                if (d1 instanceof Double) return ((Number) d1).doubleValue() / ((Number) d2).doubleValue();
                else if (d1 instanceof Float) return ((Number) d1).floatValue() / ((Number) d2).floatValue();
                else if (d1 instanceof Long) return (double) ((Number) d1).longValue() / ((Number) d2).longValue();
                else if (d1 instanceof Integer) return ((Number) d1).intValue() / ((Number) d2).intValue();
            } catch (Exception ignore) {
            }
        }
        return d1;
    }

    public static Object max(Object d1, Object d2) {
        try {
            if (d1 instanceof Double) return Math.max(((Number) d1).doubleValue(), ((Number) d2).doubleValue());
            else if (d1 instanceof Float) return Math.max(((Number) d1).floatValue(), ((Number) d2).floatValue());
            else if (d1 instanceof Long) return Math.max(((Number) d1).longValue(), ((Number) d2).longValue());
            else if (d1 instanceof Integer) return Math.max(((Number) d1).intValue(), ((Number) d2).intValue());
            else if (d1 instanceof Date) return ((Date) d1).compareTo((Date) d2) > 0 ? d1 : d2;
            else if (d1 instanceof String) return d1.toString().compareTo(d2.toString()) > 0 ? d1 : d2;
        } catch (Exception ignore) {
        }
        return d1;
    }

    public static Object min(Object d1, Object d2) {
        try {
            if (d1 instanceof Double) return Math.min(((Number) d1).doubleValue(), ((Number) d2).doubleValue());
            else if (d1 instanceof Float) return Math.min(((Number) d1).floatValue(), ((Number) d2).floatValue());
            else if (d1 instanceof Long) return Math.min(((Number) d1).longValue(), ((Number) d2).longValue());
            else if (d1 instanceof Integer) return Math.min(((Number) d1).intValue(), ((Number) d2).intValue());
            else if (d1 instanceof Date) return ((Date) d1).compareTo((Date) d2) > 0 ? d1 : d2;
            else if (d1 instanceof String)
                return d1.toString().compareTo(d2.toString()) < 0 ? d1 : d2;
        } catch (Exception ignore) {
        }
        return d1;
    }

    public static Number sumLong(Number... numbers) {
        long total = 0L;
        for (Number number : numbers) total += number.longValue();
        return total;
    }

    public static Number sumDouble(Number... numbers) {
        double total = 0D;
        for (Number number : numbers) total += number.doubleValue();
        return total;
    }

    public static String getAsDSV(List<Object> list, String del) {
        return list.stream().map(Object::toString).collect(joining(del));
    }


    static long findDifference(long epochSeconds, String end_date) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Instant instant = Instant.ofEpochSecond(epochSeconds);
        LocalDateTime d1 = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"));
        LocalDateTime d2 = LocalDateTime.parse(end_date, formatter);
        Duration duration = Duration.between(d1, d2);
        return duration.toHours();
    }


    public static String dsv(List<Map<String, Object>> group, String key) {
        String c = group.stream()
                .filter(e -> e.containsKey(key))
                .map(e -> e.get(key).toString())
                .reduce("", (x, y) -> x + y + "|");
        if (c.length() > 1) {
            c = c.substring(0, c.length() - 1);
        }
        return c;
    }

    public static Long sum(List<Map<String, Object>> group, String key) {
        return group.stream()
                .filter(e -> e.containsKey(key))
                .map(e -> Long.parseLong(e.get(key).toString()))
                .reduce(0L, Long::sum);
    }

    public static Object first(List<Map<String, Object>> group, String key) {
        List<Object> l = group.stream()
                .filter(p -> p.containsKey(key))
                .map(e -> e.get(key))
                .sorted().collect(toList());
        if (!l.isEmpty()) {
            return l.get(0);
        }
        return "";
    }

    public static Object last(List<Map<String, Object>> group, String key) throws Exception {
        List<Object> l = group.stream()
                .filter(p -> p.containsKey(key))
                .map(e -> e.get(key))
                .sorted().collect(toList());
        if (!l.isEmpty()) {
            return l.get(l.size() - 1);
        }
        return "";
    }

    public static String distinctValueAsDSV(List<Map<String, Object>> coscs, String key) {
        List<Map<String, Object>> list = coscs.stream()
                .filter(p -> p.containsKey(key))
                .filter(distinct(p -> p.get(key)))
                .collect(toList());
        return dsv(list, key);
    }

    public static <T> Predicate<T> distinct(Function<? super T, Object> key) {
        Map<Object, Boolean> map = new ConcurrentHashMap<>();
        return t -> map.putIfAbsent(key.apply(t), Boolean.TRUE) == null;
    }

    public static String ltrim(String s, char c) {
        int len = s.length();
        int st = 0;
        char[] val = s.toCharArray();
        while ((st < len) && (val[st] == c)) {
            st++;
        }
//      while ((st < len) && (val[len - 1] <= 'c')) len--;
        return st > 0 ? s.substring(st, len) : s;
    }

    public static Object compute(String op, Object d1, Object d2) {
        try {
            if (d1 instanceof Double) {
                switch (op.toLowerCase()) {
                    case "count":
                        return (Double) d1 + 1;
                    case "sum":
                        return (Double) d1 + (Double) d2;
                    case "diff":
                        return Math.abs((Double) d1 - (Double) d2);
                    default:
                }
            } else if (d1 instanceof Float) {
                switch (op.toLowerCase()) {
                    case "count":
                        return (Float) d1 + 1;
                    case "sum":
                        return (Float) d1 + (Float) d2;
                    case "diff":
                        return Math.abs((Float) d1 - (Float) d2);
                    default:
                }
            } else if (d1 instanceof Long) {
                switch (op.toLowerCase()) {
                    case "count":
                        return (Long) d1 + 1;
                    case "sum":
                        return (Long) d1 + (Long) d2;
                    case "diff":
                        return Math.abs((Long) d1 - (Long) d2);
                    default:
                }
            } else if (d1 instanceof Integer) {
                switch (op.toLowerCase()) {
                    case "count":
                        return (Integer) d1 + 1;
                    case "sum":
                        return (Integer) d1 + (Integer) d2;
                    case "diff":
                        return Math.abs((Integer) d1 - (Integer) d2);
                    default:
                }
            } else if (d1 instanceof String) {
                try {
//                    double l1 = getNumber(d1);
                    double l1 = Double.parseDouble(d1.toString());
                    double l2 = Double.parseDouble(d2.toString());
                } catch (Exception ignore) {
                }
            } else return 0;
        } catch (Exception ignore) {
//            ignor.printStackTrace();
//            throw new RuntimeException(ignor);
        }
        return d1;
    }

    public static void main(String[] args) {
        // Obtain the method name from configuration
        String methodName = "concatStrings";

        try {
            // Get the Class object for the utility class
            Class<?> clazz = Transformer.class; // Replace StringUtils with the name of your utility class

            // Get the Method object for the method with the specified name and parameter types
            Method method = clazz.getMethod(methodName, Object.class, Object.class);

            // Call the method with parameters
            String result = (String) method.invoke(null, "Hello, ", "world!");

//            System.out.println(result); // Output: Hello, world!
            sumLong(1, 3.4);
        } catch (Exception ignore) {
//            e.printStackTrace();
            // Handle any exceptions that might occur during method invocation
        }
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static byte[] octetStringToBytes(String octetString) {
        int len = octetString.length();
        byte[] result = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            result[i / 2] = (byte) ((Character.digit(octetString.charAt(i), 16) << 4)
                    + Character.digit(octetString.charAt(i + 1), 16));
        }
        return result;
    }

    public static String ipAddress(byte[] data) {
        if (data == null) return "";
        try {
            return InetAddress.getByAddress(data).getHostAddress();
        } catch (UnknownHostException e) {
            return "Invalid IP"; // Return a more descriptive string for invalid data
        }
    }

}
