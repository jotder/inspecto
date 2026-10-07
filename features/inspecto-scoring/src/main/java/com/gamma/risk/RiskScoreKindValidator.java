package com.gamma.risk;

import com.gamma.spi.http.ComponentKindValidator;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** The {@code risk-score} component kind's save-time checks, contributed to the processor's component save gate. */
public final class RiskScoreKindValidator implements ComponentKindValidator {
    @Override public String type() { return RiskScoreRoutes.TYPE; }

    @Override public void validate(String id, Map<String, Object> content) {
        // ASSURE-RISK-SCORE-1: structure, numeric weights, and every indicator compiled by MeasureCompiler.
        RiskScoreModel.fromMap(id, content);
    }

    @Override public void validateInSpace(Path writeRoot, Supplier<Path> dataRoot, String id, Map<String, Object> content) {
        RiskScoreRoutes.requireStorable(writeRoot, dataRoot, RiskScoreModel.fromMap(id, content));
    }

    @Override public void requireNotReserved(Path writeRoot, String type, String id, Map<String, Object> content) {
        RiskScoreRoutes.requireNotReserved(writeRoot, type, id, content);
    }
}
