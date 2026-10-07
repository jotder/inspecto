package com.gamma.demoauth;

import com.gamma.spi.auth.Authenticator;
import com.gamma.control.testkit.AuthenticatorContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** {@link DemoAuthenticator} against the platform's Authenticator TCK (MODULE-REORG-1 P5b); loopback bind is its load-time gate. */
class DemoAuthenticatorTckTest extends AuthenticatorContract {
    private static String bind;

    @BeforeAll
    static void loopback() {
        bind = System.getProperty("control.bind");
        System.setProperty("control.bind", "127.0.0.1");
    }

    @AfterAll
    static void restore() {
        if (bind == null) System.clearProperty("control.bind"); else System.setProperty("control.bind", bind);
    }

    @Override
    protected Authenticator authenticator() {
        return new DemoAuthenticator();
    }
}
