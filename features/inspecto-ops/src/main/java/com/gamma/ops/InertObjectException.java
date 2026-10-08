package com.gamma.ops;

/**
 * A mutation was aimed at an <b>inert</b> object (see {@link OperationalObject#inert}): a stored row whose type this
 * build does not know. It is refused, never applied, so the row stays exactly as stored. Mapped to HTTP 409.
 *
 * @since 4.0.0
 */
public final class InertObjectException extends IllegalStateException {
    public InertObjectException(String id, String rawType) {
        super("object '" + id + "' has type '" + rawType + "', which is not installed/known: it is inert and left untouched");
    }
}
