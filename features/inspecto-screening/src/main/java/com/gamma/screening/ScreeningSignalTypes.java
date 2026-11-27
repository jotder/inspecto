package com.gamma.screening;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the screening module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link ScreeningJobType}. */
public final class ScreeningSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:screening"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(new SignalTypeDecl(ScreeningSignals.SCREENING_HITS_RAISED, "Screening Hits raised", "job-type:screening.run", List.of(
                SignalField.always("dataset", Kind.STRING, "The Dataset screened"),
                SignalField.always("screened", Kind.INTEGER, "Subjects screened"),
                SignalField.always("matched", Kind.INTEGER, "Matches found"),
                SignalField.always("raised", Kind.INTEGER, "New Screening Hits raised")), "4.0.0"));
    }
}
