package com.gamma.service;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RemoteFile;
import com.gamma.etl.PipelineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A ServiceLoader-registered <b>test-only</b> collector for the {@code faketest} scheme, used by
 * {@link AcquisitionDriverTest} to drive the B3b acquisition driver ({@link PipelineScheduler#dispatchAcquireCycle()})
 * without a real network. It discovers files from an in-test "remote" directory and {@code fetchTo} copies their
 * bytes, so {@link com.gamma.inspector.RemoteAcquisitionHandler} stages and lands them exactly as it would a
 * real remote fetch. Registered via
 * {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} on the inspecto test classpath.
 *
 * <p>The "remote" root, an optional fetch gate, and a fetch counter are process statics — the test sets them
 * before it drives a cycle. Single-test-class scope keeps that acceptable.
 */
public final class FakeRemoteConnectorFactory implements CollectorConnectorFactory {

    /** The directory the fake connector treats as the remote source. */
    static final AtomicReference<Path> REMOTE_ROOT = new AtomicReference<>();
    /** When non-null, every {@code fetchTo} blocks on this latch — lets a test hold a fetch in flight. */
    static final AtomicReference<CountDownLatch> GATE = new AtomicReference<>();
    /** Total {@code fetchTo} calls, so a test can assert a second overlapping acquisition did NOT fetch. */
    static final AtomicInteger FETCHES = new AtomicInteger();

    static void reset(Path remoteRoot) {
        REMOTE_ROOT.set(remoteRoot);
        GATE.set(null);
        FETCHES.set(0);
    }

    @Override public String scheme() { return "faketest"; }

    @Override public CollectorConnector create(PipelineConfig cfg) { return new FakeRemoteConnector(); }

    /** Discovers regular files under {@link #REMOTE_ROOT} and fetches by copying their bytes. */
    private static final class FakeRemoteConnector implements CollectorConnector {

        @Override public String scheme() { return "faketest"; }

        @Override public EnumSet<Capability> capabilities() {
            return EnumSet.of(Capability.STREAM, Capability.RESUMABLE);
        }

        @Override public List<RemoteFile> discover(DiscoveryContext ctx) throws AcquisitionException {
            Path root = REMOTE_ROOT.get();
            List<RemoteFile> out = new ArrayList<>();
            if (root == null || !Files.exists(root)) return out;
            try (var s = Files.walk(root)) {
                s.filter(Files::isRegularFile).forEach(p -> {
                    long size;
                    try { size = Files.size(p); } catch (IOException e) { size = RemoteFile.SIZE_UNKNOWN; }
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.add(new RemoteFile(p.getFileName().toString(), rel, size, null, null, null, null));
                });
            } catch (IOException e) {
                throw new AcquisitionException("fake discover failed", e);
            }
            return out;
        }

        @Override public Readiness readiness(RemoteFile file) { return Readiness.READY; }

        @Override public InputStream open(RemoteFile file) throws AcquisitionException {
            try {
                return Files.newInputStream(REMOTE_ROOT.get().resolve(file.relativePath()));
            } catch (IOException e) {
                throw new AcquisitionException("fake open failed for " + file.relativePath(), e);
            }
        }

        @Override public Path fetchTo(RemoteFile file, Path dest) throws AcquisitionException {
            FETCHES.incrementAndGet();
            CountDownLatch gate = GATE.get();
            if (gate != null) {
                try { gate.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            try {
                if (dest.getParent() != null) Files.createDirectories(dest.getParent());
                Files.copy(REMOTE_ROOT.get().resolve(file.relativePath()), dest,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new AcquisitionException("fake fetch failed for " + file.relativePath(), e);
            }
            return dest;
        }

        @Override public void post(RemoteFile file, PostAction action) { /* RETAIN — leave the remote copy */ }
    }
}
