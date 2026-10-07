package com.gamma.job;

import java.util.List;

/**
 * The {@code mail.send} Job Type (job-parameter-contract §9) — the reference declaration for the
 * parameter contract.
 *
 * <p>Its {@link #DESCRIPTOR} is the worked example verbatim: {@code to}/{@code cc} are {@code EMAIL}
 * {@code multi} fields grouped under <b>Recipients</b>, {@code subject} and {@code body} sit under
 * <b>Message</b>, and {@code body} is {@code TEXT} so it renders as a textarea. Nothing in the UI knows
 * this type by name — the form is generated from these four declarations, which is exactly the claim the
 * contract makes and the reason this type exists.
 *
 * <p>⚠ It declares {@code requires: [mail]}, which §9's sketch omitted. A Job that emails the outside
 * world must declare that reach: the grant is validated fail-closed at registration, and an operator sees
 * what a Job can do before arming it. Leaving it off would have made the reference example the one Job
 * type that reaches outward without saying so.
 */
final class MailSendJobType implements JobTypeProvider {

    static final JobTypeDescriptor DESCRIPTOR = new JobTypeDescriptor("mail.send", "Send Mail",
            "Composes and sends an email to the configured recipients.",
            List.of(
                    ParameterDecl.of("to", ParamType.EMAIL).label("To").tier(ParameterDecl.Tier.REQUIRED)
                            .multi().group("Recipients")
                            .description("Recipient addresses").build(),
                    ParameterDecl.of("cc", ParamType.EMAIL).label("Cc").tier(ParameterDecl.Tier.OPTIONAL)
                            .multi().group("Recipients")
                            .description("Additional recipients").build(),
                    ParameterDecl.of("subject", ParamType.STRING).label("Subject")
                            .tier(ParameterDecl.Tier.REQUIRED).group("Message")
                            .description("Message subject line").build(),
                    ParameterDecl.of("body", ParamType.TEXT).label("Body")
                            .tier(ParameterDecl.Tier.REQUIRED).group("Message")
                            .description("Message body").build()),
            List.of("mail.sent"),
            List.of(),
            List.of("mail"));

    @Override public JobTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override public Job create(JobConfig config) { return new MailSendJob(config); }
}
