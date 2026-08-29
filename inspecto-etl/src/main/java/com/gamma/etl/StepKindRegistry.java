package com.gamma.etl;

import java.util.ServiceLoader;

/**
 * Which {@code steps:} kinds a config may name, beyond the built-in
 * {@link PipelineConfig.Step#KINDS}.
 *
 * <p><b>Why this is an SPI and not a list.</b> A contributed step kind is a plugin node type's suffix —
 * {@code acme_redact} names {@code transform.acme_redact} — and the node-type registry lives in
 * {@code inspecto-engine}, which sits <b>above</b> this module. A closed list here would make a plugin
 * step unreadable, and the file must stay readable or the editor could write a graph it can never load
 * back. Inverting through a {@code ServiceLoader} keeps the parser <b>fail-closed at LOAD</b> — a typo
 * is still refused where the author can see it — without this module learning what a node type is.
 *
 * <p><b>With no provider the answer is "no".</b> The lean core knows only its own kinds, so an
 * unrecognised one is refused exactly as it was before this seam existed.
 */
@FunctionalInterface
public interface StepKindRegistry {

    /** Whether {@code kind} names a step this deployment can actually run. */
    boolean isKnown(String kind);

    /** The registry in force: the first provider found, or the built-ins-only default. */
    static StepKindRegistry current() {
        return Holder.INSTANCE;
    }

    /** Loaded once at class-load, like every other ServiceLoader seam here. */
    final class Holder {
        private static final StepKindRegistry INSTANCE = load();

        private Holder() {}

        private static StepKindRegistry load() {
            for (StepKindRegistry r : ServiceLoader.load(StepKindRegistry.class)) return r;
            return kind -> false;
        }
    }
}
