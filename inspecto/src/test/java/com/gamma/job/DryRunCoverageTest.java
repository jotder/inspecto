package com.gamma.job;

import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recurrence guard for {@code PLATFORM-SERVICES-DRYRUN-GAP-1}: every built-in Platform Service the host binds
 * ({@code CollectorService}) is either declared read-only here — with the reason — or substituted by
 * {@link DryRunServices}. Before this guard, {@code mail} and {@code objects} joined the menu without a stand-in
 * and acted for real under every dry run.
 *
 * <p>The registrations are read from {@code CollectorService}'s source, because the host's registry is a private
 * field built inside a constructor that boots a whole Space. Substitution is checked by behaviour, not by a list:
 * each interface is bound to a dynamic proxy, and a dry-run lookup must hand back something else.
 */
class DryRunCoverageTest {

    /** Built-ins that may pass through a dry run untouched — each one only reads. */
    private static final Map<String, String> READ_ONLY = Map.of(
            "schema", "lists registry/schemas/*.toon",
            "consignment-status", "reads pipeline manifests",
            "datasets", "read-only Dataset relations",
            "analytics", "pure statistics over a caller-held read");

    private static final Pattern REGISTER =
            Pattern.compile("platformServices\\.register\\(\\s*\"([^\"]+)\"\\s*,\\s*([\\w.]+)\\.class");

    private static final RunLog SILENT = new RunLog() {
        @Override public void info(String m, Object... kv) {}
        @Override public void warn(String m, Object... kv) {}
        @Override public void error(String m, Throwable t, Object... kv) {}
    };

    private static Map<String, Class<?>> hostRegistrations() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/gamma/service/CollectorService.java"));
        Map<String, Class<?>> out = new LinkedHashMap<>();
        Matcher m = REGISTER.matcher(src);
        while (m.find()) out.put(m.group(1), Class.forName(m.group(2)));
        return out;
    }

    @Test
    void everyMutatingBuiltInServiceHasADryRunStandIn() throws Exception {
        Map<String, Class<?>> host = hostRegistrations();
        assertTrue(host.size() >= 10, "the source scan found too few registrations to be trusted: " + host.keySet());
        assertTrue(host.keySet().containsAll(READ_ONLY.keySet()),
                "a read-only entry names a service the host no longer binds: " + READ_ONLY.keySet());

        for (Map.Entry<String, Class<?>> e : host.entrySet()) {
            Class<?> type = e.getValue();
            Object real = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class)   // toString/equals/hashCode for messages
                            return switch (method.getName()) {
                                case "toString" -> "real " + e.getKey();
                                case "equals" -> proxy == args[0];
                                default -> System.identityHashCode(proxy);
                            };
                        throw new AssertionError("real service reached: " + e.getKey());
                    });
            PlatformServiceRegistry registry = new PlatformServiceRegistry();
            register(registry, e.getKey(), type, real);
            Object seen = DryRunServices.wrap(registry.grant(Set.of(e.getKey())), SILENT).find(type).orElseThrow();
            if (READ_ONLY.containsKey(e.getKey()))
                assertSame(real, seen, "read-only service '" + e.getKey() + "' must pass through a dry run");
            else
                assertNotSame(real, seen, "mutating service '" + e.getKey() + "' passes through a dry run "
                        + "unchanged: substitute it in DryRunServices, or declare it read-only here with the reason");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void register(PlatformServiceRegistry r, String id, Class<T> type, Object impl) {
        r.register(id, type, (T) impl);
    }
}
