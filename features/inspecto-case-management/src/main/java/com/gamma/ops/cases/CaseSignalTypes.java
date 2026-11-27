package com.gamma.ops.cases;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the case-management module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link CaseRuleEvalJob}. */
public final class CaseSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:case-management"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(new SignalTypeDecl(CaseSignals.CASERULE_EVALUATE_COMPLETED, "Case Rule evaluated",
                "job-type:caserule.evaluate", List.of(
                SignalField.always("rule", Kind.STRING, "The Case Rule"),
                SignalField.always("matched", Kind.INTEGER, "Incidents the rule matched"),
                SignalField.always("grouped", Kind.INTEGER, "Incidents grouped into a Case"),
                SignalField.always("opened", Kind.BOOLEAN, "Whether a Case was opened"),
                SignalField.optional("caseId", Kind.STRING, "The Case, when one was opened or updated")), "4.0.0"));
    }
}
