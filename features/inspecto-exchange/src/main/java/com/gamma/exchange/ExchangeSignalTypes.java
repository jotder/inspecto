package com.gamma.exchange;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalField.Kind;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;

import java.util.List;

/**
 * The Signal Types the Exchange posts (SIGNAL-BOARD-1 SB-1b), read from {@link ExchangeSignalForwarder}. A delivered
 * Signal is {@code exchange.<origin space>.<origin type>} and carries only the keys its offer shares, so the family
 * declares no fields ("fields not declared"); the delivery records on the origin's ledger have fixed fields.
 */
public final class ExchangeSignalTypes implements SignalTypeProvider {

    @Override public String source() { return "module:exchange"; }

    @Override
    public List<SignalTypeDecl> signalTypes() {
        return List.of(
                new SignalTypeDecl("exchange.*", "A Signal delivered from another Space", "exchange", List.of(), "4.0.0"),
                new SignalTypeDecl(ExchangeSignalForwarder.DELIVERED, "Signal delivered to a consumer Space", "exchange", List.of(
                        SignalField.always("grant", Kind.STRING, "The Share Grant"),
                        SignalField.always("consumer", Kind.STRING, "The consumer Space"),
                        SignalField.always("signalType", Kind.STRING, "The type delivered")), "4.0.0"),
                new SignalTypeDecl(ExchangeSignalForwarder.UNDELIVERABLE, "Signal not delivered to a consumer Space", "exchange", List.of(
                        SignalField.always("grant", Kind.STRING, "The Share Grant"),
                        SignalField.always("consumer", Kind.STRING, "The consumer Space"),
                        SignalField.always("signalType", Kind.STRING, "The type not delivered"),
                        SignalField.always("reason", Kind.STRING, "Why it was not delivered")), "4.0.0"));
    }
}
