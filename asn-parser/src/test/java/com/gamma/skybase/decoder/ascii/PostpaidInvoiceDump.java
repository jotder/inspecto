package com.gamma.skybase.decoder.ascii;

import com.fasterxml.aalto.stax.InputFactoryImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


public class PostpaidInvoiceDump {

    static String[] postpaid_inv_doc = {"Id", "BAId", "Sender", "ChargeId", "Amount", "CurrCode", "Type", "PT"};

    static String[] postpaid_inv_cust = {"DocID", "CustRefId", "CustCode", "ChargeId", "Amount", "CurrCode", "Type", "PT", "Name", "City", "Zip", "Country", "Line1", "Line2", "Line3", "Line4", "Line5", "Line6", "SortCrit"};

    static String[] postpaid_inv_contract = {"DocID", "CustRefId", "Num", "Main", "ContractId", "ChargeId", "Amount", "CurrCode", "Type", "PT", "MRKT", "SM", "BOPInd"};

    static String[] postpaid_inv_perctinfo = {"DocID", "CustRefId", "Num", "Main", "ContractId", "CT", "PT", "ChargeId", "Amount", "CurrCode", "Type"};

    static String[] postpaid_inv_detail = {"DocID", "CustRefId", "Num", "Main", "ContractId", "CT", "PT", "ChargeId", "Amount", "CurrCode", "Type", "ArticleString", "LZString", "NumItems", "Txt", "Price", "PRO", "TM", "SP", "SN"};

    static Map<String, FileWriter> eventWriters = new LinkedHashMap<>();

    //
    static Map<String, String[]> eventFields = new LinkedHashMap<>();
    //    static Map<String, Object> eventHeaders = new LinkedHashMap<>();

    public static Map<String, Object> getOwnProperties(XMLStreamReader reader) {
        Map<String, Object> map = new LinkedHashMap<>();
        int count = reader.getAttributeCount();
        IntStream.rangeClosed(0, count - 1).forEach(e -> {
            map.put(reader.getAttributeName(e).toString(), reader.getAttributeValue(e));
        });
        return map;
    }

    private List<Map<String, Object>> cartesianJoin(List<Map<String, Object>> rs1, List<Map<String, Object>> rs2) {
        List<Map<String, Object>> recSet = new ArrayList<>();
        rs1.forEach(r1 -> rs2.forEach(r2 -> {
            Map<String, Object> x = new LinkedHashMap<>(r1);
            x.putAll(r2);
            recSet.add(x);
        }));
        return recSet;
    }

    private static List<Map<String, Object>> cartesianJoin(List<Map<String, Object>> rs1, Map<String, Object> r) {
        List<Map<String, Object>> recSet = new ArrayList<>();
        rs1.forEach(r1 -> {
            Map<String, Object> x = new LinkedHashMap<>(r1);
            x.putAll(r);
            recSet.add(x);
        });
        return recSet;
    }

    private static Map<String, Object> createEmptyRecord(String eventName) {
        String[] fields = eventFields.get(eventName);
        Map<String, Object> emptyRecord = new LinkedHashMap<>();
        Arrays.stream(fields).forEach(e -> emptyRecord.put(e, ""));
        return emptyRecord;
    }

    public static void main(String[] args) {
        eventFields.put("postpaid_inv_doc", postpaid_inv_doc);
        eventFields.put("postpaid_inv_cust", postpaid_inv_cust);
        eventFields.put("postpaid_inv_contract", postpaid_inv_contract);
        eventFields.put("postpaid_inv_perctinfo", postpaid_inv_perctinfo);
        eventFields.put("postpaid_inv_detail", postpaid_inv_detail);

        eventFields.forEach((key, value) -> {
            FileWriter writer = eventWriters.computeIfAbsent(key, k -> createFileWriter(k + ".csv"));
            try {
                writer.write(Arrays.stream(value)
                        .map(Object::toString)
                        .collect(Collectors.joining(",")) + "\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        try {
            String fileName = ".\\data\\mcit\\awcc\\postpaid_invoice_dump\\SUM10314.50957.xml";
            XMLInputFactory inputFactory = new InputFactoryImpl();
            XMLStreamReader reader = inputFactory.createXMLStreamReader(Files.newInputStream(Paths.get(fileName)));

            ObjectMapper objectMapper = new ObjectMapper();


            Map<String, Object> dataTree = new LinkedHashMap<>();
            while (reader.hasNext()) {
                int eventType = reader.next();
                if (eventType == XMLStreamReader.START_ELEMENT)
                    dataTree = parseTree(reader);
            }
            System.out.println("");

            // Write Doc summary
            Map<String, Object> docAttr = (Map<String, Object>) dataTree.remove("ownAttr");
            Map<String, Object> sums = (Map<String, Object>) dataTree.remove("Sums");
            List<Map<String, Object>> sumsCharges = (List<Map<String, Object>>) sums.remove("Charge");

            List<Map<String, Object>> docSummary = cartesianJoin(sumsCharges, docAttr);

            writex("postpaid_inv_doc", docSummary);


            // Write CustRef summary with docID, custID
            Map<String, Object> custRef = (Map<String, Object>) dataTree.remove("CustRef");
            Map<String, Object> custRefAttr = (Map<String, Object>) custRef.remove("ownAttr");
            custRefAttr.put("DocID", docAttr.get("Id"));
            if (custRefAttr.containsKey("Id"))
                custRefAttr.put("CustRefId", custRefAttr.remove("Id"));
            Map<String, Object> custRefAddr = (Map<String, Object>) custRef.remove("Addr");
            custRefAttr.putAll(custRefAddr);

            List<Map<String, Object>> custCharges = (List<Map<String, Object>>) custRef.remove("Charge");

            List<Map<String, Object>> postpaid_inv_cust = cartesianJoin(custCharges, custRefAttr);

            writex("postpaid_inv_cust", postpaid_inv_cust);


            List<Map<String, Object>> contracts = (List<Map<String, Object>>) custRef.remove("Contract");
            contracts.forEach(e -> {
                Map<String, Object> contractAttr = (Map<String, Object>) e.get("ownAttr");
                if (contractAttr.containsKey("Id"))
                    contractAttr.put("ContractId", contractAttr.remove("Id"));
                contractAttr.put("DocID", custRefAttr.get("DocID"));
                contractAttr.put("CustRefId", custRefAttr.get("CustRefId"));
                Map<String, Object> dn = (Map<String, Object>) e.get("DN");
                contractAttr.putAll(dn);

                List<Map<String, Object>> contractCharges = (List<Map<String, Object>>) e.get("Charge");
                List<Map<String, Object>> postpaid_inv_contract = cartesianJoin(contractCharges, contractAttr);

                writex("postpaid_inv_contract", postpaid_inv_contract);

                List<Map<String, Object>> perCTInfo = (List<Map<String, Object>>) e.get("PerCTInfo");
                perCTInfo.forEach(p -> {
                    Map<String, Object> perCTInfoAttr = (Map<String, Object>) p.get("ownAttr");
                    perCTInfoAttr.put("ContractId", contractAttr.get("ContractId"));
                    perCTInfoAttr.putAll(dn);
                    perCTInfoAttr.put("DocID", custRefAttr.get("DocID"));
                    perCTInfoAttr.put("CustRefId", custRefAttr.get("CustRefId"));

                    List<Map<String, Object>> PerCTInfoCharges = (List<Map<String, Object>>) p.get("Charge");
                    List<Map<String, Object>> postpaid_inv_perctinfo = cartesianJoin(PerCTInfoCharges, perCTInfoAttr);

                    writex("postpaid_inv_perctinfo", postpaid_inv_perctinfo);

                    List<Map<String, Object>> sumItem = (List<Map<String, Object>>) p.get("SumItem");
                    sumItem.forEach(s -> {

                        Map<String, Object> sumItemAttr = (Map<String, Object>) s.get("ownAttr");
                        sumItemAttr.put("DocID", custRefAttr.get("DocID"));
                        sumItemAttr.put("CustRefId", custRefAttr.get("CustRefId"));
                        sumItemAttr.put("ContractId", contractAttr.get("ContractId"));
                        sumItemAttr.putAll(perCTInfoAttr);
                        Object txt = s.get("Txt");
                        if (txt != null) sumItemAttr.put("Txt", txt);
                        Map<String, Object> price = (Map<String, Object>) s.get("Price");
                        if (price != null)
                            sumItemAttr.putAll(price);

                        List<Map<String, Object>> sumItemCharges = (List<Map<String, Object>>) s.get("Charge");
                        List<Map<String, Object>> postpaid_inv_detail = cartesianJoin(sumItemCharges, sumItemAttr);

                        Map<String, Object> idTypes = new LinkedHashMap<>();
                        List<Map<String, Object>> att = (List<Map<String, Object>>) s.get("Att");
                        if (att != null)
                            att.forEach(x -> idTypes.put(x.get("Ty").toString(), x.get("Id")));
                        List<Map<String, Object>> postpaid_inv_detail1 = cartesianJoin(postpaid_inv_detail, idTypes);
//                        if(!idTypes.isEmpty())
//                            postpaid_inv_detail1 = cartesianJoin(postpaid_inv_detail, idTypes);

                        writex("postpaid_inv_detail", postpaid_inv_detail1);
                    });
                });
            });


            // Close all writers
            eventWriters.values().forEach(w -> {
                try {
                    w.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

//
//            String elementName = "Root";
////            if (Arrays.asList(EVENT_LIST).contains(elementName)) { // RAF events
//                ObjectNode eventNode = objectMapper.createObjectNode();
//                extractElement(reader, eventNode, elementName);
//                Map<String, Object> y = convertObjectNode(eventNode);
//
//                System.out.println("\n" + y.values().stream()
//                        .map(Object::toString)
//                        .collect(Collectors.joining(",")));
//
//                Map<String, Object> combinedEvent = createEmptyRecord("CombinedEvent");
//                combinedEvent.putAll(y);
//                FileWriter combinedEventWriter = eventWriters.computeIfAbsent("combinedEvent", k -> createFileWriter(k + ".csv"));
//                combinedEventWriter.write(combinedEvent.values().stream()
//                        .map(Object::toString)
//                        .collect(Collectors.joining(",")) + "\n");
//
//                Map<String, Object> record = createEmptyRecord(elementName);
//                record.putAll(y);
//                FileWriter writer = eventWriters.computeIfAbsent(elementName, k -> createFileWriter(k + ".csv"));
//                writer.write(record.values().stream()
//                        .map(Object::toString)
//                        .collect(Collectors.joining(",")) + "\n");
////            }


            reader.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void writex(String eventName, List<Map<String, Object>> records) {
        FileWriter writer = eventWriters.computeIfAbsent(eventName, k -> createFileWriter(k + ".csv"));
        records.forEach(e -> {
            Map<String, Object> rec = createEmptyRecord(eventName);
            e.keySet().forEach(x -> {
                if (!rec.containsKey(x))
                    System.out.println("Missing key " + x);
            });
            rec.putAll(e);
            try {
                writer.write(rec.values().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(",")) + "\n");
            } catch (IOException ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }
            // todo Write  --> Table PostPaid_Inv_Doc
        });
    }

    private static void changeAttrName(List<Map<String, Object>> sumsCharges, Map<String, String> renKeys) {
        renKeys.forEach((k, v) -> sumsCharges.forEach(e -> {
            if (e.containsKey(k)) e.put(k, e.remove(k));
        }));
    }

    private static Map<String, Object> parseTree(XMLStreamReader reader) throws XMLStreamException {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> op = getOwnProperties(reader);
        if (!op.isEmpty()) map.put("ownAttr", op);

        ArrayList<Map<String, Object>> contract, charge, sumItem, perCTInfo, aggSet, att;
        while (reader.hasNext()) {
            int eventType = reader.next();
            if (eventType == XMLStreamReader.START_ELEMENT) {

                String elementName = reader.getLocalName();
//                System.out.println(elementName + " start ");
                switch (elementName) {

                    case "Charge":
                        if (map.get(elementName) == null) {
                            charge = new ArrayList<>();
                            map.put(elementName, charge);
                        } else
                            charge = (ArrayList<Map<String, Object>>) map.get(elementName);

                        Map<String, Object> ownAttr = getOwnProperties(reader);
                        if (ownAttr.containsKey("Id"))
                            ownAttr.put("ChargeId", ownAttr.remove("Id"));
                        if (!ownAttr.get("Amount").equals("0.00"))
                            charge.add(ownAttr);

                        break;

                    case "Sums":

//                    case "AggSet":

                    case "CustRef":
                        map.put(elementName, parseTree(reader));
                        break;

                    case "Addr":

                    case "DN":

                    case "Price":
                        map.put(elementName, getOwnProperties(reader));
                        break;

                    case "Contract":
                        if (map.get(elementName) == null) {
                            contract = new ArrayList<>();
                            map.put(elementName, contract);
                        } else
                            contract = (ArrayList<Map<String, Object>>) map.get(elementName);
                        contract.add(parseTree(reader));
                        break;

                    case "PerCTInfo":
                        if (map.get(elementName) == null) {
                            perCTInfo = new ArrayList<>();
                            map.put(elementName, perCTInfo);
                        } else
                            perCTInfo = (ArrayList<Map<String, Object>>) map.get(elementName);

                        perCTInfo.add(parseTree(reader));
                        break;

                    case "SumItem":
                        if (map.get(elementName) == null) {
                            sumItem = new ArrayList<>();
                            map.put(elementName, sumItem);
                        } else
                            sumItem = (ArrayList<Map<String, Object>>) map.get(elementName);

                        sumItem.add(parseTree(reader));
                        break;

                    case "Txt":
                        String text = reader.getElementText();
                        map.put(elementName, text);
                        break;

                    case "Att":
                        if (map.get(elementName) == null) {
                            att = new ArrayList<>();
                            map.put(elementName, att);
                        } else
                            att = (ArrayList<Map<String, Object>>) map.get(elementName);
                        att.add(getOwnProperties(reader));
                        break;

                    case "Document":
                        return parseTree(reader);

                    default:
                        System.out.println("ignore .. " + elementName);
                }
            }
            if (eventType == XMLStreamReader.END_ELEMENT) {
                String elementName = reader.getLocalName();
//                System.out.println(elementName + " end ");

                switch (elementName) {
                    case "Document":
//                    case "Summary":
                    case "Sums":
                    case "CustRef":
                    case "Contract":
                    case "PerCTInfo":
                    case "SumItem":
//                    case "AggSet":
                        return map;
                    // continue for Charge, Addr, DN, Txt, Price, Att
                    default:
//                        System.out.println("");
                }
            }

        }
        return map;
    }

    private static void extractElement(XMLStreamReader reader, ObjectNode node, String name) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        while (reader.hasNext()) {
            int eventType = reader.next();
            if (eventType == XMLStreamReader.START_ELEMENT) {
                String childName = reader.getLocalName();
                extractElement(reader, (ObjectNode) node.get(name), childName);
            } else if (eventType == XMLStreamReader.CHARACTERS) {
                String text = reader.getText().trim();
                if (!text.isEmpty()) {
                    node.put(name, text);
                }
            } else if (eventType == XMLStreamReader.END_ELEMENT && reader.getLocalName().equals(name)) {
                break;
            }
        }
    }

    public static <T> T deepCopy(T original) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(byteOut);
        out.writeObject(original);
        ByteArrayInputStream byteIn = new ByteArrayInputStream(byteOut.toByteArray());
        ObjectInputStream in = new ObjectInputStream(byteIn);
        return (T) in.readObject();
    }

    public static Map<String, Object> convertObjectNode(ObjectNode node) {
        Map<String, Object> map = new LinkedHashMap<>(); // Preserve field order

        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String fieldName = it.next();
            JsonNode fieldValue = node.get(fieldName);
            if (fieldName.contains("Array")) {
                System.out.print("");
            }
            Object convertedJsonNode = convertJsonNode(fieldValue);

            if (convertedJsonNode instanceof Map) {
                if (fieldName.contains("Array")) {
                    Object list = map.get(fieldName);
                    if (list == null) {
                        list = new ArrayList<>();
                        ((ArrayList<Object>) list).add(convertedJsonNode);
                        map.put(fieldName, list);
                    } else {
                        ((List<Object>) list).add(convertedJsonNode);
                    }
                } else
                    map.putAll((Map) convertedJsonNode);
            } else
                map.put(fieldName, convertedJsonNode);

        }
        return map;
    }

    private static Object convertJsonNode(JsonNode node) {
        if (node.isObject()) {
            return convertObjectNode((ObjectNode) node);
        } else if (node.isArray()) {
            return convertArrayNode((ArrayNode) node);
        } else if (node.isValueNode()) {
            return node.asText();
        } else {
            return null; // Handle null values or other unexpected types
        }
    }

    private static List<Object> convertArrayNode(ArrayNode node) {
        List<Object> list = new ArrayList<>();
        for (JsonNode element : node) {
            list.add(convertJsonNode(element));
        }
        return list;
    }

    private static FileWriter createFileWriter(String filename) {
        try {
            return new FileWriter(filename);
        } catch (Exception e) {
            throw new RuntimeException("Error creating file: " + filename, e);
        }
    }

}
