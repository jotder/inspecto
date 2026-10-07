package com.gamma.config.safety;

/** A progress-state advance or rewind the Safety Policy refuses (policy-narrowing-design S6). Unchecked: a policy outcome, not an I/O fault. */
public final class StateRefusedException extends RuntimeException {
    public StateRefusedException(String message) {
        super(message);
    }
}
