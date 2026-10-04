package com.gamma.control;

import com.gamma.util.CurrentSpace;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SafetyPolicyExplain;
import com.gamma.config.safety.SafetyPolicyUnreadableException;

/**
 * {@code GET /settings/safety-policy} - the Space's effective Safety Policy, every field carrying which tier
 * constrained it ({@code policy-narrowing-design.md} S7). Read-only and administration-gated: the answer
 * names filesystem roots and host patterns. No write-root gate (nothing is written); no caller-supplied path
 * (the Space is the request's {@code /spaces/{id}} binding), so no jail.
 *
 * <p>An <b>unreadable</b> policy file is not a 422 here: the diagnostic's whole job is to say which file is
 * broken, so it answers 200 with {@code readable:false} and the reason, and no effective policy - never a
 * guess at one. Every plan-time gate and run still refuses ({@code ERR_SAFETY_POLICY_UNREADABLE}).
 */
final class SafetyPolicyRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/settings/safety-policy", ApiContext.withCapability("canAdminister", (e, m) -> {
            String space = CurrentSpace.id();
            try {
                return SafetyPolicyExplain.explain(space, SafetyPolicy.tiersForSpace(space));
            } catch (SafetyPolicyUnreadableException unreadable) {
                return SafetyPolicyExplain.unreadable(space, unreadable);
            }
        }));
    }
}
