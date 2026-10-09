package com.gamma.control;

import com.gamma.spi.auth.AccessDecider;
import com.gamma.spi.auth.AccessDeciders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code /bootstrap}'s edition: Personal without auth, Enterprise when a policy decider is installed. */
class BootstrapEditionTest {

    private static final AccessDecider ABSTAINS = (ex, subject, action, route, kind, attrs) -> AccessDecider.Decision.ABSTAIN;

    @AfterEach
    void restore() {
        AccessDeciders.forTest(null);
        System.clearProperty("auth.mode");
    }

    @Test
    void authFreeCoreIsPersonalEvenWithADecider() {
        AccessDeciders.forTest(ABSTAINS);
        assertEquals("personal", BootstrapRoutes.edition());
    }

    @Test
    void authenticatedWithoutADeciderIsProfessional() {
        System.setProperty("auth.mode", "oidc");
        assertEquals("professional", BootstrapRoutes.edition());
    }

    @Test
    void authenticatedWithAPolicyDeciderIsEnterprise() {
        System.setProperty("auth.mode", "demo");
        AccessDeciders.forTest(ABSTAINS);
        assertEquals("enterprise", BootstrapRoutes.edition());
    }
}
