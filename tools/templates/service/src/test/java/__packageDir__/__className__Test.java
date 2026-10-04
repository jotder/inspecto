package {{packageName}};

import com.gamma.notify.MailAccess;
import com.gamma.util.RunLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The two contract points the engine enforces at load: the service is an instance of its interface, and a
 *  mutating service's dry-run stand-in records instead of acting. */
class {{className}}Test {

    private final {{className}}Service provider = new {{className}}Service();

    @Test
    void theServiceImplementsItsDeclaredInterface() {
        assertTrue(provider.type().isInstance(provider.create()));
    }

    @Test
    void theDryRunStandInRecordsAndDoesNotAct() throws Exception {
        List<String> lines = new ArrayList<>();
        RunLog log = new RunLog() {
            @Override public void info(String message, Object... kv) { lines.add(message); }
            @Override public void warn(String message, Object... kv) { }
            @Override public void error(String message, Throwable t, Object... kv) { }
        };
        MailAccess standIn = (MailAccess) provider.dryRun(log);
        assertFalse(standIn.send(List.of("a@b.c"), List.of(), "hi", "body"));
        assertEquals(1, lines.size());
    }
}
