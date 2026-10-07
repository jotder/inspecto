package com.gamma.asn.transform;

import com.gamma.asn.plugin.PluginContext;
import com.gamma.asn.plugin.TransformFunctionProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Named transform functions (REDESIGN.md §4.4): explicit registration, no reflection.
 * Vendor/operator functions are registered by the pipeline (or a plugin), never baked in.
 * {@link #invoke} returns null for unknown functions or failures — the legacy engine
 * emitted a null field value in that case, and parity keeps that.
 */
public interface FunctionRegistry {

    @FunctionalInterface
    interface TxFunction {
        Object apply(List<Object> args) throws Exception;
    }

    /** Null when the function is unknown or throws (legacy invokeDynamic contract). */
    Object invoke(String name, List<Object> args);

    /**
     * Core generics plus every plugin discovered on {@code loader} (REDESIGN.md §4.5),
     * bound to one pipeline's context. A function name registered twice is a config
     * error and fails loudly here, not at row time.
     */
    static FunctionRegistry fromProviders(PluginContext context, ClassLoader loader) {
        Map<String, TxFunction> all = new HashMap<>(CoreFunctions.functions());
        for (TransformFunctionProvider provider : ServiceLoader.load(TransformFunctionProvider.class, loader)) {
            provider.functions(context).forEach((name, f) -> {
                if (all.put(name, f::apply) != null) {
                    throw new IllegalStateException("duplicate transform function: " + name
                            + " (from " + provider.getClass().getName() + ")");
                }
            });
        }
        return of(all);
    }

    static FunctionRegistry of(Map<String, TxFunction> functions) {
        Map<String, TxFunction> byName = new java.util.HashMap<>();
        functions.forEach((k, v) -> byName.put(k.toLowerCase(Locale.ROOT), v));
        return (name, args) -> {
            TxFunction f = byName.get(name.toLowerCase(Locale.ROOT));
            if (f == null) {
                return null;
            }
            try {
                return f.apply(args);
            } catch (Exception e) {
                return null;
            }
        };
    }
}
