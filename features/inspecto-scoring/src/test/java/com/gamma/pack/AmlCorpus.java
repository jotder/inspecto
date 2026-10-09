package com.gamma.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The deterministic SYNTHETIC golden corpus of the {@code aml} Space Template ({@code PACK-AML-1}): ten days of an
 * account ledger (2026-06-28 .. 2026-07-07) and one party-register snapshot. Fixed seed, fictional ids and names, no
 * real data; country codes XA..XF are ISO user-assigned (never a real country). The golden window is 2026-07-07
 * with a 6-day look-back (2026-07-01 .. 2026-07-08). Background accounts stay far under every threshold; each
 * typology plants offenders (one JUST past its threshold) and look-alikes (one exactly AT it, one just outside the
 * look-back, one of another kind). Regenerate the committed samples with {@link #main}.
 */
final class AmlCorpus {

    static final long SEED = 20261010L;
    static final LocalDate FIRST = LocalDate.of(2026, 6, 28), LAST = LocalDate.of(2026, 7, 7);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Random rnd = new Random(SEED);
    private final Map<LocalDate, StringBuilder> days = new TreeMap<>();
    private int seq;

    private static final String HEADER = "txn_id,txn_ts,txn_type,account_id,counterparty_id,amount,country\n";

    private AmlCorpus() {
        for (LocalDate d = FIRST; !d.isAfter(LAST); d = d.plusDays(1)) days.put(d, new StringBuilder(HEADER));
    }

    private static String acc(int n) { return String.format(Locale.ROOT, "AC%06d", n); }

    private void row(LocalDate d, int hour, int minute, String type, String account, String counterparty, String amount, String country) {
        days.get(d).append(String.format(Locale.ROOT, "T%07d,%s %02d:%02d:00,%s,%s,%s,%s,%s%n", ++seq, d, hour, minute, type,
                account, counterparty, amount, country).replace("\r", ""));
    }

    private static LocalDate d(int month, int day) { return LocalDate.of(2026, month, day); }

    private void deposit(LocalDate day, String account, String amount) { row(day, 10 + rnd.nextInt(6), rnd.nextInt(60), "CASH_DEPOSIT", account, "", amount, ""); }

    private void transfer(LocalDate day, String payer, String payee, String amount) { row(day, 9 + rnd.nextInt(8), rnd.nextInt(60), "TRANSFER", payer, payee, amount, "XD"); }

    private void wire(LocalDate day, String account, String amount, String country) { row(day, 11 + rnd.nextInt(5), rnd.nextInt(60), "WIRE", account, acc(990000 + rnd.nextInt(50)), amount, country); }

    private void build() {
        // Background: 120 accounts; each pays ONE fixed payee (a payee receives from at most two payers).
        for (LocalDate day = FIRST; !day.isAfter(LAST); day = day.plusDays(1))
            for (int a = 1; a <= 120; a++) {
                if (rnd.nextInt(2) == 0) deposit(day, acc(a), String.format(Locale.ROOT, "%d.%02d", 100 + rnd.nextInt(2400), rnd.nextInt(100)));
                if (rnd.nextInt(10) < 3) transfer(day, acc(a), acc(1000 + (a % 60)), String.format(Locale.ROOT, "%d.00", 20 + rnd.nextInt(380)));
                if (rnd.nextInt(20) == 0) wire(day, acc(a), String.format(Locale.ROOT, "%d.00", 100 + rnd.nextInt(800)), new String[]{"XD", "XE", "XF"}[rnd.nextInt(3)]);
            }

        // Structuring (alert: more than 4 deposits in [9000, 10000) inside the look-back)
        for (int i = 2; i <= 6; i++) deposit(d(7, i), acc(100001), "9500.00");               // S1 offender: 5
        for (int i = 1; i <= 7; i++) deposit(d(7, i), acc(100002), "9000.00");               // S2 offender: 7 (band floor is inclusive)
        for (int i = 3; i <= 6; i++) deposit(d(7, i), acc(100003), "9900.00");               // S3 AT the threshold: 4
        for (int i = 1; i <= 5; i++) deposit(d(7, i), acc(100004), "10000.00");              // S4 at the limit, not under it
        for (int i = 1; i <= 6; i++) deposit(d(7, i), acc(100005), "8999.99");               // S5 just under the band
        for (int i = 28; i <= 30; i++) deposit(d(6, i), acc(100006), "9500.00");             // S6 outside the look-back
        deposit(d(7, 1), acc(100006), "9500.00"); deposit(d(7, 2), acc(100006), "9500.00");  //    (3 + 2 = 2 inside)

        // Threshold crossing (alert: a day's cash over 10000.00, day 2026-07-07)
        deposit(d(7, 7), acc(200001), "10000.01");                                           // T1 offender: single
        deposit(d(7, 7), acc(200002), "4000.00"); deposit(d(7, 7), acc(200002), "4000.00"); deposit(d(7, 7), acc(200002), "2000.01"); // T2: split
        deposit(d(7, 7), acc(200003), "10000.00");                                           // T3 AT the limit
        deposit(d(7, 6), acc(200004), "9000.00"); deposit(d(7, 7), acc(200004), "9000.00");  // T4 two days, each under
        deposit(d(7, 6), acc(200005), "20000.00");                                           // T5 over, but the day before

        // Smurfing (alert: more than 9 distinct payers of small transfers into one account in the look-back)
        fanIn(300001, 10, 1, "900.00", d(7, 1));                                             // F1 offender: 10 payers
        fanIn(300002, 14, 2, "450.00", d(7, 1));                                             // F2 offender: 14 payers
        fanIn(300003, 9, 1, "900.00", d(7, 1));                                              // F3 AT the threshold: 9
        for (int i = 0; i < 12; i++) transfer(d(7, 1 + i % 7), acc(330000 + i % 3), acc(300004), "400.00"); // F4 many transfers, 3 payers
        fanIn(300005, 12, 1, "1000.01", d(7, 1));                                            // F5 payers over the small-transfer cap
        fanIn(300006, 11, 1, "900.00", d(6, 28));                                            // F6 outside the look-back (06-28..06-30 only)

        // Watch-list traffic (alert: wires to XA / XB over 5000.00 in the look-back)
        wire(d(7, 2), acc(400001), "3000.00", "XA"); wire(d(7, 4), acc(400001), "3000.00", "XA");  // H1 offender: 6000
        wire(d(7, 5), acc(400002), "5000.01", "XB");                                         // H2 offender
        wire(d(7, 2), acc(400003), "2500.00", "XA"); wire(d(7, 3), acc(400003), "2500.00", "XA");  // H3 AT the threshold: 5000
        wire(d(7, 3), acc(400004), "20000.00", "XC");                                        // H4 not on the list
        row(d(7, 3), 12, 0, "TRANSFER", acc(400005), acc(990001), "9000.00", "XA");          // H5 a transfer, not a wire
        wire(d(6, 29), acc(400006), "9000.00", "XA");                                        // H6 outside the look-back

        // Risk Score: two signals each silent alone, together at or over the high threshold (60)
        for (int i = 3; i <= 6; i++) deposit(d(7, i), acc(500001), "9400.00");               // R1: 4 near-limit (40, silent)
        wire(d(7, 2), acc(500001), "2500.00", "XA"); wire(d(7, 5), acc(500001), "2500.00", "XA"); // + 2 wires = 5000 (20, silent)
        fanIn(500002, 9, 1, "800.00", d(7, 1));                                              // R2: 9 payers (36, silent)
        for (int i = 4; i <= 6; i++) deposit(d(7, i), acc(500002), "9100.00");               // + 3 near-limit (30, silent)
    }

    /** {@code payers} distinct fresh payer accounts each sending {@code each} transfers of {@code amount}, from {@code start} on. */
    private void fanIn(int target, int payers, int each, String amount, LocalDate start) {
        for (int p = 0; p < payers; p++)
            for (int k = 0; k < each; k++) {
                LocalDate day = start.plusDays((p + k) % 3);
                transfer(day, acc(310000 + (target % 1000) * 20 + p), acc(target), amount);
            }
    }

    private String parties() {
        String[] first = {"Alder", "Bram", "Cora", "Dune", "Edda", "Fenn", "Garo", "Hale", "Iven", "Jora", "Kell", "Lund"};
        String[] last = {"Ashby", "Brook", "Calder", "Dunmore", "Ellery", "Fairley", "Gresham", "Hollis", "Ingram", "Jessup"};
        StringBuilder sb = new StringBuilder("snapshot_date,party_id,full_name,national_id,country\n");
        for (int i = 0; i < 120; i++)
            sb.append(String.format(Locale.ROOT, "2026-07-07,P%06d,%s %s,NID-%07d,XD%n", i + 1, first[i % 12], last[(i / 12) % 10], 1000000 + i * 7).replace("\r", ""));
        String[][] plants = {
                {"P900001", "Kellander, Zorvath", "NID-5550001"},       // reordered name -> aml_sanctions
                {"P900002", "Zorvath Kellander", "NID-5550002"},        // exact name -> aml_sanctions
                {"P900003", "Tamsin Orrel", "NID-7700123"},             // identifier only -> aml_sanctions
                {"P900004", "Mirela Tarkan", "NID-5550004"},            // exact name -> aml_pep
                {"P900005", "Zorana Kellogg", "NID-5550005"},           // look-alike, far enough
                {"P900006", "Miles Tarrant", "NID-5550006"}};           // look-alike, far enough
        for (String[] p : plants) sb.append("2026-07-07,").append(p[0]).append(',').append('"').append(p[1]).append('"').append(',').append(p[2]).append(",XE\n");
        return sb.toString().replace("\"Kellander, Zorvath\"", "\"Kellander, Zorvath\"");
    }

    /** relative path under data/samples -> content. */
    static Map<String, String> files() {
        AmlCorpus c = new AmlCorpus();
        c.build();
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<LocalDate, StringBuilder> e : c.days.entrySet())
            out.put("aml_txn/AML_TXN_" + STAMP.format(e.getKey()) + ".csv", e.getValue().toString());
        out.put("aml_parties/AML_PARTIES_20260707.csv", c.parties());
        return out;
    }

    static List<String> sanctionsList() { return new ArrayList<>(List.of("Zorvath Kellander", "NID-7700123")); }

    static List<String> pepList() { return List.of("Mirela Tarkan"); }

    /** Regenerate the committed corpus: {@code main <template dir>/data/samples}. */
    public static void main(String[] args) throws IOException {
        Path samples = Path.of(args[0]);
        for (Map.Entry<String, String> e : files().entrySet()) {
            Path f = samples.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
    }
}
