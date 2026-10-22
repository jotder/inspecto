package com.gamma.control;

import com.gamma.config.spec.ConfigSpecs;
import com.gamma.entitystore.MaskingDefault;
import com.gamma.spi.auth.Authenticators;

/**
 * {@link MaskingDefault} by SIGN-IN ({@code LA-ONBOARD-1} OB-6, operator answer A-16): an install where nobody signs in
 * ({@code auth.mode} {@code none} and no {@link com.gamma.spi.auth.Authenticator} installed - Basic) masks nothing unless the
 * Space says so; an install where people sign in keeps the shipped {@code typed}, whatever its Link Analysis level - a sign-in
 * install without the Investigations add-on stays masked. Before OB-6 this keyed on the {@code investigations} edition
 * feature, which the runtime add-on now withholds on sign-in installs too. Read live, so a test arming sign-in sees it at once.
 */
public final class EditionMaskingDefault implements MaskingDefault {

    @Override
    public String mode() {
        return signsIn() ? ConfigSpecs.LINK_ANALYSIS_MASKING_MODES.get(0) : "none";
    }

    /** People sign in: a stated {@code auth.mode} other than {@code none}, or an installed Authenticator. */
    static boolean signsIn() {
        return !"none".equalsIgnoreCase(System.getProperty("auth.mode", "none").trim()) || Authenticators.active().isPresent();
    }
}
