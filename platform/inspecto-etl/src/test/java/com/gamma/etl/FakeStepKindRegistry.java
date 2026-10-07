package com.gamma.etl;

/**
 * A test-scope {@link StepKindRegistry} provider standing in for the engine's own.
 *
 * <p>⚠ It vouches for exactly ONE obscure kind. A test provider is global to this module's test run, so
 * a permissive one would quietly widen what every other {@code steps:} test accepts — and the refusal
 * those tests pin is the point of them.
 */
public final class FakeStepKindRegistry implements StepKindRegistry {

    /** The one contributed kind this module's tests may name. */
    public static final String KIND = "acme_probe";

    @Override
    public boolean isKnown(String kind) {
        return KIND.equals(kind);
    }
}
