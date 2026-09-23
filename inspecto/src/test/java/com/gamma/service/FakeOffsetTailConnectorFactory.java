package com.gamma.service;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.etl.PipelineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A ServiceLoader-registered <b>test-only</b> collector ({@code faketail} scheme) that reproduces the exact
 * frontier contract {@code KafkaConnector} and {@code DbExportConnector} share: {@code discover} resumes from the
 * ledger's {@code dbWatermark} and names the slice {@code tail-p0-<from>-<to>.csv}; {@code fetchTo} writes rows
 * {@code [from,to)} and stashes the reached offset via {@link AcquisitionLedgers#stashDbWatermark} keyed by the
 * {@code dest} path it was handed — so {@link com.gamma.inspector.RemoteAcquisitionHandler} and
 * {@code ConsignmentIngestor} run their real stage, land and commit path over it. (Neither real connector is on
 * this module's classpath; their stash-by-dest behaviour is pinned by their own tests.)
 */
public final class FakeOffsetTailConnectorFactory implements CollectorConnectorFactory {

    static final String WATERMARK_KEY = "faketail:p0";
    /** The "log end offset": rows 0..END-1 exist on the fake topic. */
    static final AtomicLong END = new AtomicLong();
    /** Every slice name {@code fetchTo} drained, in order — the overlap evidence. */
    static final List<String> FETCHED = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final Pattern SLICE = Pattern.compile("tail-p0-(\\d+)-(\\d+)\\.csv$");

    @Override public String scheme() { return "faketail"; }

    @Override public CollectorConnector create(PipelineConfig cfg) { return new Tail(); }

    private static final class Tail implements CollectorConnector {
        @Override public String scheme() { return "faketail"; }

        @Override public EnumSet<Capability> capabilities() { return EnumSet.of(Capability.STREAM); }

        @Override public List<RemoteFile> discover(DiscoveryContext ctx) {
            long from = AcquisitionLedgers.shared().dbWatermark(WATERMARK_KEY).map(Long::parseLong).orElse(0L);
            long to = END.get();
            if (from >= to) return List.of();
            String name = "tail-p0-" + from + "-" + to + ".csv";
            return List.of(new RemoteFile(name, name, RemoteFile.SIZE_UNKNOWN, null, null, null, null));
        }

        @Override public Readiness readiness(RemoteFile file) { return Readiness.READY; }

        @Override public InputStream open(RemoteFile file) throws AcquisitionException {
            throw new AcquisitionException("faketail is fetchTo-only: " + file.relativePath());
        }

        @Override public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
            Matcher m = SLICE.matcher(file.relativePath());
            if (!m.find()) throw new AcquisitionException("not a faketail slice: " + file.relativePath());
            long from = Long.parseLong(m.group(1)), to = Long.parseLong(m.group(2));
            StringBuilder sb = new StringBuilder("ID,AMT,EVENT_DATE\n");   // the CSV reader takes line 1 as header
            for (long off = from; off < to; off++) sb.append(off).append(",1,2020-01-01\n");
            try {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.writeString(dest, sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AcquisitionException("faketail fetch failed", e);
            }
            FETCHED.add(file.relativePath());
            AcquisitionLedgers.stashDbWatermark(dest, WATERMARK_KEY, Long.toString(to));   // the Kafka/DbExport idiom
            return dest;
        }

        @Override public void post(RemoteFile file, PostAction action) { /* RETAIN */ }
    }
}
