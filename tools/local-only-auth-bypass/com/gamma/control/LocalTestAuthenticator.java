package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.util.Optional;

/**
 * Local-only permit-all Authenticator for feature-testing the Professional/Enterprise route modules
 * (geo-link, exchange, metrics, events, ops, notify-channels, backup, ABAC policy engine) WITHOUT a
 * real OIDC provider. Grants every declared capability to a fixed "local-tester" Subject on every
 * request, regardless of credentials.
 *
 * NEVER add this to any pom.xml or module source tree, and NEVER let package.ps1 bundle it — it is
 * the opposite of what the Authenticator seam (docs/EDITIONS.md "Security direction") exists to
 * enforce. It is compiled ad hoc and dropped onto a classpath by hand; see
 * docs/okf/backend/editions/local-testing-without-iam.md for the full recipe.
 */
public final class LocalTestAuthenticator implements Authenticator {

    @Override
    public Optional<Subject> authenticate(HttpExchange ex) {
        return Optional.of(new Subject("local-tester", CapabilityManifest.capabilities()));
    }
}
