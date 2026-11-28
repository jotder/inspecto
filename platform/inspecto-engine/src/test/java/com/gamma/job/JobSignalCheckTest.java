package com.gamma.job;

import com.gamma.signal.SignalField;
import com.gamma.signal.SignalType;
import com.gamma.signal.SignalTypeCatalog;
import com.gamma.signal.SignalTypeDecl;
import com.gamma.signal.SignalTypeProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** SIGNAL-BOARD-1 SB-2: the Job save checks, against a small fixed catalog. */
class JobSignalCheckTest {

    private static final SignalTypeProvider PROVIDER = new SignalTypeProvider() {
        public List<SignalTypeDecl> signalTypes() {
            return List.of(
                    new SignalTypeDecl("sb2.order.landed", "Order landed", "engine",
                            List.of(SignalField.always("dataset", SignalField.Kind.STRING, "d"),
                                    SignalField.optional("rows", SignalField.Kind.INTEGER, "r")), "4.0.0"),
                    new SignalTypeDecl("sb2.order.failed", "Order failed", "engine",
                            List.of(SignalField.always("reason", SignalField.Kind.STRING, "r")), "4.0.0"),
                    new SignalTypeDecl("sb2.free.*", "Free family", "exchange", List.of(), "4.0.0"),
                    new SignalTypeDecl("sb2.free.known", "Known member", "engine",
                            List.of(SignalField.always("k", SignalField.Kind.STRING, "k")), "4.0.0"),
                    new SignalTypeDecl(SignalType.JOB_RUN_COMPLETED, "Run completed", "engine",
                            List.of(SignalField.always("job", SignalField.Kind.STRING, "j")), "4.0.0"),
                    new SignalTypeDecl(SignalType.JOB_RUN_FAILED, "Run failed", "engine",
                            List.of(SignalField.always("job", SignalField.Kind.STRING, "j")), "4.0.0"));
        }

        public String source() { return "test"; }
    };

    private static final SignalTypeCatalog CATALOG = SignalTypeCatalog.assemble(List.of(PROVIDER),
            List.of(new SignalTypeCatalog.PackEmit("p.zip", "packtype", "sb2.pack.done")));

    /** Job Types: {@code poster} declares {@code sb2.order.landed}, {@code failer} {@code sb2.order.failed}; others nothing. */
    private static final Function<String, List<String>> EMITS =
            t -> switch (t) {
                case "poster" -> List.of("sb2.order.landed");
                case "failer" -> List.of("sb2.order.failed");
                default -> List.of();
            };

    private static JobConfig job(String name, String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return JobConfig.fromMap(Map.of("job", m));
    }

    private static List<JobSignalCheck.Finding> check(JobConfig draft, JobConfig... others) {
        return JobSignalCheck.check(draft, CATALOG, List.of(others), EMITS);
    }

    private static List<JobSignalCheck.Kind> kinds(List<JobSignalCheck.Finding> f) {
        return f.stream().map(JobSignalCheck.Finding::kind).toList();
    }

    @Test
    void aJobWithNoOnSignalIsNotChecked() {
        assertEquals(List.of(), check(job("a", "noop", "cron", "0 0 * * *")));
    }

    @Test
    void anUncataloguedTypeIsAnUnpostedWarningThatNeverRefusesAnAgent() {
        List<JobSignalCheck.Finding> f = check(job("a", "noop", "on_signal", "sb2.order.landd"));
        assertEquals(List.of(JobSignalCheck.Kind.UNPOSTED_TYPE), kinds(f));
        assertFalse(f.getFirst().agentRefusable(), "D-SB3: an unposted type warns everyone");
        assertEquals("SIGNAL_TYPE_UNPOSTED", f.getFirst().toMap().get("code"));
        assertEquals("WARNING", f.getFirst().toMap().get("severity"));
        // a prefix that matches nothing is unposted too; one that matches a type is not
        assertEquals(List.of(JobSignalCheck.Kind.UNPOSTED_TYPE), kinds(check(job("a", "noop", "on_signal", "sb2.nothing.*"))));
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "sb2.order.*"))));
        // the dispatcher matches an exact type case-insensitively; so does the check
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "SB2.Order.Landed"))));
    }

    @Test
    void aFamilyAndAPackEmitAreCatalogued() {
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "sb2.free.anything"))));
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "sb2.pack.done"))));
    }

    @Test
    void everySignalFieldOfWhenAndBindMustBeDeclared() {
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "sb2.order.landed",
                "when", "$signal.dataset == \"orders\" && $signal.rows > 0 && $signal.chainDepth < 3",
                "bind", Map.of("ds", "$signal.dataset")))), "declared fields and the stamped chainDepth pass");

        List<JobSignalCheck.Finding> f = check(job("a", "noop", "on_signal", "sb2.order.landed",
                "when", "$signal.datset == \"orders\"", "bind", Map.of("why", "$signal.reason.text")));
        assertEquals(List.of(JobSignalCheck.Kind.UNDECLARED_FIELD, JobSignalCheck.Kind.UNDECLARED_FIELD), kinds(f));
        assertEquals("when", f.get(0).key());
        assertEquals("datset", f.get(0).subject());
        assertEquals("bind.why", f.get(1).key());
        assertEquals("reason", f.get(1).subject(), "the first path segment is the field");
        assertTrue(f.get(0).agentRefusable());
        assertTrue(f.get(0).fix().contains("dataset"), "the fix names the declared fields: " + f.get(0).fix());
    }

    @Test
    void aPrefixAcceptsAFieldAnyMatchedTypeDeclares() {
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "sb2.order.*", "when", "$signal.reason == x"))));
        assertEquals(List.of(JobSignalCheck.Kind.UNDECLARED_FIELD),
                kinds(check(job("a", "noop", "on_signal", "sb2.order.*", "when", "$signal.nope == x"))));
    }

    @Test
    void fieldsOfATypeThatDeclaresNoneAreNotCheckedAndSaySo() {
        for (String type : List.of("sb2.pack.done", "sb2.free.x")) {
            List<JobSignalCheck.Finding> f = check(job("a", "noop", "on_signal", type, "when", "$signal.whatever == 1"));
            assertEquals(List.of(JobSignalCheck.Kind.FIELDS_NOT_DECLARED), kinds(f), type);
            assertFalse(f.getFirst().agentRefusable(), "a field that cannot be checked is never refused");
            assertEquals("INFO", f.getFirst().toMap().get("severity"));
        }
    }

    @Test
    void twoJobsThatFireEachOtherAreALoop() {
        JobConfig b = job("b", "poster", "on_signal", SignalType.JOB_RUN_COMPLETED);
        List<JobSignalCheck.Finding> f = check(job("a", "noop", "on_signal", "sb2.order.landed"), b);
        assertEquals(List.of(JobSignalCheck.Kind.CHAIN_LOOP), kinds(f));
        assertTrue(f.getFirst().message().contains("a → b → a"), f.getFirst().message());
        assertTrue(f.getFirst().agentRefusable());
        assertEquals("a,b", f.getFirst().subject());
    }

    @Test
    void aLongerLoopIsFoundAndAGuardOrADisabledJobBreaksIt() {
        // a posts landed → b; b's Run completes → c; c posts failed → a
        JobConfig a = job("a", "poster", "on_signal", "sb2.order.failed");
        JobConfig b = job("b", "noop", "on_signal", "sb2.order.landed");
        JobConfig c = job("c", "failer", "on_signal", SignalType.JOB_RUN_COMPLETED);
        List<JobSignalCheck.Finding> f = check(a, b, c);
        assertEquals(List.of(JobSignalCheck.Kind.CHAIN_LOOP), kinds(f));
        assertTrue(f.getFirst().message().contains("a → b → c → a"), f.getFirst().message());
        assertEquals(List.of(), kinds(check(a, b)), "a → b, but nothing leads back to a");

        JobConfig guarded = job("c", "failer", "on_signal", SignalType.JOB_RUN_COMPLETED, "when", "$signal.job == \"x\"");
        assertEquals(List.of(), kinds(check(a, b, guarded)), "a when guard may stop the chain");
        JobConfig off = job("c", "failer", "on_signal", SignalType.JOB_RUN_COMPLETED, "enabled", "false");
        assertEquals(List.of(), kinds(check(a, b, off)), "a disabled Job never fires");
        JobConfig draftOff = job("a", "poster", "on_signal", "sb2.order.failed", "enabled", "false");
        assertEquals(List.of(), kinds(check(draftOff, b, c)), "a disabled draft joins no chain");
    }

    @Test
    void aJobIsNeverFiredByItsOwnSignalSoAloneItIsNoLoop() {
        assertEquals(List.of(), kinds(check(job("a", "poster", "on_signal", SignalType.JOB_RUN_COMPLETED))));
        // the stored copy of the draft (same name) is replaced, not counted as a second Job
        assertEquals(List.of(), kinds(check(job("a", "poster", "on_signal", SignalType.JOB_RUN_COMPLETED),
                job("a", "poster", "on_signal", SignalType.JOB_RUN_COMPLETED))));
    }

    @Test
    void aPrefixMatchesCaseSensitivelyAsTheDispatcherDoes() {
        assertEquals(List.of(JobSignalCheck.Kind.UNPOSTED_TYPE), kinds(check(job("a", "noop", "on_signal", "Sb2.free.*"))));
        assertEquals(List.of(JobSignalCheck.Kind.UNPOSTED_TYPE), kinds(check(job("a", "noop", "on_signal", "SB2.order.*"))));
    }

    @Test
    void anExactTypeTheCatalogListsCarriesItsOwnFieldsNotItsFamilys() {
        assertEquals(List.of(), kinds(check(job("a", "noop", "on_signal", "sb2.free.known", "when", "$signal.k == 1"))));
        assertEquals(List.of(JobSignalCheck.Kind.UNDECLARED_FIELD),
                kinds(check(job("a", "noop", "on_signal", "sb2.free.known", "when", "$signal.other == 1"))));
    }

    @Test
    void aLoopIsOneFindingPerJobWhicheverJobsItRunsThrough() {
        // stored a closes a → b → c → a and a → c → a; the edit removes a → b, the a → c → a loop stays
        JobConfig b = job("b", "noop", "on_signal", "sb2.order.landed");
        JobConfig c = job("c", "noop", "on_signal", SignalType.JOB_RUN_COMPLETED);
        JobSignalCheck.Finding before = check(job("a", "poster", "on_signal", SignalType.JOB_RUN_COMPLETED), b, c).getFirst();
        JobSignalCheck.Finding after = check(job("a", "noop", "on_signal", SignalType.JOB_RUN_COMPLETED), b, c).getFirst();
        assertNotEquals(before.subject(), after.subject(), "the named loop differs");
        assertEquals(before.identity(), after.identity(), "but it is the same finding, so an agent's edit is not refused");
    }
}
