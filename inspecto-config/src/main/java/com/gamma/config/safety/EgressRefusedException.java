package com.gamma.config.safety;

/** An outbound dial the Safety Policy refuses (policy-narrowing-design S4). Unchecked: a refusal is a policy outcome, not an I/O fault. */
public final class EgressRefusedException extends RuntimeException {
    public EgressRefusedException(String message) {
        super(message);
    }
}
