package com.gamma.skybase.decoder.ascii;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class AwccVomsUsedVoucher {

    public static void main(String[] args) throws Exception {
        String srcDir = "./data/mcit/awcc/voms/used_voucher/efill_extrechargereport_hourly_20240717003000.csv";
        AwccVomsUsedVoucher reVoucher = new AwccVomsUsedVoucher();
        reVoucher.parse(srcDir);
        while (reVoucher.hasNext())
            System.out.println(reVoucher.next());
    }

    List<Map<String, Object>> records = new ArrayList<>();

    public static String readStringFromFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new String(bytes, StandardCharsets.UTF_8); // or any other appropriate charset
    }

    private void parse(String srcDir) throws IOException {
        AtomicReference<String[]> fileMDKeys = new AtomicReference<>();
        AtomicReference<String[]> fileMDVals = new AtomicReference<>();
        AtomicReference<String[]> recMDkeys = new AtomicReference<>();
        AtomicInteger lineNo = new AtomicInteger(0);
        String content = readStringFromFile(Paths.get(srcDir));
        try (Stream<String> lines = new BufferedReader(new StringReader(content)).lines()) {
            lines.filter(l -> !l.trim().isEmpty())
                    .forEach(l -> {
                        lineNo.getAndIncrement();
                        int ln = lineNo.get();

                        if (ln == 1)
                            fileMDKeys.set(l.split(";"));
                        else if (lineNo.get() == 2) {
                            fileMDVals.set(l.split(";"));
                        } else if (l.trim().startsWith("ExtTransactionId"))
                            recMDkeys.set(l.split(";"));
                        else {
                            String[] fhk = fileMDKeys.get();
                            String[] fhv = fileMDVals.get();
                            String[] rhk = recMDkeys.get();
                            String[] rhv = l.split(";");
                            if (fhk.length == fhv.length && rhk.length == rhv.length) {
                                LinkedHashMap<String, Object> rec = new LinkedHashMap<>(fhk.length + rhk.length);
                                for (int i = 0; i < fhk.length; i++) rec.put(fhk[i].trim(), fhv[i].trim());
                                for (int i = 0; i < rhk.length; i++) rec.put(rhk[i].trim(), rhv[i].trim());
                                records.add(rec);
                            } else
                                System.out.println("File header and it's values doesn't match !!");
                        }
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean hasNext() {
        return !records.isEmpty();
    }

    public synchronized Map<String, Object> next() {
        return records.remove(0);
    }
}

