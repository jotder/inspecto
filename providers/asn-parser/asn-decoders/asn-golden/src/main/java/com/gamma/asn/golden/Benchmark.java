package com.gamma.asn.golden;

import com.gamma.asn.core.ByteSource;
import com.gamma.asn.core.Framing;
import com.gamma.asn.core.RecordReader;
import com.gamma.asn.core.RecoveryPolicy;
import com.gamma.asn.core.Strictness;
import com.gamma.asn.schema.Asn1Parser;
import com.gamma.asn.schema.CompiledSchema;
import com.gamma.asn.schema.DecoderRegistry;
import com.gamma.asn.schema.SchemaBinder;
import com.gamma.asn.schema.SchemaCompiler;
import com.gamma.skybase.decoder.asn2.ASN1Reader;
import com.gamma.skybase.decoder.asn2.ASNConf;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5 performance pass (REDESIGN.md §6): decode every corpus data file with the new
 * stack (mmap + framing + BER + schema bind) and with the legacy asn2 reader, and report
 * records/sec. Full decode both sides, no transform. Two passes each, second measured
 * (JIT warm). Numbers land in asn-decoders/README.md — rerun after codec changes.
 *
 * <p>Usage: {@code Benchmark <base-dir>} (the asn-parser root).
 */
public final class Benchmark {

    private static final int WARMUP = 2;
    private static final int MEASURED = 3;

    /** Keeps the faster of two (records, ns) samples. */
    private static long[] best(long[] a, long[] b) {
        return b[1] < a[1] ? b : a;
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        PrintStream realOut = System.out;
        PrintStream devNull = new PrintStream(java.io.OutputStream.nullOutputStream());
        System.setOut(devNull); // legacy decode printStackTraces on ordinary records
        System.setErr(devNull);
        try {
            run(base, realOut);
        } finally {
            System.setOut(realOut);
        }
    }

    private static void run(Path base, PrintStream out) throws Exception {
        out.printf("%-14s %-40s %7s %12s %12s %12s %7s%n",
                "case", "file", "MB", "legacy r/s", "tlv r/s", "new r/s", "ratio");
        long totLegacyNs = 0, totNewNs = 0, totRecords = 0;
        for (GoldenCapture.CaseSpec spec : GoldenCapture.CASES) {
            Path dataDir = base.resolve(spec.dataDir());
            if (!Files.isDirectory(dataDir)) {
                continue;
            }
            List<Path> files;
            try (var stream = Files.list(dataDir)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return !n.endsWith(".gz") && !n.endsWith(".rar") && !n.endsWith(".zip");
                        })
                        .sorted()
                        .toList();
            }

            List<String> warnings = new ArrayList<>();
            CompiledSchema schema = SchemaCompiler.compileLenient(
                    Asn1Parser.parseLenient(Files.readString(base.resolve(spec.asn()),
                            StandardCharsets.ISO_8859_1), warnings), spec.root(), warnings);
            DecoderRegistry registry = DecoderRegistry.withDefaults();
            Framing framing = Framing.of(new Framing.FramingSpec(
                    spec.headerLength() == null ? 0 : spec.headerLength(), 0, Set.of(0x00, 0xFF),
                    spec.recordHeaderLength() == null ? null
                            : Framing.RecordHeaderSpec.skipOnly(Math.toIntExact(spec.recordHeaderLength()))));
            ASNConf conf = new ASNConf(base.resolve(spec.asn()).toString(), spec.root(), spec.skipLines());

            for (Path file : files) {
                // This box is a shared sandbox: a single measured pass swings ±30%, enough
                // to invent or hide a regression. Warm up, then take the BEST of N — decode
                // is deterministic, so the minimum is the estimate least polluted by
                // unrelated load, and it is stable run to run.
                long[] legacy = {0, Long.MAX_VALUE};
                long[] tlv = {0, Long.MAX_VALUE};
                long[] fresh = {0, Long.MAX_VALUE};
                for (int pass = 0; pass < WARMUP + MEASURED; pass++) {
                    long[] l = timeLegacy(conf, spec, file);
                    long[] t = timeTlvOnly(framing, file);
                    long[] n = timeNew(schema, registry, framing, file);
                    if (pass >= WARMUP) {
                        legacy = best(legacy, l);
                        tlv = best(tlv, t);
                        fresh = best(fresh, n);
                    }
                }
                double mb = Files.size(file) / 1e6;
                double lRate = legacy[0] * 1e9 / Math.max(1, legacy[1]);
                double tRate = tlv[0] * 1e9 / Math.max(1, tlv[1]);
                double nRate = fresh[0] * 1e9 / Math.max(1, fresh[1]);
                out.printf("%-14s %-40s %7.1f %,12.0f %,12.0f %,12.0f %6.1fx%n",
                        spec.name(), file.getFileName(), mb, lRate, tRate, nRate,
                        nRate / Math.max(1, lRate));
                totLegacyNs += legacy[1];
                totNewNs += fresh[1];
                totRecords += fresh[0];
            }
        }
        out.printf("%ntotal: %,d records; legacy %.2fs, new %.2fs (%.1fx)%n",
                totRecords, totLegacyNs / 1e9, totNewNs / 1e9, (double) totLegacyNs / totNewNs);
    }

    /** records decoded + elapsed ns for the legacy asn2 reader. */
    private static long[] timeLegacy(ASNConf conf, GoldenCapture.CaseSpec spec, Path file) {
        long records = 0;
        long start = System.nanoTime();
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> fileStruct = new LinkedHashMap<>();
            if (spec.headerLength() != null) {
                fileStruct.put("HEADER_LENGTH", spec.headerLength());
            }
            if (spec.recordHeaderLength() != null) {
                fileStruct.put("RECORD_HEADER_LENGTH", spec.recordHeaderLength());
            }
            ASN1Reader reader = new ASN1Reader(in, conf, fileStruct);
            while (reader.hasNext()) {
                LinkedHashMap<String, Object> rec = reader.next();
                if (rec != null && !rec.isEmpty()) {
                    records++;
                }
            }
        } catch (Exception e) {
            // legacy stops mid-file on some inputs; time what it managed, like production did
        }
        return new long[]{records, System.nanoTime() - start};
    }

    /** records + ns for framing + BER only, no schema bind — isolates codec from binder. */
    private static long[] timeTlvOnly(Framing framing, Path file) throws Exception {
        long records = 0;
        long start = System.nanoTime();
        try (ByteSource src = ByteSource.map(file)) {
            RecordReader reader = new RecordReader(src, framing, Strictness.BER,
                    RecoveryPolicy.STOP_FILE, e -> {
            });
            while (reader.hasNext()) {
                if (reader.next() != null) {
                    records++;
                }
            }
        }
        return new long[]{records, System.nanoTime() - start};
    }

    /** records decoded + elapsed ns for the new stack, full bind to named tree. */
    private static long[] timeNew(CompiledSchema schema, DecoderRegistry registry,
                                  Framing framing, Path file) throws Exception {
        long records = 0;
        long start = System.nanoTime();
        try (ByteSource src = ByteSource.map(file)) {
            RecordReader reader = new RecordReader(src, framing, Strictness.BER,
                    RecoveryPolicy.STOP_FILE, e -> {
            });
            SchemaBinder binder = new SchemaBinder(schema, src, registry);
            while (reader.hasNext()) {
                if (!binder.bind(reader.next()).children().isEmpty()) {
                    records++;
                }
            }
        }
        return new long[]{records, System.nanoTime() - start};
    }
}
