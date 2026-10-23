package com.gamma.control;

import com.gamma.spi.auth.AccessDecider;
import com.gamma.spi.auth.AccessDeciders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code /bootstrap}'s edition: Basic without auth, Advanced when a policy decider is installed. */
class BootstrapEditionTest {

    private static final AccessDecider ABSTAINS = (ex, subject, action, route, kind, attrs) -> AccessDecider.Decision.ABSTAIN;

    @AfterEach
    void restore() {
        AccessDeciders.forTest(null);
        com.gamma.spi.auth.Authenticators.forTest(null);
        System.clearProperty("auth.mode");
    }

    @Test
    void authFreeCoreIsPersonalEvenWithADecider() {
        AccessDeciders.forTest(ABSTAINS);
        assertEquals("basic", BootstrapRoutes.edition());
    }

    /** One definition of "people sign in" (LA-ONBOARD-RESIDUALS-1 item 2): an installed Authenticator counts, as it does for the masking default. */
    @Test
    void anInstalledAuthenticatorMeansSignInEvenWithoutAuthMode() {
        com.gamma.spi.auth.Authenticators.forTest(ex -> java.util.Optional.empty());
        assertEquals("standard", BootstrapRoutes.edition());
        assertEquals(EditionMaskingDefault.signsIn(), !"basic".equals(BootstrapRoutes.edition()));
    }

    @Test
    void authenticatedWithoutADeciderIsProfessional() {
        System.setProperty("auth.mode", "oidc");
        assertEquals("standard", BootstrapRoutes.edition());
    }

    @Test
    void authenticatedWithAPolicyDeciderIsEnterprise() {
        System.setProperty("auth.mode", "demo");
        AccessDeciders.forTest(ABSTAINS);
        assertEquals("advanced", BootstrapRoutes.edition());
    }
}
