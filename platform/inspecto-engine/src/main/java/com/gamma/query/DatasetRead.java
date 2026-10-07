package com.gamma.query;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Dataset-read port (SEP-01, {@code docs/archived-documents/plans-archive/la-separation-feasibility-plan.md} §4): the one seam through
 * which an optional module (geo-link / Link Analysis) reads Dataset definitions and builds their relation SQL, so it
 * never constructs {@link ComponentStore} / {@link ViewStore} for Datasets itself. Behaviour-identical to the direct
 * calls it replaced: registry under {@code <writeRoot>/registry}, views under {@code <writeRoot>/views}.
 */
public final class DatasetRead {
    private DatasetRead() {}

    /** The Dataset's definition content, or empty when it is not in the registry. */
    public static Optional<Map<String, Object>> dataset(Path writeRoot, String datasetId) {
        return registry(writeRoot).get("dataset", datasetId).map(ComponentRegistry.Component::content);
    }

    /** Every Dataset in the registry. */
    public static List<ComponentRegistry.Component> datasets(Path writeRoot) {
        return registry(writeRoot).list("dataset");
    }

    /** The relation SQL for a Dataset definition; {@link IllegalArgumentException} when it cannot be bound. */
    public static String relationSql(Map<String, Object> dataset, Path dataRoot, Path writeRoot) {
        return DatasetRelation.relationSql(dataset, dataRoot, new ViewStore(writeRoot.resolve("views")));
    }

    /** The input files of a Dataset's relation (see {@link DatasetRelation#inputFiles}); empty when not enumerable. */
    public static Optional<DatasetRelation.InputFiles> inputFiles(Map<String, Object> dataset, Path dataRoot, int limit) {
        return DatasetRelation.inputFiles(dataset, dataRoot, limit);
    }

    /** {@link #inputFiles(Map, Path, int)} abandoned (ConsignmentSelector.WalkTimeoutException) when {@code expired} turns true. */
    public static Optional<DatasetRelation.InputFiles> inputFiles(Map<String, Object> dataset, Path dataRoot, int limit,
                                                                  java.util.function.BooleanSupplier expired) {
        return DatasetRelation.inputFiles(dataset, dataRoot, limit, expired);
    }

    /** The relation over only {@code relativePaths} (see {@link DatasetRelation#relationSqlOverFiles}); null when it cannot be appended. */
    public static String relationSqlOverFiles(Map<String, Object> dataset, Path dataRoot, List<String> relativePaths) {
        return DatasetRelation.relationSqlOverFiles(dataset, dataRoot, relativePaths);
    }

    /** The component registry rooted at the Space write root (for non-Dataset component types a module owns). */
    public static ComponentStore registry(Path writeRoot) {
        return new ComponentStore(writeRoot.resolve("registry"));
    }
}
