package com.gamma.control;

import java.util.Set;

/**
 * Contribution point for config kinds that sit under maker-checker (the Pending Change hold). A core
 * kind is named in the processor's {@code ApprovalPolicy}; an <b>optional</b> module names its own kind
 * here, registered in {@code META-INF/services/com.gamma.control.GovernableKindProvider}, so putting a
 * module's kind under governance never means editing the processor.
 *
 * <p>Fail-closed by absence: a kind whose module is not installed is not governable, so an approval
 * policy file naming it is refused (422) rather than silently accepted.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface GovernableKindProvider {
    /** Kind ids this module puts under maker-checker. */
    Set<String> kinds();
}
