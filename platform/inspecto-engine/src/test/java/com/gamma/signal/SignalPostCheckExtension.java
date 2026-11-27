package com.gamma.signal;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;

/**
 * Fails the running test when a Signal it caused did not fit the Signal Type catalog (SIGNAL-BOARD-1 SB-1a). The
 * post-time check throws where the Signal is posted, but a Job Run posts on a worker thread whose Future nobody reads,
 * so the throw alone can vanish; {@link SignalPostCheck} also records each misfit, and this reads the record. Registered
 * through {@code META-INF/services/org.junit.jupiter.api.extension.Extension} (Surefire enables JUnit's auto-detection),
 * and shipped in the engine test-jar so the processor's tests get it too. A no-op where the check is off.
 */
public final class SignalPostCheckExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        SignalPostCheck.drainMisfits();   // a misfit left by an earlier test's late worker is that test's, not this one's
    }

    @Override
    public void afterEach(ExtensionContext context) {
        List<String> misfits = SignalPostCheck.drainMisfits();
        if (!misfits.isEmpty())
            throw new AssertionError(misfits.size() + " Signal(s) posted during this test do not fit their declaration ("
                    + "-D" + SignalPostCheck.PROPERTY + "=true): " + String.join(" | ", misfits));
    }
}
