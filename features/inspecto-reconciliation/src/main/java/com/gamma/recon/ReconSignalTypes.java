package com.gamma.recon;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the reconciliation module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link ReconRunJob}. */
public final class ReconSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:reconciliation"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(new SignalTypeDecl(ReconSignals.RECON_RUN_COMPLETED, "Reconciliation run", "job-type:recon.run", List.of(
                SignalField.always("reconciliation", Kind.STRING, "The Reconciliation"),
                SignalField.always("day", Kind.STRING, "The day reconciled"),
                SignalField.always("missingLeft", Kind.INTEGER, "Keys missing on the left"),
                SignalField.always("missingRight", Kind.INTEGER, "Keys missing on the right"),
                SignalField.always("valueBreak", Kind.INTEGER, "Keys whose values differ"),
                SignalField.always("breaks", Kind.INTEGER, "All Breaks"),
                SignalField.always("matchedKeys", Kind.INTEGER, "Keys that matched")), "4.0.0"));
    }
}
