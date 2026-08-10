package com.gamma.job;

import com.gamma.notify.MailAccess;
import com.gamma.signal.SignalEmitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mail.send} reference Job (job-parameter-contract §9, step 15).
 *
 * <p>The declaration half is pinned by {@link MailSendJobTypeTest}; this covers the behaviour that the
 * declaration implies — CSV list parameters, the explicit-recipient contract, and the two degrade paths.
 */
class MailSendJobTest {

    // ── a MailAccess stub that records what it was asked to send ────────────────

    private static final class FakeMail implements MailAccess {
        List<String> to = List.of();
        List<String> cc = List.of();
        String subject;
        String body;
        boolean configured = true;
        RuntimeException blowUp;

        @Override
        public boolean send(List<String> to, List<String> cc, String subject, String body) {
            if (blowUp != null) throw blowUp;
            this.to = to;
            this.cc = cc;
            this.subject = subject;
            this.body = body;
            return configured;
        }
    }

    private static final class FakeJobContext implements JobContext {
        final Map<String, String> params;
        final FakeMail mail;
        final List<Map<String, Object>> signals = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        FakeJobContext(Map<String, String> params, FakeMail mail) {
            this.params = params;
            this.mail = mail;
        }

        @Override public String runId() { return "run-1"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return Map.of(); }
        @Override public Map<String, String> params() { return params; }
        @Override public ArtifactRecorder artifacts() { return null; }
        @Override public boolean dryRun() { return false; }

        @Override public PlatformServices services() {
            return new PlatformServices() {
                @Override public <T> java.util.Optional<T> find(Class<T> type) {
                    return type == MailAccess.class ? java.util.Optional.of(type.cast(mail)) : java.util.Optional.empty();
                }
                @Override public java.util.Set<Class<?>> granted() { return java.util.Set.of(MailAccess.class); }
            };
        }

        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) { }
                @Override public void warn(String message, Object... kv) { }
                @Override public void error(String message, Throwable t, Object... kv) { errors.add(message); }
            };
        }

        @Override public SignalEmitter signals() {
            return (type, severity, payload) -> {
                Map<String, Object> got = new LinkedHashMap<>(payload);
                got.put("__type", type);
                signals.add(got);
            };
        }
    }

    private static JobResult run(Map<String, String> params, FakeMail mail) throws Exception {
        JobConfig cfg = JobConfig.fromMap(Map.<String, Object>of("job", Map.of("name", "nightly_mail", "type", "mail.send")));
        return new MailSendJobType().create(cfg).run(new FakeJobContext(params, mail));
    }

    @Test
    void aMultiParameterArrivesAsCsvAndIsSplitBackIntoAddresses() throws Exception {
        FakeMail mail = new FakeMail();
        JobResult r = run(Map.of("to", "a@x.io, b@x.io", "cc", "c@x.io",
                "subject", "Nightly", "body", "All good."), mail);

        assertEquals(List.of("a@x.io", "b@x.io"), mail.to,
                "a `multi` param is CSV on the wire (§7.5) — sent verbatim it would be one bad address");
        assertEquals(List.of("c@x.io"), mail.cc);
        assertEquals("Nightly", mail.subject);
        assertEquals("All good.", mail.body);
        assertEquals("SUCCESS", r.status());
        assertTrue(r.message().contains("3 recipient"), () -> "unexpected message: " + r.message());
    }

    @Test
    void ccIsOptionalAndAbsenceIsNotAnEmptyAddress() throws Exception {
        FakeMail mail = new FakeMail();
        run(Map.of("to", "a@x.io", "subject", "s", "body", "b"), mail);
        assertEquals(List.of(), mail.cc);
    }

    @Test
    void aToThatResolvesToNothingFailsRatherThanSendingToNobody() throws Exception {
        // `to` is declared required, so the resolver refuses an unbound Run before this point. Reaching
        // here means it resolved to blanks — succeeding at sending to no one would be the dishonest read.
        FakeMail mail = new FakeMail();
        JobResult r = run(Map.of("to", " , ", "subject", "s", "body", "b"), mail);

        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("no addresses"), () -> "unexpected message: " + r.message());
    }

    @Test
    void anUnconfiguredEmailChannelIsInertRatherThanARedRunEveryFire() throws Exception {
        FakeMail mail = new FakeMail();
        mail.configured = false;
        JobResult r = run(Map.of("to", "a@x.io", "subject", "s", "body", "b"), mail);

        assertEquals("SUCCESS", r.status(), "a deployment without SMTP must not fail this Job on every fire");
        assertTrue(r.message().contains("nothing sent"), () -> "unexpected message: " + r.message());
    }

    @Test
    void aTransportFailureFailsTheRunAndIsLogged() throws Exception {
        FakeMail mail = new FakeMail();
        mail.blowUp = new RuntimeException("smtp refused");
        FakeJobContext ctx = new FakeJobContext(
                Map.of("to", "a@x.io", "subject", "s", "body", "b"), mail);
        JobResult r = new MailSendJobType()
                .create(JobConfig.fromMap(Map.<String, Object>of("job", Map.of("name", "m", "type", "mail.send")))).run(ctx);

        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("smtp refused"), () -> "unexpected message: " + r.message());
        assertFalse(ctx.errors.isEmpty(), "a transport failure must reach the Run Log");
    }

    @Test
    void itEmitsTheDeclaredSignalWithARecipientCountAndNoAddresses() throws Exception {
        FakeMail mail = new FakeMail();
        FakeJobContext ctx = new FakeJobContext(
                Map.of("to", "a@x.io,b@x.io", "subject", "s", "body", "b"), mail);
        new MailSendJobType().create(JobConfig.fromMap(Map.<String, Object>of("job", Map.of("name", "m", "type", "mail.send")))).run(ctx);

        assertEquals(1, ctx.signals.size());
        Map<String, Object> emitted = ctx.signals.get(0);
        assertEquals("mail.sent", emitted.get("__type"),
                "the descriptor declares it emits mail.sent — the emission must match the declaration");
        assertEquals(2, emitted.get("recipients"));
        // Addresses are the operator's contact data, not diagnostics: the payload carries a COUNT.
        assertFalse(emitted.toString().contains("@"), () -> "signal payload leaked an address: " + emitted);
    }
}
