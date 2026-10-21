package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.entitystore.MaskingDefault;
import com.gamma.etl.EditionFeatures;

/**
 * {@link MaskingDefault} by edition (operator 2026-10-21): Basic - no {@link EditionFeatures#INVESTIGATIONS} - masks nothing
 * unless the Space says so; every other edition keeps the shipped {@code typed}. Read live, so a test pinning the edition
 * ({@link EditionFeatures#overrideForTest}) sees it at once.
 */
public final class EditionMaskingDefault implements MaskingDefault {

    @Override
    public String mode() {
        return EditionFeatures.present(EditionFeatures.INVESTIGATIONS) ? ConfigSpecs.LINK_ANALYSIS_MASKING_MODES.get(0) : "none";
    }
}
