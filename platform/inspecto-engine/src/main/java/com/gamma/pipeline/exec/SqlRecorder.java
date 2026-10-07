package com.gamma.pipeline.exec;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the SQL a block of code executes on a JDBC {@link Connection}, so a preview can show the
 * author <b>the statements their config compiled to</b>.
 *
 * <p><b>Why a wrapper and not a parameter.</b> {@link RowShaper} builds its SQL internally and runs it
 * through 13 call sites across 9 private methods. Threading a sink through all of them would change
 * production signatures and production call sites for a preview-only feature; wrapping the connection
 * changes nothing anyone else executes. It also cannot miss a statement: every route into the database
 * goes through {@code Connection}, so a path that bypassed {@code RowShaper.exec} would still be seen.
 *
 * <p>⚠ <b>Recording only.</b> The handler forwards every call unchanged and never rewrites, blocks or
 * reorders SQL — the previewed execution must be the same execution, or the schema it derives describes
 * something the author is not running.
 *
 * <p>Not thread-safe, and not meant to be: one recorder wraps one connection for one preview call.
 */
final class SqlRecorder {

    private final List<String> statements = new ArrayList<>();

    /**
     * A {@code Connection} that behaves exactly like {@code real} while recording the SQL text passed to
     * any {@code Statement} it hands out. The returned proxy must not be closed — close {@code real}.
     */
    Connection wrap(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    return result instanceof Statement st && method.getName().startsWith("createStatement")
                            ? wrapStatement(st)
                            : result;
                });
    }

    /** The statements executed through the wrapper, in execution order. */
    List<String> statements() {
        return List.copyOf(statements);
    }

    private Statement wrapStatement(Statement real) {
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    // execute / executeQuery / executeUpdate / executeLargeUpdate all take the SQL first.
                    if (method.getName().startsWith("execute")
                            && args != null && args.length > 0 && args[0] instanceof String sql)
                        statements.add(sql);
                    return invoke(real, method, args);
                });
    }

    /** Forward a proxied call, unwrapping the reflection layer so the caller sees the real exception. */
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }
}
