package com.gamma.job;

/**
 * The declared type of a Job {@link ParameterDecl} (job-framework §7.1). Drives resolution
 * ({@link ParameterResolver}, P3a) and UI form widgets.
 */
public enum ParamType {
    /** Free text. {@code TEXT} is the multiline variant — it retires the renderer's {@code name == "sql"}
     *  guess (job-parameter-contract §7.1). */
    STRING, TEXT,
    INTEGER, DECIMAL, BOOLEAN, DATE, INSTANT,
    /** An email address — the first real consumer is the {@code mail.send} reference Job (§9). */
    EMAIL,
    DATASET_REF,
    /** Structured, author-supplied JSON — the vocabulary's only nested shape. First consumer: a
     *  {@code consignment.process} chain's per-step {@code chain_config} (open-dag design §9). */
    JSON
}
