package com.gamma.entitystore;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The Link Analysis masking mode in force when a Space states no {@code masking_mode}
 * ({@link LinkAnalysisSettings#effectiveMaskingMode}). Whether people SIGN IN decides it ({@code LA-ONBOARD-1} OB-6, A-16;
 * was the edition, 2026-10-21): no sign-in (Basic) masks nothing by default ({@code none}), a sign-in install keeps the
 * shipped {@code typed}. A Space that states a mode keeps it everywhere. The sign-in facts live in a module this one cannot
 * see, so the control-plane module provides this
 * ({@link ServiceLoader}); with no provider on the classpath the shipped default ({@code typed}) holds.
 */
public interface MaskingDefault {

    /** The in-force default, one of {@code ConfigSpecs.LINK_ANALYSIS_MASKING_MODES}; read on every resolve, never cached. */
    String mode();

    static Optional<MaskingDefault> provider() {
        return ServiceLoader.load(MaskingDefault.class).findFirst();
    }
}
