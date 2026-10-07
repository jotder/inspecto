package com.gamma.config.safety;

/**
 * A Safety Policy file that exists (or is named by the server file) but cannot be read as a valid policy.
 * It is deliberately <b>not</b> read as "no policy": an unreadable narrowing file read as empty is a silent
 * widening ({@code docs/archived-documents/plans-archive/policy-narrowing-design.md} §5). Whoever asks for the effective policy
 * gets this instead, so every plan-time gate and every run fails closed with {@link #CODE}.
 */
public final class SafetyPolicyUnreadableException extends RuntimeException {

    /** The stable error code a gate or a failed Run reports. */
    public static final String CODE = "ERR_SAFETY_POLICY_UNREADABLE";

    private final String file;

    public SafetyPolicyUnreadableException(String file, String reason, Throwable cause) {
        super(CODE + ": " + file + ": " + reason, cause);
        this.file = file;
    }

    /** The file that could not be read. */
    public String file() { return file; }
}
