package com.gamma.skybase.decoder.asn2;//package com.gamma.skybase.decoder.asn;
//
//import com.gamma.components.commons.app.AppConfig;
//import com.gamma.telco.utility.reference.ReferenceDimDialDigit;
//
//import java.text.ParseException;
//import java.text.SimpleDateFormat;
//import java.util.Arrays;
//import java.util.Date;
//import java.util.LinkedHashMap;
//import java.util.Map;
//
//import static com.gamma.telco.utility.TelcoEnrichmentUtility.ltrim;
//
//public class HuwAsnMscEnrichmentUtil {
//
////    private static final ThreadLocal<SimpleDateFormat> seizure = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyMMddHHmmss"));
////    DateTimeFormatter inputFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
//
//    private final AppConfig appConfig = AppConfig.instance();
//    private final String localDateOffset = appConfig.getProperty("app.datasource.timeoffset");
//    private static final String COUNTRY_CODE = AppConfig.instance().getProperty("app.datasource.countrycode");
////    protected static final OpcoBusinessTransformation txLib = new OpcoBusinessTransformation();
////    protected static final OpcoBusinessTransformation txLib = new OpcoBusinessTransformation();
//
//    LinkedHashMap<String, Object> record;
//
//    private HuwAsnMscEnrichmentUtil(LinkedHashMap<String, Object> record) {
//        this.record = record;
//    }
//
//    public static HuwAsnMscEnrichmentUtil of(LinkedHashMap<String, Object> record) {
//        return new HuwAsnMscEnrichmentUtil(record);
//    }
//
//    public String getValue(String field) {
//        Object s = record.get(field);
//        if (s != null) {
//            String s1 = s.toString().trim();
//            if (!s1.isEmpty()) return s1;
//        }
//        return null;
//    }
//
//    public void eventType() { // default -99
//        String eventTypeKey = null;
//        String recordType = getValue("recordType");
//        if (Arrays.asList(new String[]{"6", "7"}).contains(recordType))
//            eventTypeKey = "2";
//        else if (Arrays.asList(new String[]{"0", "1", "3", "4", "5"}).contains(recordType))
//            eventTypeKey = "1";
//        else if ("100".equals(recordType))
//            eventTypeKey = "5";
//        else if ("2".equals(recordType))
//            eventTypeKey = "6";
//
//        if (eventTypeKey != null)
//            record.put("event_type_key", eventTypeKey);
//    }
//
//    public void eventDirection() {
//        String eventDirectionKey = null;
//        String recordType = getValue("recordType");
//        if (recordType != null)
//            switch (recordType) {
//                case "0":
//                case "6":
//                case "4":
//                case "100":
//                    eventDirectionKey = "1";
//                    break;
//                case "1":
//                case "7":
//                case "2":
//                case "3":
//                    eventDirectionKey = "2";
//                    break;
//                case "5":
//                    eventDirectionKey = "3";
//                    break;
//                default:
//                    eventDirectionKey = "-99";
//                    break;
//            }
//        if (eventDirectionKey != null)
//            record.put("event_direction_key", eventDirectionKey);
//
//
//        if (getValue("otherMSISDN") != null) {
//            switch (recordType) {
//                case "MOCallRecord":
//                case "ForwardCallRecord":
//                case "SOSMSRecord":
//                case "MOSMSRecord":
//                case "OutGatewayRecord":
//                    eventDirectionKey = "1";
//                    break;
//                case "MTCallRecord":
//                case "RoamingRecord":
//                case "STSMSRecord":
//                case "MTSMSRecord":
//                case "IncGatewayRecord":
//                case "TermCAMELRecord":
//                    eventDirectionKey = "2";
//                    break;
//                case "TransitCallRecord":
//                    eventDirectionKey = "3";
//                    break;
//                default:
//                    eventDirectionKey = "-99";
////                    logger.debug("No event direction key defined for event type {}", callType);
//                    break;
//            }
//        } else {
//            eventDirectionKey = "-99";
//        }
//
////            EVENT_DIRECTION_KEY
//        record.put("EVENT_DIRECTION_KEY", eventDirectionKey);
//
//    }
//
//    public void servedIMSI() { // default -99
//        String imsi = "-99";
//        String servedImsi = getValue("servedIMSI");
//        if (servedImsi != null)
//            imsi = servedImsi;
//        record.put("served_imsi", imsi);
//    }
//
//    public void servedIMEI() { // default -99
//        String servedImei = getValue("servedIMEI");
//        if (servedImei != null)
//            record.put("served_imei", servedImei);
//    }
//
//    public void servedMSISDN() {
//        String serveMsisdn = null;
//        String servedMsisdn = getValue("servedMSISDN");
//        String recordType = getValue("recordType");
//        if (recordType != null) {
//            switch (recordType) {
//                case "3":
//                    String calledNumber = getValue("calledNumber");
//                    if (calledNumber != null) {
//                        if (calledNumber.startsWith("A132")) {
//                            serveMsisdn = calledNumber.substring(4);
//                            serveMsisdn = normalizeMSISDN(serveMsisdn);
//                        } else if (calledNumber.startsWith("A1")) {
//                            serveMsisdn = calledNumber.substring(2);
//                            servedMsisdn = normalizeMSISDN(serveMsisdn);
//                        }
//                    }
//                    break;
//                case "4":
//                    String callingNumber = getValue("callingNumber");
//                    if (callingNumber != null) {
//                        if (callingNumber.startsWith("A1")) {
//                            serveMsisdn = callingNumber.substring(2);
//                            serveMsisdn = normalizeMSISDN(serveMsisdn);
//                        }
//                    }
//                    break;
//                case "100":
//                    if (servedMsisdn != null)
//                        serveMsisdn = normalizeMSISDN(servedMsisdn);
//                    break;
//                default:
//                    if (servedMsisdn != null)
//                        if (servedMsisdn.startsWith("91"))
//                            serveMsisdn = servedMsisdn.substring(2);
//                    break;
//            }
//        }
//        record.put("served_msisdn", serveMsisdn);
//    }
//
//    public void servedType() {
//        String servedType = "-99";
//        String recordType = getValue("recordType");
//        String servedIMSI = getValue("servedIMSI");
//        if (servedIMSI != null) {
////            ReferenceDimKaabuClient clientInfo = SbinUtil.getKaabuClientServedType(servedIMSI);
////            if (clientInfo != null) {
////                String srvType = clientInfo.getServedType();
////                if (srvType != null) {
////                    if (recordType != null && recordType.equals("2") && servedIMSI.startsWith("61607")) {
////                        if (srvType.equalsIgnoreCase("PREPAID"))
////                            servedType = "6";
////                        else if (srvType.equalsIgnoreCase("POSTPAID"))
////                            servedType = "5";
////                    } else {
////                        if (srvType.equalsIgnoreCase("PREPAID"))
////                            servedType = "2";
////                        else if (srvType.equalsIgnoreCase("POSTPAID"))
////                            servedType = "1";
////                    }
////                }
////            }
//        } else
//            servedType = "3";
//        record.put("served_type", servedType);
//    }
//
//    public void otherPartyMSISDN() { // default -99
//        String otherMsisdn = null;
//        String calledNumber = getValue("calledNumber");
//        String callingNumber = getValue("callingNumber");
//        String recordType = getValue("recordType");
//        String destinationNumber = getValue("cAMELDestinationNumber");
//        String origination = getValue("origination");
//
//        if (recordType != null) {
//            if (recordType.equalsIgnoreCase("6")) {
//                if (destinationNumber != null) {
//                    if (destinationNumber.startsWith("81") || destinationNumber.startsWith("91")) {
//                        otherMsisdn = destinationNumber.substring(2);
//                        otherMsisdn = normalizeMSISDN(otherMsisdn);
//                    }
//                }
//            } else if (recordType.equalsIgnoreCase("7")) {
//                if (origination != null)
//                    otherMsisdn = normalizeMSISDN(origination);
//            } else if (Arrays.asList(new String[]{"0", "4"}).contains(recordType)) {
//                if (calledNumber != null && calledNumber.length() > 1) {
//                    if (Arrays.asList(new String[]{"A1", "81", "91", "00"}).contains(calledNumber.substring(0, 2))) {
//                        otherMsisdn = calledNumber.substring(2);
//                        otherMsisdn = normalizeMSISDN(otherMsisdn);
//                    }
//                }
//            } else {
//                if (Arrays.asList(new String[]{"1", "2", "3", "5", "100"}).contains(recordType)) {
//                    if (callingNumber != null) {
//                        if (callingNumber.startsWith("A1") || callingNumber.startsWith("91")) {
//                            otherMsisdn = callingNumber.substring(2);
//                            otherMsisdn = normalizeMSISDN(otherMsisdn);
//                        }
//                    }
//                }
//            }
//        }
//        if (otherMsisdn != null)
//            record.put("other_msisdn", otherMsisdn);
//    }
//
//    private boolean in(String recordType, String[] strings) {
//        return Arrays.asList(strings).contains(recordType);
//    }
//
//    public void thirdPartyMSISDN() { // default
//        String recordType = getValue("recordType");
//        String thirdMsisdn = null;
//        if (recordType != null) {
//            if (recordType.equals("100")) {
//                String calledNumber = getValue("calledNumber");
//                if (calledNumber == null || calledNumber.isEmpty()) {
//                    String connectedNumber = getValue("connectedNumber");
//                    if (connectedNumber != null)
//                        thirdMsisdn = normalizeMSISDN(connectedNumber);
//                } else
//                    thirdMsisdn = normalizeMSISDN(calledNumber);
//            } else if (recordType.equals("2")) {
//                String roamingNumber = getValue("roamingNumber");
//                if (roamingNumber != null)
//                    thirdMsisdn = roamingNumber;
//            }
//        }
//        if (thirdMsisdn != null)
//            record.put("third_party_msisdn", thirdMsisdn);
//    }
//
//    public void servedMSRN() {// default "-99";
//        String roamingNumber = getValue("roamingNumber");
//        if (roamingNumber != null) {
//            if (roamingNumber.length() == 8)
//                roamingNumber = "229" + roamingNumber;
//            record.put("served_msrn", roamingNumber);
//        }
//    }
//
//    ReferenceDimDialDigit getDialedDigitSettings(String servedMSISDN) {
//        return null; // txLib.getDialedDigitSettings(servedMSISDN);
//    }
//
//    String normalizeMSISDN(String number) {
//        if (number != null) {
//            if (number.startsWith("0"))
//                number = ltrim(number, '0');
//
//            if (number.length() < 9) {
//                if (number.startsWith("229"))
//                    return number;
//                else
//                    number = "229" + number;
//            }
//            return number;
//        }
//        return "";
//    }
//
//    private static final Map<String, SimpleDateFormat> sdfMap = new LinkedHashMap<>();
//
//    private String changeDateFormat(String dateString, String fromFormat, String toFormat) throws ParseException {
//        SimpleDateFormat sdfS = sdfMap.get(fromFormat);
//        if (sdfS == null) {
//            sdfS = new SimpleDateFormat(fromFormat);
//            sdfMap.put(fromFormat, sdfS);
//        }
//        Date time = sdfS.parse(dateString);
//
//        SimpleDateFormat sdfT = sdfMap.get(toFormat);
//        if (sdfT == null) {
//            sdfT = new SimpleDateFormat(toFormat);
//            sdfMap.put(toFormat, sdfT);
//        }
//        return sdfT.format(time);
//    }
//
//    public void startTime() {
//        String recordType = getValue("recordType");
//        String yyyyMMdd = "";
//        if (recordType != null) {
//            try {
//                String startTime = "";
//                switch (recordType) {
//                    case "6":
//                        String orgTime = getValue("originationTime");
//                        if (orgTime != null) {
//                            startTime = changeDateFormat(orgTime, "yyMMddHHmmss Z", "yyyy-MM-dd HH:mm:ss");
//                            yyyyMMdd = changeDateFormat(orgTime, "yyMMddHHmmss Z", "yyyyMMdd");
//                        }
//                        break;
//                    case "7":
//                        String deliveryTime = getValue("deliveryTime");
//                        if (deliveryTime != null) {
//                            startTime = changeDateFormat(deliveryTime, "yyMMddHHmmss Z", "yyyy-MM-dd HH:mm:ss");
//                            yyyyMMdd = changeDateFormat(deliveryTime, "yyMMddHHmmss Z", "yyyyMMdd");
//                        }
//                        break;
//                    default:
//                        String answerTime = getValue("answerTime");
//                        if (answerTime != null) {
//                            startTime = changeDateFormat(answerTime, "yyMMddHHmmss Z", "yyyy-MM-dd HH:mm:ss");
//                            yyyyMMdd = changeDateFormat(answerTime, "yyMMddHHmmss Z", "yyyyMMdd");
//                        }
//                        break;
//                }
//                record.put("event_start_time", startTime);
//                record.put("xdr_date", startTime);
//                record.put("event_date", yyyyMMdd);
//            } catch (ParseException e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
//    public void endTime() {
//        try {
//            String releaseTime = getValue("releaseTime");
//            if (releaseTime != null) {
//                String time = changeDateFormat(releaseTime, "yyMMddHHmmss Z", "yyyy-MM-dd HH:mm:ss");
//                record.put("event_end_time", time);
//            }
//        } catch (ParseException ignore) {
//        }
//    }
//
////    private Date computeLocalDateTime(Date cdrRecordDate, String utcOffsetCode) {
////        int localOffsetInMin = ZoneOffset.of(utcOffsetCode).getTotalSeconds() / 60;
////        return DateUtility.addMinutesToDate(cdrRecordDate, localOffsetInMin);
////    }
//
//    public void zeroDurationInd() {
//        String callDuration = getValue("callDuration");
//        if (callDuration != null)
//            try {
//                int duration = Integer.parseInt(callDuration);
//                String zeroDurationInd;
//                if (duration == 0)
//                    zeroDurationInd = "1";
//                else
//                    zeroDurationInd = "0";
//                record.put("zero_duration_ind", zeroDurationInd);
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//    }
//
//    public void lac() {
//        String locationCell = getValue("locationAreaCode");
//        if (locationCell != null) {
//            try {
//                int lac = Integer.parseInt(locationCell, 16);
//                record.put("lac", lac);
//            } catch (NumberFormatException ignore) {
//            }
//        }
//    }
//
//    public void cellID() {
//        String locationArea = getValue("loc_cellIdentifier");
//        if (locationArea != null) {
//            try {
//                int cell = Integer.parseInt(locationArea, 16);
//                record.put("cell_id", cell);
//            } catch (NumberFormatException ignore) {
//            }
//        }
//    }
//
//    public void smscAddress() {
//        String smscAddress = getValue("serviceCentre");
//        if (smscAddress != null) {
//            String recordType = getValue("recordType");
//            if (recordType.equals("6") || recordType.equals("7"))
//                record.put("smsc_address", smscAddress);
//        }
//    }
//
//    public void mscAddress() {
//        String mscAddress = getValue("mSCAddress");
//        if (mscAddress != null) {
//            if (mscAddress.startsWith("91")) {
//                String mscAdd = mscAddress.substring(2);
//                record.put("msc_address", mscAdd);
//            }
//        }
//    }
//
//    public void servedMsisdnDialDigit() { // SERVED_MSISDN_DIAL_DIGIT_KEY
//        String servedMsisdn = getValue("served_msisdn");
//        try {
//            ReferenceDimDialDigit ddk = getDialedDigitSettings(servedMsisdn);
//            if (ddk != null) {
//                if (ddk.getDialDigitKey() != null)
//                    record.put("served_msisdn_dial_digit_key", ddk.getDialDigitKey());
//                if (ddk.getIsoCountryCode() != null)
//                    record.put("served_plmn_iso", ddk.getIsoCountryCode());
//            }
//        } catch (Exception ignore) {
//        }
//    }
//
//    public void servedMsrnDialDigit() { // SERVED_ROAM_DAILED_KEY
//        String servedMsrn = getValue("served_msrn");
//        if (servedMsrn != null) {
//            try {
//                ReferenceDimDialDigit ddk = getDialedDigitSettings(servedMsrn);
//                if (ddk != null && ddk.getDialDigitKey() != null)
//                    record.put("served_roam_dialed_key", ddk.getDialDigitKey());
//            } catch (Exception ignore) {
//            }
//        }
//    }
//
//    public void thirdPartyMsisdnDialDigit() {  // THIRD_PARTY_MSISDN
//        String thirdParty = getValue("third_party_msisdn");
//        if (thirdParty != null) {
//            try {
//                ReferenceDimDialDigit ddk = getDialedDigitSettings(thirdParty);
//                if (ddk != null && ddk.getDialDigitKey() != null)
//                    record.put("third_party_msisdn_dial_digit_key", ddk.getDialDigitKey());
//            } catch (Exception ignore) {
//            }
//        }
//    }
//
//    // other_msisdn_dial_digit_key , other_party_iso ,other_msisdn_operator, other_party_nw_ind_key
//    public void otherPartyMsisdnDialDigit() {
//        Object otherMSISDN = getValue("other_msisdn");
//        if (otherMSISDN != null) {
//            ReferenceDimDialDigit ddk = getDialedDigitSettings(otherMSISDN.toString());
//            try {
//                if (ddk != null) {
//                    String key = ddk.getDialDigitKey();
//                    if (key != null)
//                        record.put("other_msisdn_dial_digit_key", key);
//
//                    String isoCountryCode = ddk.getIsoCountryCode();
//                    if (isoCountryCode != null)
//                        record.put("other_party_iso", isoCountryCode);
//
//                    String providerDesc = ddk.getProviderDesc();
//                    if (providerDesc != null) {
//                        record.put("other_msisdn_operator", providerDesc);
//
//                        String targetCountryCode = ddk.getTargetCountryCode();
//                        if (targetCountryCode != null) {
//                            String opNwIndKey;
//                            if (providerDesc.toLowerCase().contains("sbin"))
//                                opNwIndKey = "1";
//                            else
//                                opNwIndKey = "2";
//
//                            if (!targetCountryCode.equals(COUNTRY_CODE))
//                                opNwIndKey = "3";
//
//                            Object served_type = getValue("served_type");
//                            if (served_type != null && served_type.equals("3"))
//                                opNwIndKey = "";
//                            record.put("other_party_nw_ind_key", opNwIndKey);
//                        }
//                    }
//                }
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }
//    }
//
//}