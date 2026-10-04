package {{packageName}};

import com.gamma.job.ServiceProvider;
import com.gamma.notify.MailAccess;
import com.gamma.util.RunLog;

/**
 * {{name}} - contributes the Platform Service {@code {{id}}}. A Job or Step in any pack reaches it by
 * declaring {@code requires: [{{id}}]}.
 *
 * <p>TODO: the example implements {@link MailAccess}, which the engine publishes. Replace it with the
 * engine-published interface you are implementing (the interface must NOT be defined in this pack - see the
 * README) and implement it in {@link #create()}.
 */
public class {{className}}Service implements ServiceProvider {

    @Override public String id() { return "{{id}}"; }

    @Override public Class<?> type() { return MailAccess.class; }

    @Override public Object create() {
        // TODO: the real implementation.
        return (MailAccess) (to, cc, subject, body, attachments) -> true;
    }

    /** Mandatory for a mutating service: record the would-be effect, perform nothing. */
    @Override public Object dryRun(RunLog log) {
        return (MailAccess) (to, cc, subject, body, attachments) -> {
            log.info("dry run: would send mail", "subject", subject);
            return false;
        };
    }

    // A service that never mutates anything: delete dryRun and declare it instead.
    // @Override public boolean readOnly() { return true; }
}
