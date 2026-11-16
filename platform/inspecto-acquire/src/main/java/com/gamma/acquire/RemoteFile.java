package com.gamma.acquire;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.time.Instant;

/**
 * A file a {@link CollectorConnector} has discovered — protocol-agnostic.
 *
 * <p>{@code relativePath} is the path relative to the source root (forward-slash, e.g.
 * {@code 20200403/feed.csv.gz}); it is what the engine mirrors when computing marker/staging/archive
 * locations, so it is identical across protocols. {@code localPath} is non-null only when the bytes are
 * already on the local filesystem — i.e. for the {@link LocalFileSystemConnector} (the file in place) or
 * after a {@link CollectorConnector#fetchTo}. For a remote, not-yet-fetched file it is {@code null}.
 *
 * <p>{@code size}/{@code lastModified}/{@code etag}/{@code version} are listing metadata. They are populated
 * only when the connector gets them <em>for free</em> from its listing (S3 LIST returns size/etag; an SFTP
 * listing returns attrs). The local connector leaves {@code size = -1} and {@code lastModified = null} at
 * discovery on purpose — a directory walk would have to {@code stat()} each file separately, and Phase A
 * needs none of it (batch planning stats on demand). Later phases (stability/watermark/checksum) fill these
 * when they actually need them, keeping discovery I/O at the legacy minimum.
 *
 * <p><b>{@code size} is the listing-with-sizes seam</b> (MoSCoW C8). When every file a remote listing offers
 * carries a size, the engine applies the per-cycle byte cap ({@code -Dingest.maxBytesPerCycle}) <em>before</em>
 * fetching, so the deferred files cost no bandwidth. A connector fills it only when its listing returns it;
 * it never guesses. {@link #SIZE_UNKNOWN} on any file keeps the cap after the fetch, on the landed files.
 */
@PublicApi(since = "4.0.0")
public record RemoteFile(String name,
                         String relativePath,
                         long size,
                         Instant lastModified,
                         String etag,
                         String version,
                         Path localPath) {

    /** Sentinel for "size not fetched at discovery" (see class note). */
    public static final long SIZE_UNKNOWN = -1L;

    /** Whether the bytes are already on the local filesystem (in place, or staged/fetched). */
    public boolean isLocal() {
        return localPath != null;
    }

    /** Whether listing metadata carried a size. */
    public boolean hasSize() {
        return size >= 0;
    }

    /** A copy pointing at a now-local copy of the bytes (after {@link CollectorConnector#fetchTo}), preserving the
     *  protocol-agnostic identity (name / relativePath / listing metadata). */
    public RemoteFile withLocalPath(Path local) {
        return new RemoteFile(name, relativePath, size, lastModified, etag, version, local);
    }
}
