package com.gamma.demoauth;

import com.gamma.control.TokenRelay;
import com.gamma.control.testkit.TokenRelayContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** {@link DemoTokenRelay} against the platform's TokenRelay TCK (MODULE-REORG-1 P5b); loopback bind is its load-time gate. */
class DemoTokenRelayTckTest extends TokenRelayContract {
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
    protected TokenRelay relay() {
        return new DemoTokenRelay();
    }
}
