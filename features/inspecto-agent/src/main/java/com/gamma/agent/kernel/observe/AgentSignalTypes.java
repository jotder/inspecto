package com.gamma.agent.kernel.observe;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/** The Signal Types the agent module posts (SIGNAL-BOARD-1 SB-1b), fields read from {@link EventLogAuditSink}. */
public final class AgentSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:agent"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(
                decl("agent.run.started", "Agent run started",
                        SignalField.always("contextKeys", Kind.LIST, "The context keys handed to the capability")),
                decl("agent.run.completed", "Agent run completed",
                        SignalField.always("status", Kind.STRING, "The result status"),
                        SignalField.always("evidenceCount", Kind.INTEGER, "Evidence items gathered"),
                        SignalField.always("durationMs", Kind.INTEGER, "How long the run took"),
                        SignalField.always("contextKeys", Kind.LIST, "The context keys handed to the capability"),
                        SignalField.always("servedBy", Kind.STRING, "The model tier that answered"),
                        SignalField.always("modelInvoked", Kind.BOOLEAN, "Whether a model was called"),
                        SignalField.always("repairRounds", Kind.INTEGER, "Output repair rounds"),
                        SignalField.always("confidence", Kind.NUMBER, "The result's confidence"),
                        SignalField.always("promptTokens", Kind.INTEGER, "Prompt tokens"),
                        SignalField.always("completionTokens", Kind.INTEGER, "Completion tokens")),
                decl("agent.run.failed", "Agent run failed",
                        SignalField.always("category", Kind.STRING, "The error category"),
                        SignalField.always("reason", Kind.STRING, "Why it failed")),
                decl("agent.model.called", "Agent called a model",
                        SignalField.always("tier", Kind.STRING, "The model tier"),
                        SignalField.always("jsonFormat", Kind.BOOLEAN, "Whether JSON output was asked for")),
                decl("agent.tool.called", "Agent called a tool",
                        SignalField.always("toolId", Kind.STRING, "The tool")),
                decl("agent.tool.completed", "Agent tool returned",
                        SignalField.always("toolId", Kind.STRING, "The tool"),
                        SignalField.always("evidenceCount", Kind.INTEGER, "Evidence items returned"),
                        SignalField.always("durationMs", Kind.INTEGER, "How long the call took")),
                decl("agent.human.decided", "A person decided on an agent proposal",
                        SignalField.always("decision", Kind.STRING, "The decision"),
                        SignalField.always("reviewer", Kind.STRING, "Who decided"),
                        SignalField.always("reference", Kind.STRING, "What was decided on")));
    }

    private static SignalTypeDecl decl(String type, String title, SignalField... fields) {
        return new SignalTypeDecl(type, title, "agent", List.of(fields), "4.0.0");
    }
}
