package com.gamma.asn.plugin;

import java.util.List;

/**
 * A named transform function invocable from a tx config ({@code "@transform": "name(args)"}).
 * Arguments arrive as the evaluated parameter values (record maps, Strings, BigIntegers…);
 * a thrown exception means "no value" — the engine emits a null field, like the legacy
 * reflective dispatch did.
 */
@FunctionalInterface
public interface TransformFunction {
    Object apply(List<Object> args) throws Exception;
}
