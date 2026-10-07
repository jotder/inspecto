package com.gamma.testsupport;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Method;

/**
 * Fails a test class that leaves a subscriber attached to the process-wide {@code EventLog.global()} — a
 * CollectorService (or a ControlApi / SpaceManager holding one) that was never closed keeps its notification,
 * security-trigger and bridge subscribers live for the rest of the surefire fork, where they react to the NEXT
 * class's events. Auto-registered through {@code META-INF/services} (autodetection is switched on in the parent
 * pom's surefire config) for every module on this test-jar's classpath. Reflective so this leaf module needs no
 * dependency on inspecto-event; a module without it on the classpath is simply not checked.
 */
public final class EventLogLeakDetector implements BeforeAllCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(EventLogLeakDetector.class);

    @Override
    public void beforeAll(ExtensionContext ctx) {
        if (ctx.getTestClass().map(c -> c.getEnclosingClass() != null).orElse(true)) return;   // top-level only
        int n = count();
        if (n >= 0) ctx.getStore(NS).put("baseline", n);
    }

    @Override
    public void afterAll(ExtensionContext ctx) {
        Integer baseline = ctx.getStore(NS).get("baseline", Integer.class);
        if (baseline == null) return;
        int n = count();
        if (n > baseline) {
            throw new AssertionError("EventLog.global() subscriber LEAK: " + ctx.getRequiredTestClass().getName()
                    + " left " + (n - baseline) + " subscriber(s) attached (" + baseline + " -> " + n
                    + ") — close the CollectorService / ControlApi / SpaceManager it created");
        }
    }

    private static int count() {
        try {
            Class<?> log = Class.forName("com.gamma.event.EventLog");
            Object global = log.getMethod("global").invoke(null);
            Method m = log.getMethod("subscriberCount");
            return (int) m.invoke(global);
        } catch (ReflectiveOperationException | LinkageError e) {
            return -1;
        }
    }
}
