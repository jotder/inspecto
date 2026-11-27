package com.gamma.opsjob;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the ops module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link ObjectsAnalyticsJob}. */
public final class OpsSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:ops"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        // Two shapes: the summary of a run, or {rows, error} when writing the catalog failed.
        return List.of(new SignalTypeDecl(OpsSignals.OBJECTS_ANALYTICS_COMPLETED, "Incident and Case analytics written",
                "job-type:objects.analytics", List.of(
                SignalField.always("rows", Kind.INTEGER, "Rows computed"),
                SignalField.optional("error", Kind.STRING, "Why the write failed (failure shape only)"),
                SignalField.optional("dataset", Kind.STRING, "The analytics Dataset"),
                SignalField.optional("ledgerRows", Kind.INTEGER, "Impact ledger rows"),
                SignalField.optional("types", Kind.LIST, "The object types analysed"),
                SignalField.optional("durationMs", Kind.INTEGER, "How long the run took"),
                SignalField.optional("purged", Kind.INTEGER, "Rows purged, when any")), "4.0.0"));
    }
}
