package com.gamma.service;

/**
 * Thrown by {@link ServiceBootstrap#buildFrom} when config discovery finds no
 * {@code *_pipeline.toon} / {@code *_enrich.toon} / {@code *_job.toon} files and the caller asked
 * to be told rather than tolerate an empty config ({@code exitIfEmpty=true}). A CLI {@code main}
 * (see {@link CollectorService#main} and {@code ControlApi#main}) catches this at the top level
 * and exits the process; {@link CollectorService#fromArgs} and any other embedder-facing entry
 * point let it propagate instead of killing the host JVM (was {@code System.exit(1)} — see
 * {@code LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1} in {@code docs/BACKLOG.md}).
 */
public final class EmptyConfigException extends RuntimeException {
    public EmptyConfigException(String message) {
        super(message);
    }
}
