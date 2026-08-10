package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mail.send}'s DECLARATION, pinned against the job-parameter-contract worked example (§9).
 *
 * <p>This type exists to prove one claim: that a form is generated from a declaration rather than coded
 * per Job Type. So the declaration itself is the artifact under test — if it drifts from §9, the worked
 * example stops being worked, and the UI has no way to notice because it never names this type.
 */
class MailSendJobTypeTest {

    private static ParameterDecl param(String name) {
        return MailSendJobType.DESCRIPTOR.parameters().stream()
                .filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("mail.send declares no `" + name + "` parameter"));
    }

    @Test
    void itDeclaresExactlyTheFourParametersOfTheWorkedExampleInOrder() {
        assertEquals(List.of("to", "cc", "subject", "body"),
                MailSendJobType.DESCRIPTOR.parameters().stream().map(ParameterDecl::name).toList(),
                "declaration ORDER is the form's field order — §9 renders Recipients before Message");
        assertEquals("mail.send", MailSendJobType.DESCRIPTOR.id());
        assertEquals("Send Mail", MailSendJobType.DESCRIPTOR.title());
    }

    @Test
    void recipientsAreMultiEmailFieldsGroupedTogether() {
        assertEquals(ParamType.EMAIL, param("to").type());
        assertEquals(ParamType.EMAIL, param("cc").type());
        assertTrue(param("to").multi(), "To is a list of addresses, not one address");
        assertTrue(param("cc").multi());
        assertEquals("Recipients", param("to").group());
        assertEquals("Recipients", param("cc").group());
    }

    @Test
    void toIsRequiredAndCcIsNot() {
        assertEquals(ParameterDecl.Tier.REQUIRED, param("to").tier());
        assertTrue(param("to").required(), "tier(REQUIRED) must also mark it required — the two agree or the "
                + "form demands a value the resolver does not");
        assertEquals(ParameterDecl.Tier.OPTIONAL, param("cc").tier());
        assertFalse(param("cc").required());
    }

    @Test
    void theMessageGroupCarriesASubjectLineAndAMultilineBody() {
        assertEquals("Message", param("subject").group());
        assertEquals("Message", param("body").group());
        assertEquals(ParamType.STRING, param("subject").type());
        assertEquals(ParamType.TEXT, param("body").type(),
                "TEXT is what makes Body a textarea — a STRING here would render single-line");
    }

    @Test
    void everyParameterCarriesAnExplicitLabel() {
        assertEquals("To", param("to").label());
        assertEquals("Cc", param("cc").label());
        assertEquals("Subject", param("subject").label());
        assertEquals("Body", param("body").label());
    }

    @Test
    void itDeclaresTheMailGrantItActuallyUses() {
        // §9's sketch omitted this. A Job that mails the outside world must declare that reach — the grant
        // is validated fail-closed at registration and shown to an operator before arming.
        assertEquals(List.of("mail"), MailSendJobType.DESCRIPTOR.requires());
        assertEquals(List.of("mail.sent"), MailSendJobType.DESCRIPTOR.emits());
    }

    @Test
    void theWholeDeclarationSurvivesTheWireAsTheUiWillReadIt() {
        // The UI generates the form from toMap() — a component that never reaches the wire cannot render.
        Map<String, Object> wire = MailSendJobType.DESCRIPTOR.toMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> params = (List<Map<String, Object>>) wire.get("parameters");

        Map<String, Object> to = params.get(0);
        assertEquals("to", to.get("name"));
        assertEquals("EMAIL", to.get("type"));
        assertEquals("To", to.get("label"));
        assertEquals("REQUIRED", to.get("tier"));
        assertEquals("Recipients", to.get("group"));
        assertEquals(true, to.get("multi"));
        assertEquals(true, to.get("expressions"), "a $-Expression must be authorable in a recipient field");
        assertEquals(List.of("mail"), wire.get("requires"));
    }

    @Test
    void itRegistersWhereTheMailServiceIsWired() {
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("mail", com.gamma.notify.MailAccess.class, (to, cc, subject, body) -> true);

        JobTypeRegistry wired = new JobTypeRegistry(platform);
        wired.register(new MailSendJobType());

        assertTrue(wired.has("mail.send"));
        assertEquals("Send Mail", wired.descriptor("mail.send").orElseThrow().title());
    }

    @Test
    void aBareRegistryStillTakesItBecauseItIsABuiltIn() {
        // NOT the pack/classpath rule. `register(provider)` is the BUILT-IN path, and S1-7 has it tolerate
        // an unsatisfiable requires on purpose: a built-in's service ships in the same build, so a lean or
        // embedded JobService with no platform registry wired must still register it — absent host wiring
        // is not the same fact as an unknown service id. A pack declaring `mail` would still be refused.
        JobTypeRegistry bare = new JobTypeRegistry();
        bare.register(new MailSendJobType());

        assertTrue(bare.has("mail.send"));
        assertEquals(List.of("mail"), bare.descriptor("mail.send").orElseThrow().requires(),
                "the declaration stays honest even where nothing can satisfy it");
    }
}
