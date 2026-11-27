package com.gamma.signal;

import com.gamma.signal.SignalField.Kind;

import java.util.List;

/**
 * The Signal Types only tests post (SIGNAL-BOARD-1 SB-1a): declared here, on the test classpath, so the post-time check
 * ({@code -Dsignals.checkFields=true}) knows them as it knows every real type. Never shipped.
 */
public final class TestSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "test"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(
                new SignalTypeDecl("probe.burst", "Test: a burst of Signals for on_signal Jobs", "test",
                        List.of(SignalField.always("i", Kind.INTEGER, "The posting's index in the burst")), "4.0.0"),
                new SignalTypeDecl("loop.ping", "Test: a Signal a Job posts to itself", "test",
                        List.of(SignalField.optional("k", Kind.STRING, "Any value")), "4.0.0"));
    }
}
