package com.gamma.insights;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the insights module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link InsightScanJobType}. */
public final class InsightSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:insights"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(
                new SignalTypeDecl(InsightScanJobType.COMPLETED, "Insight scan completed", "job-type:" + InsightScanJobType.TYPE, List.of(
                        SignalField.always("job", Kind.STRING, "The Job's name"),
                        SignalField.always("profile", Kind.STRING, "The Insight Profile"),
                        SignalField.always("dataset", Kind.STRING, "The Dataset scanned"),
                        SignalField.always("insights", Kind.INTEGER, "Insights found"),
                        SignalField.always("new", Kind.INTEGER, "Insights not seen before"),
                        SignalField.always("tests", Kind.INTEGER, "Tests run")), "4.0.0"),
                new SignalTypeDecl(InsightScanJobType.FOUND, "Insight found", "job-type:" + InsightScanJobType.TYPE, List.of(
                        SignalField.always("profile", Kind.STRING, "The Insight Profile"),
                        SignalField.always("insight", Kind.STRING, "The Insight id"),
                        SignalField.always("type", Kind.STRING, "The Insight type"),
                        SignalField.always("dimension", Kind.STRING, "The dimension"),
                        SignalField.always("slice", Kind.STRING, "The dimension value"),
                        SignalField.always("period", Kind.STRING, "The period"),
                        SignalField.always("score", Kind.NUMBER, "The score"),
                        SignalField.always("band", Kind.STRING, "elevated or high"),
                        SignalField.always("sentence", Kind.STRING, "The plain-language explanation")), "4.0.0"));
    }
}
