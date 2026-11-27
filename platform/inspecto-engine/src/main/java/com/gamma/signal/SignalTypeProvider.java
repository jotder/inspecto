package com.gamma.signal;

import com.gamma.api.PublicApi;

import java.util.List;

/**
 * Declares the Signal Types one part of the product posts (SIGNAL-BOARD-1 §11.2, D-SB6). Found by
 * {@code ServiceLoader} (through {@code EntitledServices}), like {@code JobTypeProvider}: core ships
 * {@link CoreSignalTypes}; each module ships one beside its own constants class, so core never names a module's
 * vocabulary. List an implementation in {@code META-INF/services/com.gamma.signal.SignalTypeProvider}.
 */
@PublicApi(since = "4.0.0")
public interface SignalTypeProvider {

    /** Every Signal Type this provider declares. A type declared by two providers fails {@link SignalTypeCatalog}. */
    List<SignalTypeDecl> signalTypes();

    /** Where these types come from, as {@code GET /signals/types} reports it: {@code core}, or {@code module:<id>}. */
    String source();
}
