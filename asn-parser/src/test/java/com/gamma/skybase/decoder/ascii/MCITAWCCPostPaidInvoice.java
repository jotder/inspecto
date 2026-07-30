package com.gamma.skybase.decoder.ascii;

import com.fasterxml.aalto.stax.InputFactoryImpl;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class MCITAWCCPostPaidInvoice {

    static String[] EVENT_LIST = {"CustRef", "Sums", "Charge", "", "Contract", "PerCTInfo"};

//    static String[] CombinedEvent = {"NetworkCallReferenceId", "VLRInfo", "mscAddress", "OnNet", "UliType", "LAC", "CID", "LocationRegion", "RatingGroup", "ChargedPartyMccMnc", "sessionIdCust", "ccRequestType", "ccRequestNumber", "BPartyMsisdn", "APartyMsisdn", "OtherPartyCountry", "ChargedPartyCountry", "originationCCRegion", "Imsi", "RoamingFlag", "CallUsageType", "iddCCRegion", "RoamingCarrier", "originationCarrier", "CallType", "AccountBrand", "SubscriberType", "MSISDN", "QuantityType", "QuantityUnit", "Flags", "MsgAmount", "RatingAmount", "SessionId", "UsageUtcOffset", "LastUsageRoundingAmount", "UsageRoundingAmount", "UsageRoundingAmountUnit", "value", "TemplateId", "Amount", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferResourceId", "ProductOfferVersion", "AppliedBundleIndex", "AggregationId", "BundleId", "BundleResourceId", "BundleVersion", "TextCharge", "TextGrant", "VoiceCharge", "VoiceGrant", "DataCharge", "DataGrant", "Remarks", "AppliedCatalogItemIndex", "CatalogItemId", "CatalogItemExternalId", "CatalogItemResourceId", "OfferType", "TextGrantLimit", "ProductNamePushto", "ProductNameDari", "InitiatorId", "InitiatorExternalId", "InitiatorDeviceId", "InitiatorDeviceExternalId", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "AppliedOfferIndex", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "Duration", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType", "ApplicationName", "ApplicationCategory", "APN", "SgsnIpAddress", "ChargingId", "ChargingIdInt", "SubState", "NetworkImei", "RATType", "AddlInfo1", "AddlInfo2", "RenewalPolicy", "Bonus", "RecurrenceFlag", "BalanceIntervalId", "BundleExternalId", "InitiatorPrimaryUserExternalId", "PreActiveState", "OfferId", "OfferVersion", "ExternalId", "ResourceId", "CurrencyCharge", "ServiceAction", "ExternalProductId", "ChannelId", "TransactionId", "VendorId", "Reason", "Info", "GrossAmountBefore", "GrossAmountAfter", "DebtBalanceType", "SAC", "UserId", "ApplicationCharge", "ApplicationGrant", "ProfileId", "ProfileName", "ProfileDescription", "OriginalCalledStationId", "RedirectStationId", "RedirectReason", "ServiceId", "TAC", "ECI", "VoiceGrantLimit", "OldBalanceEndTime", "CurrencyGrant", "LoanAmount", "LoanServiceFee", "MinimumAge", "RechargeTax", "SerialNo", "VoucherType", "LastSubStatus", "CardFaceValue", "SourceMSISDN", "Name", "Rate", "AppliedTaxIndex", "Tag", "AppliedRateTagIndex", "StartTime", "EndTime", "CancelEndTime", "PurchaseEventId", "IsSysInit", "RoamingVoiceCharge", "RoamingDataCharge", "IntlVoiceCharge", "IntlTextCharge", "StatusValue", "StatusDescription", "FirstName", "LastName", "ContactEmail", "ContactPhoneNumber", "NotificationPreference", "Language", "CustomerType", "ActivationDateTime", "MgmtState", "MgmtStateReason", "DeviceType", "AssociatedEventId", "CycleType", "CycleIntervalId", "CycleStartTime", "CycleEndTime", "CyclePurchasedItemResourceId", "CycleCatalogItemId", "CycleCatalogItemExternalId", "TimeWindow", "ThresholdId", "ThresholdName", "Type", "ThresholdAmount", "OwnerId", "LastCycleOffset", "NextCycleOffset", "LastCycleTimeOfDay", "NextCycleTimeOfDay", "LastCycleAlignmentType", "NextCycleAlignmentType", "LastCycleStartTime", "LastCycleOriginalEndTime", "NextCycleStartTime", "NextCycleEndTime", "LastCycleIntervalId", "NextCycleIntervalId", "ServiceFee", "TransferType", "DataLoanAmount", "TargetMSISDN", "ProductOfferIsTaxIncluded"};
//    static String[] RAFVoiceEvent = {"NetworkCallReferenceId", "VLRInfo", "mscAddress", "OnNet", "UliType", "LAC", "CID", "LocationRegion", "RatingGroup", "ChargedPartyMccMnc", "sessionIdCust", "ccRequestType", "ccRequestNumber", "BPartyMsisdn", "APartyMsisdn", "OtherPartyCountry", "ChargedPartyCountry", "originationCCRegion", "Imsi", "RoamingFlag", "CallUsageType", "iddCCRegion", "RoamingCarrier", "originationCarrier", "CallType", "AccountBrand", "SubscriberType", "MSISDN", "QuantityType", "QuantityUnit", "Flags", "MsgAmount", "RatingAmount", "SessionId", "UsageUtcOffset", "LastUsageRoundingAmount", "UsageRoundingAmount", "UsageRoundingAmountUnit", "value", "TemplateId", "Amount", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferResourceId", "ProductOfferVersion", "AppliedBundleIndex", "AggregationId", "BundleId", "BundleResourceId", "BundleVersion", "TextCharge", "TextGrant", "VoiceCharge", "VoiceGrant", "DataCharge", "DataGrant", "Remarks", "AppliedCatalogItemIndex", "CatalogItemId", "CatalogItemExternalId", "CatalogItemResourceId", "OfferType", "TextGrantLimit", "ProductNamePushto", "ProductNameDari", "InitiatorId", "InitiatorExternalId", "InitiatorDeviceId", "InitiatorDeviceExternalId", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "AppliedOfferIndex", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "Duration", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType", "BundleExternalId", "InitiatorPrimaryUserExternalId", "AddlInfo2", "RenewalPolicy", "Bonus", "BalanceIntervalId", "UserId", "OriginalCalledStationId", "RedirectStationId", "RedirectReason", "VoiceGrantLimit", "Name", "ExternalId", "Rate", "AppliedTaxIndex", "Tag", "AppliedRateTagIndex", "RecurrenceFlag", "ApplicationCharge", "CurrencyCharge", "RoamingVoiceCharge", "RoamingDataCharge", "IntlVoiceCharge", "IntlTextCharge", "TimeWindow"};
//    static String[] RAFDataEvent = {"ApplicationName", "ApplicationCategory", "UliType", "APN", "LAC", "CID", "SgsnIpAddress", "ChargingId", "ChargingIdInt", "LocationRegion", "RatingGroup", "ChargedPartyMccMnc", "sessionIdCust", "ccRequestType", "ccRequestNumber", "BPartyMsisdn", "APartyMsisdn", "ChargedPartyCountry", "originationCCRegion", "Imsi", "RoamingFlag", "CallUsageType", "RoamingCarrier", "originationCarrier", "AccountBrand", "SubscriberType", "MSISDN", "SubState", "NetworkImei", "RATType", "QuantityType", "QuantityUnit", "Flags", "MsgAmount", "RatingAmount", "SessionId", "UsageUtcOffset", "LastUsageRoundingAmount", "UsageRoundingAmount", "UsageRoundingAmountUnit", "value", "TemplateId", "Amount", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferResourceId", "ProductOfferVersion", "AppliedBundleIndex", "AggregationId", "BundleId", "BundleResourceId", "BundleVersion", "AppliedCatalogItemIndex", "CatalogItemId", "CatalogItemExternalId", "CatalogItemResourceId", "OfferType", "DataCharge", "DataGrant", "ProductNamePushto", "ProductNameDari", "AddlInfo1", "AddlInfo2", "RenewalPolicy", "Bonus", "RecurrenceFlag", "InitiatorId", "InitiatorExternalId", "InitiatorDeviceId", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceIntervalId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "AppliedOfferIndex", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "Duration", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType", "SAC", "InitiatorDeviceExternalId", "InitiatorPrimaryUserExternalId", "ApplicationCharge", "ApplicationGrant", "CurrencyCharge", "UserId", "TAC", "ECI", "TextCharge", "TextGrant", "VoiceCharge", "VoiceGrant", "Remarks", "TextGrantLimit", "BundleExternalId", "LoanAmount", "LoanServiceFee", "MinimumAge", "RoamingVoiceCharge", "RoamingDataCharge", "IntlVoiceCharge", "IntlTextCharge", "ChannelId", "TransactionId", "SourceMSISDN", "TargetMSISDN", "TransferType", "ServiceFee"};
//    static String[] RAFPurchaseEvent = {"AccountBrand", "SubscriberType", "MSISDN", "PreActiveState", "OfferId", "OfferVersion", "ExternalId", "ResourceId", "CatalogItemId", "CatalogItemExternalId", "OfferType", "CurrencyCharge", "ServiceAction", "ProductNamePushto", "ProductNameDari", "BundleId", "BundleVersion", "ExternalProductId", "ChannelId", "TransactionId", "VendorId", "Remarks", "AddlInfo1", "AddlInfo2", "Reason", "Info", "value", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferVersion", "AppliedBundleIndex", "BundleExternalId", "AppliedCatalogItemIndex", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "Amount", "GrossAmountBefore", "GrossAmountAfter", "DebtBalanceType", "AppliedOfferIndex", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType", "OldBalanceEndTime", "CurrencyGrant", "LoanAmount", "LoanServiceFee", "MinimumAge", "RechargeTax", "SerialNo", "VoucherType", "LastSubStatus", "CardFaceValue", "SourceMSISDN", "InitiatorPrimaryUserExternalId", "StartTime", "EndTime", "TextGrant", "TextGrantLimit", "VoiceGrant", "DataGrant", "UserId", "ProductOfferResourceId", "BundleResourceId", "CatalogItemResourceId", "DataCharge", "RenewalPolicy", "ApplicationCharge", "ApplicationGrant", "VoiceCharge", "BalanceIntervalId", "ServiceFee", "TransferType", "DataLoanAmount", "TargetMSISDN", "TextCharge"};
//    static String[] RAFPolicyChangeEvent = {"ProfileId", "ProfileName", "ProfileDescription", "value", "InitiatorId", "InitiatorExternalId", "InitiatorDeviceId", "InitiatorDeviceExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorPrimaryUserExternalId", "InitiatorType", "WalletOwnerType", "MSISDN", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferResourceId", "ProductOfferVersion", "AppliedBundleIndex", "BundleId", "BundleExternalId", "BundleResourceId", "BundleVersion", "AppliedCatalogItemIndex", "CatalogItemId", "CatalogItemExternalId", "CatalogItemResourceId", "OfferType", "DataCharge", "DataGrant", "ProductNamePushto", "ProductNameDari", "RenewalPolicy"};
//    static String[] RAFTextEvent = {"RatingGroup", "sessionIdCust", "ccRequestType", "ccRequestNumber", "BPartyMsisdn", "APartyMsisdn", "OtherPartyCountry", "ChargedPartyCountry", "originationCCRegion", "Imsi", "RoamingFlag", "CallUsageType", "iddCCRegion", "RoamingCarrier", "originationCarrier", "AccountBrand", "SubscriberType", "MSISDN", "ServiceId", "QuantityType", "QuantityUnit", "Flags", "MsgAmount", "RatingAmount", "SessionId", "UsageUtcOffset", "LastUsageRoundingAmount", "UsageRoundingAmount", "UsageRoundingAmountUnit", "value", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferResourceId", "ProductOfferVersion", "AppliedBundleIndex", "BundleId", "BundleExternalId", "BundleResourceId", "BundleVersion", "AppliedCatalogItemIndex", "CatalogItemId", "CatalogItemExternalId", "CatalogItemResourceId", "OfferType", "InitiatorId", "InitiatorExternalId", "InitiatorDeviceId", "InitiatorDeviceExternalId", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "Amount", "GrossAmountBefore", "GrossAmountAfter", "DebtBalanceType", "AppliedOfferIndex", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "Duration", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorPrimaryUserExternalId", "InitiatorType", "WalletOwnerType", "TextCharge", "TextGrant", "ProductNamePushto", "ProductNameDari", "RenewalPolicy", "BalanceIntervalId", "TextGrantLimit", "VoiceCharge", "VoiceGrant", "AddlInfo2", "Bonus", "DataCharge", "DataGrant", "UserId", "CurrencyCharge"};
//    static String[] RAFCancelEvent = {"AccountBrand", "SubscriberType", "MSISDN", "CancelEndTime", "OfferId", "OfferVersion", "ExternalId", "ResourceId", "StartTime", "EndTime", "CatalogItemId", "CatalogItemExternalId", "OfferType", "TextGrant", "TextGrantLimit", "VoiceCharge", "VoiceGrant", "ProductNamePushto", "ProductNameDari", "AddlInfo2", "RenewalPolicy", "Bonus", "BundleId", "BundleVersion", "UserId", "PurchaseEventId", "Reason", "Info", "IsSysInit", "value", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorPrimaryUserExternalId", "InitiatorType", "WalletOwnerType"};
//    static String[] RAFUserDeleteEvent = {"StatusValue", "StatusDescription", "UserId", "FirstName", "LastName", "ContactEmail", "ContactPhoneNumber", "NotificationPreference", "Language", "ExternalId", "value", "InitiatorId", "InitiatorExternalId", "Flags", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType"};
//    static String[] RAFSubscriptionDeleteEvent = {"StatusValue", "StatusDescription", "AccountBrand", "CustomerType", "SubscriberType", "ActivationDateTime", "MgmtState", "MgmtStateReason", "ExternalId", "value", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "EventTime", "EventId", "DeleteCode", "InitiatorType", "WalletOwnerType"};
//    static String[] RAFDeviceDeleteEvent = {"StatusValue", "StatusDescription", "DeviceType", "Imsi", "value", "ExternalId", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "EventTime", "EventId", "DeleteCode", "InitiatorType", "WalletOwnerType"};
//    static String[] RAFBalanceAdjustEvent = {"AccountBrand", "SubscriberType", "MSISDN", "Reason", "value", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceIntervalId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "Amount", "GrossAmountBefore", "GrossAmountAfter", "DebtBalanceType", "UsageQuantity", "UsageQuantityUnit", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "EventId", "DeleteCode", "OldBalanceEndTime", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType", "InitiatorPrimaryUserExternalId"};
//    static String[] RAFRecurringEvent = {"AccountBrand", "SubscriberType", "MSISDN", "AssociatedEventId", "CycleType", "CycleIntervalId", "CycleStartTime", "CycleEndTime", "CyclePurchasedItemResourceId", "CycleCatalogItemId", "CycleCatalogItemExternalId", "value", "UsageQuantity", "UsageQuantityUnit", "ProductOfferId", "ProductOfferExternalId", "ProductOfferOwnerId", "ProductOfferExternalOwnerId", "ProductOfferResourceId", "ProductOfferVersion", "AppliedBundleIndex", "BundleId", "BundleResourceId", "BundleVersion", "AppliedCatalogItemIndex", "CatalogItemId", "CatalogItemExternalId", "CatalogItemResourceId", "OfferType", "DataCharge", "DataGrant", "ProductNamePushto", "ProductNameDari", "AddlInfo1", "RenewalPolicy", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "BalanceClassId", "BalanceTemplateId", "BalanceIntervalId", "BalanceResourceId", "BalanceStartTime", "BalanceEndTime", "Amount", "GrossAmountBefore", "GrossAmountAfter", "DebtBalanceType", "AppliedOfferIndex", "BalanceUpdateIndex", "UpdateType", "ImpactSource", "PaymentType", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType", "ApplicationCharge", "ApplicationGrant", "InitiatorPrimaryUserExternalId", "VoiceCharge", "VoiceGrant", "AddlInfo2", "Bonus", "RecurrenceFlag", "ProductOfferIsTaxIncluded", "BundleExternalId", "TextGrant", "TextCharge"};
//    static String[] RAFBalanceThresholdEvent = {"AccountBrand", "MSISDN", "SubscriberType", "ThresholdId", "ThresholdName", "BalanceTemplateId", "BalanceResourceId", "BalanceClassId", "StartTime", "EndTime", "Type", "ThresholdAmount", "value", "InitiatorId", "InitiatorExternalId", "InitiatorDeviceId", "InitiatorDeviceExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorPrimaryUserExternalId", "InitiatorType", "WalletOwnerType"};
//    static String[] RAFPurchasedItemCycleChangeEvent = {"OwnerId", "OfferId", "OfferVersion", "ExternalId", "ResourceId", "StartTime", "EndTime", "CatalogItemId", "CatalogItemExternalId", "OfferType", "DataCharge", "DataGrant", "ProductNamePushto", "ProductNameDari", "AddlInfo1", "AddlInfo2", "RenewalPolicy", "Bonus", "RecurrenceFlag", "BundleId", "BundleVersion", "LastCycleOffset", "NextCycleOffset", "LastCycleTimeOfDay", "NextCycleTimeOfDay", "LastCycleAlignmentType", "NextCycleAlignmentType", "LastCycleStartTime", "LastCycleOriginalEndTime", "NextCycleStartTime", "NextCycleEndTime", "LastCycleIntervalId", "NextCycleIntervalId", "value", "InitiatorId", "InitiatorExternalId", "Flags", "WalletId", "WalletOwnerId", "WalletOwnerExternalId", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType", "WalletOwnerType"};
//    static String[] RAFUserModifyEvent = {"Language", "UserId", "FirstName", "LastName", "ContactEmail", "ContactPhoneNumber", "NotificationPreference", "ExternalId", "value", "InitiatorId", "InitiatorExternalId", "Flags", "EventTime", "EventId", "DeleteCode", "InitiatorPrimaryUserId", "InitiatorType"};

    static Map<String, String[]> eventFields = new LinkedHashMap<>();
    static Map<String, Object> eventHeaders = new LinkedHashMap<>();

    private static final Set<String> eventTypes
            = new HashSet<>(Arrays.asList("Sums", "CustRef"));

    public static void main(String[] args) throws XMLStreamException, IOException {

        String file2Parse = "C:\\projects\\asn-decoders\\generic-asn-reader\\data\\mcit\\awcc\\postpaid_invoice_dump\\SUM10314.50957.xml";

        eventFields.put("EVENT_LIST", EVENT_LIST);
//        eventFields.put("RAFUserModifyEvent", RAFUserModifyEvent);

        try {
            InputStream inputStream = Files.newInputStream(Paths.get(file2Parse));
            parse(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void parse(InputStream inputStream) throws XMLStreamException, IOException, XMLStreamException {
        XMLStreamReader reader = new InputFactoryImpl().createXMLStreamReader(inputStream);
        while (reader.hasNext()) {
            int event = reader.next();
            Map<String, Object> record = null;
            if (event == XMLEvent.START_ELEMENT) {
                String currentElement = reader.getLocalName();
                if (eventTypes.contains(currentElement)) {
                    Map<String, Object> attribMap = new LinkedHashMap<>();

                    // Capture attributes
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        String attributeName = reader.getAttributeLocalName(i);
                        String attributeValue = reader.getAttributeValue(i);
                        attribMap.put(attributeName, attributeValue);
                    }
                    record = new LinkedHashMap<>();
                    parseElement(record, currentElement, reader, "");
                    System.out.println("\n" + currentElement + "->\n" + record);
                }
            }
        }
    }

    private static void parseElement(Map<String, Object> record, String tag, XMLStreamReader reader, String ind) throws XMLStreamException {
        String indent = ind + "\t";
//        System.out.println("=>" + tag);
        Stack<String> tagStack = new Stack<>();
        tagStack.push(tag);
        Stack<Object> contentStack = new Stack<>();
        contentStack.push(record);

        boolean tuple = false;
        StringBuilder content = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLEvent.START_ELEMENT:
                    String startElement = reader.getLocalName();
                    tagStack.push(startElement);
//                    if (startElement.equals("Sums") || startElement.endsWith("CustRef")) {
                        contentStack.push(new ArrayList<>());
                        Map<String, Object> attribMap = new LinkedHashMap<>();

                        // Capture attributes
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            String attributeName = reader.getAttributeLocalName(i);
                            String attributeValue = reader.getAttributeValue(i);
                            attribMap.put(attributeName, attributeValue);
                        }
//                    } else
//                        contentStack.push(new LinkedHashMap<>());
                    content.setLength(0);
                    break;
                case XMLEvent.CHARACTERS:
                    if (reader.hasText()) {
                        String txt = reader.getText();
                        if (!txt.trim().isEmpty()) {
                            tuple = true;
                            content.append(txt.trim());
                        }
//                        else
//                            System.out.print("");
                    } else
                        System.out.println("<><>");
                    break;

                case XMLEvent.END_ELEMENT:
                    String endElement = reader.getLocalName();
                    if (tag.equals(endElement))
                        return;

                    String key = tagStack.pop();
                    if (key.equals(endElement)) {
                        Object obj = contentStack.pop();
                        Object parentContainer = contentStack.peek();
                        if (tuple) { // Tuples
                            tuple = false;
                            if (contentStack.isEmpty())
                                System.out.println(indent + "fix it ");
                            else {
                                if (parentContainer instanceof Map)
                                    (((Map<String, Object>) parentContainer)).put(key, content.toString());
                                if (parentContainer instanceof List)
                                    (((List<Object>) parentContainer)).add(content.toString());
                            }
                        } else { // parent node
                            if (parentContainer instanceof Map)
                                (((Map<String, Object>) parentContainer)).put(key, obj);
                            if (parentContainer instanceof List)
                                (((List<Object>) parentContainer)).add(obj);
//                            System.out.println(indent + "Val -> " + key + ": " + obj);
                        }
                        content.setLength(0);
                    } else {
//                        Map<String, Object> obj1 = contentStack.peek();
//                        obj1.put(endElement, obj);
                        System.out.println(endElement + " ~ ~ " + tag);
                    }
            }
        }
    }

    private static Map<String, Object> createEmptyRecord(String eventName) {
        String[] fields = eventFields.get(eventName);
        Map<String, Object> emptyRecord = new LinkedHashMap<>();
        Arrays.stream(fields).forEach(e -> emptyRecord.put(e, ""));
        return emptyRecord;
    }
}
