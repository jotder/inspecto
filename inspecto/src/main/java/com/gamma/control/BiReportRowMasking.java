package com.gamma.control;

import com.gamma.job.ReportRowMasking;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.spi.auth.ApiException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** {@link ReportRowMasking} over {@link BiResultMasking}, always as a caller who cannot reveal (a Report Job has no viewer). */
public final class BiReportRowMasking implements ReportRowMasking {

    @Override
    public Masked mask(Path writeRoot, String datasetId, Map<String, Object> dataset, MeasureCompiler.Spec spec,
                                     QueryExecutor.Result rows) {
        BiResultMasking masking = BiResultMasking.of(writeRoot, new ComponentStore(writeRoot.resolve("registry")),
                new ViewStore(writeRoot.resolve("views")), datasetId, dataset, false);
        try {
            if (spec != null) masking.vet(spec, false, null);   // a Job's own measures / order: no key may come back raw
        } catch (ApiException refused) {
            throw new IllegalArgumentException(refused.getMessage());
        }
        List<String> dimensions = spec != null ? spec.groupBy()
                : rows.columns().stream().map(ResultSetDescriptor.Column::name).toList();
        QueryExecutor.Result masked = masking.apply(rows, dimensions, null);
        Map<String, Object> described = masking.describe();
        @SuppressWarnings("unchecked")
        List<String> aliased = described == null ? List.of() : (List<String>) described.get("columns");
        return new Masked(masked, aliased);
    }
}
