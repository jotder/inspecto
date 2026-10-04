package com.gamma.query;

import com.gamma.api.PublicApi;
import com.gamma.pipeline.ViewStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Platform Service {@code datasets} (platform-services S3-3): READ-ONLY access to the running Space's
 * Datasets. Granted to a Run via {@code requires: [datasets]} and looked up as
 * {@code ctx.services().get(DatasetAccess.class)}; a consumer that did not declare it never sees it.
 *
 * <h3>Inherited from {@code ConsignmentSelector} (D-10, option 1)</h3>
 * The relation and the file list come through {@link DatasetRelation}, which asks the selector: files the
 * Consignment catalog marks unreadable are pruned, and the list is PINNED at this call, so a file landing
 * afterwards never reaches the read. There is no held snapshot handle in this slice — each {@link #read}
 * pins afresh.
 *
 * <h3>Scope (fail closed)</h3>
 * Per-Space: the service is bound to ONE Space; naming another Space, or a Dataset that Space does not
 * hold, throws naming the id — never an empty result. The Job's authority is the {@code requires:} grant
 * itself (a Job has no Subject at fire time), exactly as for every other Platform Service.
 *
 * <h3>Dry-run contract</h3>
 * Read-only — unaffected by a dry run; the real service is handed through unchanged ({@code readOnly()}).
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public interface DatasetAccess {

    /**
     * What a read resolved to.
     *
     * @param relationSql the Dataset's relation (calculated columns included) over the pinned file list
     * @param files       the pinned input files relative to the data root, or empty when the relation has
     *                    no enumerable files (a view-backed Dataset): 'cannot know' is not 'no files'
     */
    record Read(String datasetId, String relationSql, Optional<List<String>> files) {}

    /**
     * @param spaceId   the Space the caller means; must be the Space this service is bound to
     * @param datasetId a Dataset registered in that Space
     * @throws IllegalStateException when the Space is another one, the Dataset is unknown, or its config is unusable
     */
    Read read(String spaceId, String datasetId);

    /** The production implementation over one Space's Dataset components; suppliers are read per call. */
    static DatasetAccess over(String ownSpaceId, Function<String, Optional<Map<String, Object>>> datasets,
                              Supplier<Path> dataRoot, Supplier<ViewStore> views) {
        return (spaceId, datasetId) -> {
            if (spaceId == null || !spaceId.equals(ownSpaceId))
                throw new IllegalStateException("Dataset read refused: Space '" + spaceId
                        + "' is not the Space this service is bound to ('" + ownSpaceId + "'), dataset '" + datasetId + "'");
            Map<String, Object> cfg = datasetId == null ? null : datasets.apply(datasetId).orElse(null);
            if (cfg == null)
                throw new IllegalStateException("Dataset read refused: no dataset '" + datasetId
                        + "' in Space '" + ownSpaceId + "'");
            String sql;
            Optional<List<String>> files;
            try {
                sql = DatasetRelation.relationSql(cfg, dataRoot.get(), views.get());
                files = DatasetRelation.inputFiles(cfg, dataRoot.get(), Integer.MAX_VALUE - 1)
                        .map(f -> f.files().stream().map(DatasetRelation.FileStamp::path).toList());
            } catch (IllegalArgumentException bad) {
                throw new IllegalStateException("Dataset read refused: dataset '" + datasetId + "' is unusable: "
                        + bad.getMessage(), bad);
            }
            return new Read(datasetId, sql, files);
        };
    }
}
