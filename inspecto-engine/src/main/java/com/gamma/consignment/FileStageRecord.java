package com.gamma.consignment;

import com.gamma.api.PublicApi;

/**
 * One recorded {@link FileStage} transition for a file — the row {@link DbFileStageStore} persists.
 * {@code sourceId}/{@code relativePath} is the same key {@code AcquisitionLedger} uses; {@code batchId}
 * ties the transition to the batch that produced it (nullable for a transition recorded before the
 * batch id was known, though every current call site has one).
 */
@PublicApi(since = "5.1.0")
public record FileStageRecord(String sourceId, String relativePath, String batchId,
                              FileStage stage, String recordedAt) {
}
