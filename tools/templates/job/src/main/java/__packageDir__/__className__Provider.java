package {{packageName}};

import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobTypeDescriptor;
import com.gamma.job.JobTypeMeta;
import com.gamma.job.JobTypeProvider;
import com.gamma.job.ParamType;
import com.gamma.job.ParameterDecl;

import java.util.List;

/**
 * Declares the {@code {{id}}} Job Type. This class is the pack's entry point: it is named in
 * {@code META-INF/services/com.gamma.job.JobTypeProvider}, so the engine's ServiceLoader finds it
 * when the pack jar is dropped into the packs directory.
 *
 * <p>Three things are declared here, and all three are contracts the engine enforces:
 * <ul>
 *   <li><b>parameters</b> — what the Job needs. The engine resolves them (authored value → deduced
 *       {@code $}-Expression → default) and REJECTS the Run before your code starts if a required
 *       one is missing or a value doesn't parse. The UI renders the authoring form from this list.</li>
 *   <li><b>emits</b> — the Signal types the Job may raise, so downstream Jobs can bind to them.</li>
 *   <li><b>requires</b> — the Platform Services the Job may reach. Validated at <em>registration</em>:
 *       a typo, or a service absent from this build, refuses the whole pack rather than failing at
 *       fire time. Declaring a service you never look up is the other error — the declaration is what
 *       makes the grant honest to the operator reading it.</li>
 * </ul>
 */
@JobTypeMeta(id = "{{id}}", title = "{{name}}")
public class {{className}}Provider implements JobTypeProvider {

    @Override
    public JobTypeDescriptor descriptor() {
        return new JobTypeDescriptor(
                "{{id}}",
                "{{name}}",
                "TODO: one sentence an operator reads before arming this Job.",
                List.of(
                        ParameterDecl.of("subject", ParamType.STRING)
                                .label("Subject")
                                .defaultValue("world")
                                .description("Try an Expression here, e.g. $today")
                                .build()),
                List.of("{{id}}.completed"),        // emits: Signal types
                List.of(),                          // artifacts
                List.of("notifications"));          // requires: Platform Services
    }

    @Override
    public Job create(JobConfig config) {
        return new {{className}}Job(config);
    }
}
