package com.gamma.entitystore;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The Link Analysis masking mode in force when a Space states no {@code masking_mode}
 * ({@link LinkAnalysisSettings#effectiveMaskingMode}). The EDITION decides it (operator 2026-10-21): Basic masks nothing by
 * default ({@code none}), Standard, Advanced and Preview keep the shipped {@code typed}. A Space that states a mode keeps it
 * in every edition. The edition features live in a module this one cannot see, so the control-plane module provides this
 * ({@link ServiceLoader}); with no provider on the classpath the shipped default ({@code typed}) holds.
 */
public interface MaskingDefault {

    /** The in-force default, one of {@code ConfigSpecs.LINK_ANALYSIS_MASKING_MODES}; read on every resolve, never cached. */
    String mode();

    static Optional<MaskingDefault> provider() {
        return ServiceLoader.load(MaskingDefault.class).findFirst();
    }
}
