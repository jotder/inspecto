package com.gamma.signal;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The post-time check of SIGNAL-BOARD-1 §11.3, for tests only. With {@code -Dsignals.checkFields=true}, every Signal
 * posted to a ledger ({@code EventLog.emit} of a {@code SIGNAL} event, through {@code EventLog.postCheck}) must fit the
 * {@link SignalTypeCatalog}: its type catalogued, no undeclared payload key, every {@code always} field present. A
 * Signal built and handed straight to a consumer (a test fixture) is not a posting and is not checked. A misfit throws
 * {@link AssertionError}, an Error so that the posting sites that swallow a {@link RuntimeException} (an observability
 * hiccup must not break a commit) cannot hide it. A Run posts on a worker thread whose {@code Future} nobody reads, so
 * every misfit is also recorded and printed to stderr; {@code SignalPostCheckExtension} (engine test-jar, auto-detected
 * by JUnit) fails the test that was running when one was recorded. Off by default, and so in production.
 *
 * <p>Two openings, both deliberate: a type a loaded Job Pack declares in its {@code emits} passes on its name (a
 * pack's fields are not declared), and a Signal posted by a Decision Rule ({@code source.kind = decision-rule}) whose
 * type is not catalogued passes, because the rule's {@code emit-signal} consequence lets its author choose the type
 * and the payload keys (D-SB8, open).
 */
public final class SignalPostCheck {

    /** The switch. Surefire sets it for a module once that module's Signal Types are declared. */
    public static final String PROPERTY = "signals.checkFields";

    private static volatile SignalTypeCatalog installed;
    private static final Set<String> PACK_TYPES = ConcurrentHashMap.newKeySet();
    private static final Queue<String> MISFITS = new ConcurrentLinkedQueue<>();

    private SignalPostCheck() {}

    /** Is the check switched on in this JVM? */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Record the dotted types a Job Pack's Job Type declares in {@code emits}, so the check knows them. Kept for the
     * life of the JVM: an unloaded pack's types stay known, which can only make the check more lenient.
     */
    public static void packDeclared(Collection<String> emits) {
        if (!enabled() || emits == null) return;
        for (String t : emits) if (SignalTypeDecl.isDotted(t)) PACK_TYPES.add(t);
    }

    /** Arm the check on every ledger when it is switched on (called once, as {@link Signal} loads). */
    static void install() {
        if (enabled()) EventLog.postCheck(SignalPostCheck::checkEvent);
    }

    private static void checkEvent(Event e) {
        if (!EventType.SIGNAL.equals(e.type())) return;
        Signal s = Signal.fromEvent(e);
        check(s.type(), s.payload(), s.source());
    }

    /** Throws {@link AssertionError} when the check is on and this posting does not fit the catalog. */
    static void check(String type, Map<String, ?> payload, Ref source) {
        if (!enabled()) return;
        SignalTypeCatalog catalog = catalog();
        if (catalog.find(type).isEmpty()) {
            if (type != null && PACK_TYPES.contains(type)) return;
            if (source != null && "decision-rule".equals(source.kind())) return;
        }
        List<String> problems = catalog.problems(type, payload);
        if (problems.isEmpty()) return;
        String misfit = String.join("; ", problems) + " [-D" + PROPERTY + "=true; payload keys "
                + (payload == null ? "[]" : payload.keySet()) + "]";
        // A Run posts on a worker thread whose Future nobody reads, so the throw alone can vanish there: record it and
        // print it too. SignalPostCheckExtension (engine test-jar, auto-detected) fails the running test on a record.
        MISFITS.add(misfit);
        System.err.println("[" + PROPERTY + "] " + misfit);
        throw new AssertionError(misfit);
    }

    /** Every misfit recorded since the last call, emptied. For the test extension that fails the running test. */
    public static List<String> drainMisfits() {
        List<String> out = new ArrayList<>();
        for (String m; (m = MISFITS.poll()) != null; ) out.add(m);
        return out;
    }

    private static SignalTypeCatalog catalog() {
        SignalTypeCatalog c = installed;
        if (c == null) installed = c = SignalTypeCatalog.installed();
        return c;
    }
}
