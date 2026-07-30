package com.gamma.skybase.decoder.asn2;//package com.gamma.skybase.decoder.asn;
//
//import com.gamma.components.commons.app.AppConfig;
//import com.gamma.skybase.contract.decoders.IEnrichment;
//import com.gamma.skybase.contract.decoders.MEnrichmentReq;
//import com.gamma.skybase.contract.decoders.MEnrichmentResponse;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import zteMsc.ZteMscTxUtil;
//
//import java.text.ParseException;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.LinkedHashMap;
//
//public class HuwAsnMscRecordEnrichment implements IEnrichment {
////    private static final Logger logger = LoggerFactory.getLogger(HuwAsnMscRecordEnrichment.class);
//    private static final ThreadLocal<SimpleDateFormat> sdfT = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
//    private static final String COUNTRY_CODE = AppConfig.instance().getProperty("app.datasource.countrycode");
//
//    @Override
//    public MEnrichmentResponse transform(MEnrichmentReq request) {
//        MEnrichmentResponse response = new MEnrichmentResponse();
//        LinkedHashMap<String, Object> record = request.getRequest();
//
//        LinkedHashMap<String, Object> output = transform(record);
//
//        if (output == null || output.isEmpty()) {
//            response.setResponseCode(false);
//        } else {
//            response.setResponseCode(true);
//            response.setResponse(output);
//        }
//        return response;
//    }
//
//    @Override
//    public LinkedHashMap<String, Object> transform(LinkedHashMap<String, Object> data) {
//        LinkedHashMap<String, Object> record = new LinkedHashMap<>(data);
//        try {
//            ZteMscTxUtil tx = ZteMscTxUtil.of(record);
//            tx.eventType();  //            EVENT_TYPE_KEY
//
//            tx.startTime();  //        EVENT_START_TIME
//
//            tx.endTime();  //            event_end_time
//
//            tx.eventDirection();  //            EVENT_DIRECTION_KEY
//
//            tx.servedMSISDN();  //            SERVED_MSISDN
//
//            tx.servedMSRN();  //            SERVED_MSRN
//
//            tx.servedIMSI();  //            SERVED_IMSI
//
//            tx.servedIMEI();  //            served_IMEI
//
//            tx.otherPartyMSISDN();  //            OTHER_MSISDN
//
//            tx.thirdPartyMSISDN();   //            THIRD_PARTY_MSISDN
//
//            tx.cellID();  //            cell_id
//
//            tx.lac();  //            lac
//
//            tx.zeroDurationInd();  //            ZeroDurationInd
//
//            tx.smscAddress();  //        smsc_address
//
//            tx.mscAddress();  //        msc_address
//
//            //  Enrichment for served, other, third party and other lookups
//            tx.servedType();//            SERVED_TYPE
//
//            // served_msisdn_dial_digit_key, served_plmn_iso
//            tx.servedMsisdnDialDigit();
//
//            // served_roam_dialed_key
//            tx.servedMsrnDialDigit();
//
//            // other_msisdn_dial_digit_key , other_party_iso ,other_msisdn_operator, other_party_nw_ind_key
//            tx.otherPartyMsisdnDialDigit();
//
//            // third_party_msisdn_dial_digit_key
//            tx.thirdPartyMsisdnDialDigit();
//
//            record.put("population_date", sdfT.get().format(new Date()));
//        } catch (ParseException e) {
//            throw new RuntimeException(e);
//        }
//        return record;
//    }
//
//}