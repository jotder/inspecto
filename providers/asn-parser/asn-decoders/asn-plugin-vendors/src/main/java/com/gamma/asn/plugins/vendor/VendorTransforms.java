package com.gamma.asn.plugins.vendor;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * VERBATIM port of the vendor functions from legacy
 * {@code com.gamma.skybase.transformer2.TransformUtils} — quirks, dead locals and
 * unchecked casts included; the golden corpus rows are the contract. The only change:
 * {@code fetchValue}'s lookup tables come from the constructor (per-pipeline) instead of
 * the legacy static Jackson cache.
 */
@SuppressWarnings("unchecked")
final class VendorTransforms {

    /** {@code @simpleLookup}: table name → (key → scalar); values stringified like Jackson asText. */
    private final Map<String, Object> lookups;

    VendorTransforms(Map<String, Object> lookups) {
        this.lookups = lookups == null ? Map.of() : lookups;
    }

    // ---- Ericsson CCN (mtna_ccn, aftel voice/SMS) --------------------------------------

    static Object ccnEventType(Map<String, Object> node, String serviceIdentifier) {
        String eventType = "";
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> record.get(serviceIdentifier))
                .filter(Objects::nonNull)
                .collect(toList());
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

    static Object ccnEventTypeKey(Map<String, Object> node, String serviceIdentifier) {
        String eventTypeKey = "";
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> record.get(serviceIdentifier))
                .filter(Objects::nonNull)
                .collect(toList());
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

    static String ccnSubscriberType(Map<String, Object> node, String cCAccountData, String serviceClassID) {
        BigInteger x = ccnServiceKey(node, cCAccountData, serviceClassID);
        String st = "";
        if (x.intValue() < 300) st = "PREPAID";
        if (x.intValue() == 307) st = "POSTPAID";
        if (x.intValue() == 308) st = "CORP";
        return st;
    }

    static String ccnFnfNumber(Map<String, Object> node, String cCAccountData, String familyAndFriendsNo) {
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

    static String ccnCellId(Map<String, Object> node) {
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

    static String ccnSmscCenter(Map<String, Object> node) {
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

    static String ccnServedMsrn(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        Object creditControlRecord = node1.get("creditControlRecord");
        List<String> y = new ArrayList<>();
        if (creditControlRecord instanceof List) {
            y = safeGetList(node1, "creditControlRecord").stream()
                    .map(record -> safeGetMap(record, "chargingContextSpecific"))
                    .flatMap(ccs -> safeGetList(ccs, "contextParameter").stream())
                    .map(param -> safeGetMap(param, "parameterValue"))
                    .map(paramValue -> safeGetString(paramValue, "string"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return y.isEmpty() ? "" : y.get(0);
    }

    static String ccnChargedAccName(Map<String, Object> node) {
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

    static Map<String, Object> ccnDedicatedCharge(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(node1, "creditControlRecord");

        Map<Integer, Long> amountSum = new LinkedHashMap<>();
        Map<Integer, Integer> acctIdOrder = new LinkedHashMap<>();

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

    static String ccnSrvTypeKey(Map<String, Object> node, String cCAccountData, String serviceClassID) {
        BigInteger x = ccnServiceKey(node, cCAccountData, serviceClassID);
        String st = "";
        if (x.intValue() >= 300) st = "1";
        else st = "2";
        return st;
    }

    static int ccnBillablePulse(BigInteger originalDur, String pulse) {
        BigInteger[] x = originalDur.divideAndRemainder(new BigInteger(pulse));
        return (x[1].intValue() == 0) ? x[0].intValue() : x[0].intValue() + 1;
    }

    static BigInteger ccnOriginalDur(Map<String, Object> node) {
        BigInteger totalTimeUnit = BigInteger.ZERO;

        Map<String, Object> node1 = unifyCCRecord(node);
        Object p = node1.get("creditControlRecord");
        if (p instanceof Map) {
            // legacy: falls through with ZERO
        } else if (p instanceof List) {
            List<Map<String, Object>> a = (List<Map<String, Object>>) node1.get("creditControlRecord");
            List x = a.stream()
                    .map(m -> m.get("usedServiceUnits")).collect(toList());
            totalTimeUnit = calculateTotalSum(x, "timeUnit");
            BigInteger totalTariffChangeUsage = calculateTotalSum(x, "tariffChangeUsage");
        }
        return totalTimeUnit;
    }

    static int ccnZeroDurationInd(BigInteger duration) {
        return duration.intValue() == 0 ? 1 : 0;
    }

    static Object ccnEventDir(Map<String, Object> node, String serviceScenario) {
        String eventDir = "";
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> record.get(serviceScenario))
                .filter(Objects::nonNull)
                .collect(toList());
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

    static String ccnRoamingPosition(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        Object recordValue = node1 != null ? node1.get("creditControlRecord") : null;

        List<Map<String, Object>> creditControlRecord = (recordValue instanceof List)
                ? (List<Map<String, Object>>) recordValue : Collections.emptyList();

        List<Object> x = creditControlRecord.stream()
                .filter(Objects::nonNull)
                .map(record -> record.get("roamingPosition"))
                .map(Object::toString)
                .collect(toList());

        return x.isEmpty() ? "" : x.get(0).toString();
    }

    static Map<String, String> ccnGsnAddres(Map<String, Object> node) {
        Map<String, Object> unified = unifyCCRecord(node);
        List<Map<String, Object>> ccrList = safeGetList(unified, "creditControlRecord");

        Map<String, String> result = new HashMap<>();
        if (ccrList == null) return result;

        Object sgsnAddr = null, ggsnAddr = null;
        String sgsnMccMnc = null, ggsnMccMnc = null, chargingChar = null, apn = null,
                chargingId = null, ratType = null, qosClassId = null, rateEventType = null;

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

    static BigInteger ccnGprsTotalVolume(Map<String, Object> node) {
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

    static String ccnOtherMsisdn(Map<String, Object> node) {
        Map<String, Object> node1 = unifyCCRecord(node);
        Object creditControlRecord = node1.get("creditControlRecord");
        List<String> y = new ArrayList<>();
        if (creditControlRecord instanceof List) {
            y = safeGetList(node1, "creditControlRecord").stream()
                    .map(record -> safeGetMap(record, "chargingContextSpecific"))
                    .flatMap(ccs -> safeGetList(ccs, "contextParameter").stream())
                    .map(param -> safeGetMap(param, "parameterValue"))
                    .map(paramValue -> safeGetMap(paramValue, "partyInformation"))
                    .map(partyInfo -> safeGetString(partyInfo, "msisdn"))
                    .filter(Objects::nonNull)
                    .collect(toList());
        } else if (creditControlRecord instanceof Map) {
            Map<String, Object> ccRecord = (Map<String, Object>) creditControlRecord;
            List<Map<String, Object>> temp =
                    safeGetList(safeGetMap(ccRecord, "chargingContextSpecific"), "contextParameter");
            y = temp.stream()
                    .map(param -> safeGetMap(param, "parameterValue"))
                    .map(paramValue -> safeGetMap(paramValue, "partyInformation"))
                    .map(partyInfo -> safeGetString(partyInfo, "msisdn"))
                    .filter(Objects::nonNull)
                    .collect(toList());
        }
        return y.isEmpty() ? "" : y.get(0);
    }

    static Object ccnRateEventType(Map<String, Object> node, String serviceIdentifier) {
        Map<String, Object> node1 = unifyCCRecord(node);
        List<Object> x = safeGetList(node1, "creditControlRecord").stream()
                .map(record -> record.get(serviceIdentifier))
                .filter(Objects::nonNull)
                .collect(toList());
        return x.isEmpty() ? "" : x.get(0);
    }

    static BigInteger ccnServiceKey(Map<String, Object> node, String cCAccountData, String serviceClassID) {
        Map<String, Object> node1 = unifyCCRecord(node);

        return safeGetList(node1, "creditControlRecord").stream()
                .map(record -> safeGetMap(record, cCAccountData))
                .filter(Objects::nonNull)
                .map(ccData -> ccData.get(serviceClassID))
                .filter(Objects::nonNull)
                .map(obj -> new BigInteger(obj.toString()))
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    static Map<String, String> ccnCharge(Map<String, Object> node) {
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

    // ---- Ericsson OCC (mtna_occ GPRS/voice) --------------------------------------------

    static Map<String, String> occServedSubscriptionIds(Map<String, Object> node) {
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

    static Map<String, Object> occGprsDedicatedCharge(Map<String, Object> node) {
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

    static BigInteger occGprsOriginalDur(Map<String, Object> node) {
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

    static Map<String, String> occGprsGsnAddres(Map<String, Object> node) {
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

    // ---- Huawei MSC (mtna_huwmsc), operator numbering, lookups -------------------------

    static Map<String, Object> mtn_gmsc_supplServicesUsed(Map<String, Object> node) {
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

    static Object normalization(Object number) {
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

    static String selectWhere(Map<String, Object> node, String recKey, String findEle, String eleVal, String valKey) {
        String result = "";
        Object subscriptionIDList = node.get(recKey);
        if (subscriptionIDList instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) subscriptionIDList;
            result = list.stream()
                    .filter(m -> eleVal.equals(m.get(findEle).toString()))
                    .map(m -> m.get(valKey).toString())
                    .findFirst()
                    .map(Object::toString)
                    .orElse(null);
        }
        return result;
    }

    static Map<String, Object> billablePulse(String dur, String divisor) {
        BigInteger d = new BigInteger(dur);
        return billablePulse(d, divisor);
    }

    static Map<String, Object> billablePulse(BigInteger dur, String divisor) {
        Map<String, Object> mv = new HashMap<>();
        Long BILLABLE_PULSE = dur.longValue() / Integer.parseInt(divisor);
        int ZERO_DUR_IND = 0;
        if (dur.toString().equals("0"))
            ZERO_DUR_IND = 1;
        mv.put("BILLABLE_PULSE", BILLABLE_PULSE);
        mv.put("ZERO_DURATION_IND", ZERO_DUR_IND);
        return mv;
    }

    /** Legacy fetchValue(cacheName, key): the static Jackson cache became per-pipeline lookups. */
    String fetchValue(String cacheName, String key) {
        Object t = lookups.get(cacheName);
        Object t1 = null;
        if (t instanceof Map) t1 = ((Map<String, Object>) t).get(key);
        return t1 == null ? null : String.valueOf(t1);
    }

    String fetchValue(String cacheName, BigInteger key) {
        return fetchValue(cacheName, key.toString());
    }

    String fetchValue(String cacheName, String key, String defaultVal) {
        String v = fetchValue(cacheName, key);
        return (v == null) ? defaultVal : v;
    }

    // ---- helpers (verbatim legacy) ------------------------------------------------------

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

    private static Stream<Map<String, Object>> getUsageStream(Map<String, Object> entry) {
        if (entry == null) return Stream.empty();

        Object serviceUnitObj = entry.get("usedServiceUnit");

        if (serviceUnitObj instanceof List) {
            List<Map<String, Object>> usageList = (List<Map<String, Object>>) serviceUnitObj;
            return usageList.stream().filter(Objects::nonNull);
        }
        return Stream.empty();
    }

    static BigInteger calculateTotalSum(List<Map<String, Object>> x, String fieldName) {
        return Optional.ofNullable(x)
                .orElseGet(ArrayList::new)
                .stream()
                .flatMap(VendorTransforms::getUsageStream)
                .map(usageMap -> {
                    Object value = usageMap.get(fieldName);
                    if (value instanceof BigInteger) return (BigInteger) value;
                    return BigInteger.ZERO;
                })
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static List<Map<String, Object>> safeGetList(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyList();

        Object listObj = map.get(key);
        if (listObj instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) listObj;
            return list.stream().filter(Objects::nonNull).collect(toList());
        }
        return Collections.emptyList();
    }

    private static List<Map<String, Object>> safeGetInnerList(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyList();
        Object obj = map.get(key);
        if (obj instanceof List) {
            return ((List<?>) obj).stream()
                    .filter(Map.class::isInstance)
                    .map(m -> (Map<String, Object>) m)
                    .collect(Collectors.toList());
        }
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

    private static Map<String, Object> safeGetMap(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyMap();
        Object mapObj = map.get(key);
        if (mapObj instanceof Map) {
            return (Map<String, Object>) mapObj;
        }
        return Collections.emptyMap();
    }

    private static String safeGetString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return (value instanceof String) ? (String) value : null;
    }

    private static Integer safeGetInteger(Map<String, Object> map, String key) {
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

    private static BigInteger toBigInteger(Object amount) {
        if (amount == null) return BigInteger.ZERO;
        if (amount instanceof BigInteger) return (BigInteger) amount;

        try {
            return new BigInteger(amount.toString());
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    static String ltrim(String s, char c) {
        int len = s.length();
        int st = 0;
        char[] val = s.toCharArray();
        while ((st < len) && (val[st] == c)) {
            st++;
        }
        return st > 0 ? s.substring(st, len) : s;
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    static String ipAddress(byte[] data) {
        if (data == null) return "";
        try {
            return InetAddress.getByAddress(data).getHostAddress();
        } catch (UnknownHostException e) {
            return "Invalid IP";
        }
    }
}
