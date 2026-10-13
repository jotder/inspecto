package com.gamma.job;

import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * BI-QUERY-MASKING-1: the seam a scheduled Dataset export ({@link ReportJob}, scope {@code dataset}) masks its rows through.
 * A Report Job has no viewer, so the file it writes leaves the platform for recipients nobody can capability-check: the
 * rows are ALWAYS written as a caller without {@code canRevealLinkEntities} would see them (classified entity keys aliased).
 * The classification lives in the entity-store module, which the engine cannot see, so the control-plane module provides
 * this ({@link ServiceLoader}); with no provider on the classpath there is no classification surface and rows pass through.
 */
public interface ReportRowMasking {

    /**
     * {@code rows} as a non-revealing caller would see them.
     *
     * @param spec the structured spec the export compiled from, or {@code null} for a raw {@code SELECT *} export
     * @throws IllegalArgumentException when the spec would return a raw key through {@code min/max/sum/avg} (fails the Run)
     */
    Masked mask(Path writeRoot, String datasetId, Map<String, Object> dataset, MeasureCompiler.Spec spec,
                QueryExecutor.Result rows);

    /** The masked rows plus the output columns whose values were replaced by an alias (empty when nothing was aliased). */
    record Masked(QueryExecutor.Result result, List<String> aliasedColumns) { }

    static Optional<ReportRowMasking> provider() {
        return ServiceLoader.load(ReportRowMasking.class).findFirst();
    }
}
