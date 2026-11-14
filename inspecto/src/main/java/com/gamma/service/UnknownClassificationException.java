package com.gamma.service;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;

import java.util.List;

/**
 * Thrown by {@link BundleImporter#writeConfig} when a file it wrote states a column classification the target Space
 * does not know ({@link ClassificationVocabulary#findingsWritten}, CLASSIFICATION-BULK-IMPORT-UNGATED-1). The writer
 * has rolled its journal back by then, so nothing of the import stays written. An {@link IllegalArgumentException} so
 * a caller that knows nothing of it still refuses; the routes catch it first and answer 422.
 */
public final class UnknownClassificationException extends IllegalArgumentException {
    private final transient List<Finding> findings;

    public UnknownClassificationException(List<Finding> findings) {
        super(FindingCodes.ERR_UNKNOWN_CLASSIFICATION + ": "
                + String.join("; ", findings.stream().map(f -> f.fieldPath() + ": " + f.message()).toList()));
        this.findings = List.copyOf(findings);
    }

    /** One ERROR per unknown class, its field path led by the file ({@link ClassificationVocabulary#findingsWritten}). */
    public List<Finding> findings() {
        return findings;
    }
}
