package com.gamma.acquire;

import com.gamma.api.PublicApi;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The OUTBOUND mirror of {@link CollectorConnector} (EXPORT-1): push local files to a remote object store
 * after a successful run. A {@link CollectorConnector} fetches <em>in</em>; this delivers <em>out</em>.
 *
 * <p>Keys are relative to the bound Connection's own root ({@code base_path} = {@code bucket[/prefix]} for
 * {@code s3}), forward-slash separated, never leading {@code /}. Every method throws
 * {@link AcquisitionException} on any non-success answer — a caller that swallows one would report a
 * delivery that did not happen.
 *
 * <p>Built by an {@link ExportConnectorFactory}; the lean core ships none.
 */
@PublicApi(since = "4.0.0")
public interface ExportConnector {

    /**
     * The remote object at {@code key}, or empty when none exists. Only {@link RemoteFile#size()} and
     * {@link RemoteFile#etag()} (unquoted) are meaningful — what idempotent re-export compares against.
     */
    Optional<RemoteFile> stat(String key) throws AcquisitionException;

    /**
     * Upload {@code file} to {@code key}, replacing any object there. {@code md5Hex}/{@code sha256Hex} are the
     * file's digests, computed once by the caller (the transport signs and integrity-checks with them).
     */
    void put(String key, Path file, String md5Hex, String sha256Hex) throws AcquisitionException;

    /** Upload a small in-memory body (the export manifest) to {@code key}. */
    void put(String key, byte[] body, String contentType) throws AcquisitionException;
}
