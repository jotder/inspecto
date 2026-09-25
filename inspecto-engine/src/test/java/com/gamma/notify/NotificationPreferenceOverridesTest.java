package com.gamma.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.notify.NotificationPreferences.EMAIL;
import static com.gamma.notify.NotificationPreferences.IN_APP;
import static com.gamma.notify.NotificationPreferences.WEBHOOK;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-Subject override layer over the deployment-default grid (ses-sns-adapter-design §7, fixes F2):
 * {@code enabled(category, channel, subject)} is the Subject's override when there is one, else the
 * default; critical categories are locked on at both layers; email needs a verified address.
 */
class NotificationPreferenceOverridesTest {

    private static Map<String, Map<String, Boolean>> cell(String category, String channel, Boolean on) {
        Map<String, Boolean> ch = new HashMap<>();
        ch.put(channel, on);   // null = reset to the default
        return Map.of(category, ch);
    }

    @Test
    void anOverrideWinsOverTheDefaultForThatSubjectOnly() throws Exception {
        NotificationPreferences defaults = new NotificationPreferences();
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        o.apply("alice", null, cell("pipeline", IN_APP, false));

        assertFalse(o.enabled(defaults, "pipeline", IN_APP, "alice", null));
        assertTrue(o.enabled(defaults, "pipeline", IN_APP, "bob", null), "bob inherits the default");
        assertTrue(defaults.enabled("pipeline", IN_APP), "an override never writes the default grid");

        defaults.set("job", Map.of(IN_APP, false));
        assertFalse(o.enabled(defaults, "job", IN_APP, "alice", null), "no override ⇒ the default, live");
    }

    @Test
    void aNullCellResetsToTheDefault() throws Exception {
        NotificationPreferences defaults = new NotificationPreferences();
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        o.apply("alice", null, cell("pipeline", IN_APP, false));
        o.apply("alice", null, cell("pipeline", IN_APP, null));
        assertTrue(o.enabled(defaults, "pipeline", IN_APP, "alice", null));
        Map<String, Object> row = row(o.grid(defaults, "alice", null), "pipeline");
        assertEquals(NotificationPreferenceOverrides.INHERITED, source(row).get(IN_APP));
    }

    @Test
    void criticalCategoriesCannotBeOverriddenOff() throws Exception {
        NotificationPreferences defaults = new NotificationPreferences();
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        o.apply("alice", "alice@example.com", cell("security", IN_APP, false));
        o.apply("alice", "alice@example.com", cell("security", EMAIL, false));
        assertTrue(o.enabled(defaults, "security", IN_APP, "alice", "alice@example.com"));
        assertTrue(o.enabled(defaults, "security", EMAIL, "alice", "alice@example.com"));
        assertTrue(o.override("alice", "security", IN_APP).isEmpty(), "nothing was stored for a critical category");
    }

    @Test
    void withoutAVerifiedEmailOnlyInAppCanBeTurnedOn() throws Exception {
        NotificationPreferences defaults = new NotificationPreferences();
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        o.apply("bob", null, cell("pipeline", EMAIL, true));
        assertTrue(o.override("bob", "pipeline", EMAIL).isEmpty(), "email on without an address is refused");
        assertFalse(o.enabled(defaults, "pipeline", EMAIL, "bob", null));

        defaults.set("pipeline", Map.of(EMAIL, true));
        assertFalse(o.enabled(defaults, "pipeline", EMAIL, "bob", null),
                "a Subject with no email claim receives no email even when the default says so");
        Map<String, Object> row = row(o.grid(defaults, "bob", null), "pipeline");
        assertEquals(false, editable(row).get(EMAIL));
        assertEquals(true, editable(row).get(IN_APP));
    }

    @Test
    void webhookIsAnOperatorDestinationNotAPersonalOne() throws Exception {
        NotificationPreferences defaults = new NotificationPreferences();
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        o.apply("alice", "a@x.io", cell("pipeline", WEBHOOK, true));
        assertTrue(o.override("alice", "pipeline", WEBHOOK).isEmpty());
        assertEquals(false, editable(row(o.grid(defaults, "alice", "a@x.io"), "pipeline")).get(WEBHOOK));
    }

    @Test
    void enrolledSubjectsCarryOnlyTheirVerifiedAddress() throws Exception {
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        o.apply("alice", "alice@example.com", Map.of());
        o.apply("bob", null, Map.of());
        assertEquals(List.of(new NotificationPreferenceOverrides.Enrolled("alice", "alice@example.com")), o.enrolled());
        o.apply("alice", null, Map.of());   // the claim went away at the IdP
        assertTrue(o.enrolled().isEmpty(), "the stored address follows the claim — it is never kept past it");
    }

    @Test
    void anyEnablesSeesOverridesTheDefaultDoesNot() throws Exception {
        NotificationPreferences defaults = new NotificationPreferences();
        defaults.set("pipeline", Map.of(IN_APP, false));
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.inMemory();
        assertFalse(o.anyEnables("pipeline", IN_APP));
        o.apply("alice", null, cell("pipeline", IN_APP, true));
        assertTrue(o.anyEnables("pipeline", IN_APP));
    }

    @Test
    void overridesSurviveAReopenOfTheDeploymentFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(NotificationPreferenceOverrides.FILE);
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.open(file);
        assertFalse(Files.exists(file), "nothing is written until a Subject saves");
        String odd = "auth0|a,b: c";   // a stable IdP id with TOON-significant characters
        o.apply(odd, "o@example.com", cell("pipeline", IN_APP, false));
        o.apply("bob", null, cell("job", IN_APP, false));
        assertTrue(Files.exists(file));

        NotificationPreferenceOverrides again = NotificationPreferenceOverrides.open(file);
        assertEquals(java.util.Optional.of(false), again.override(odd, "pipeline", IN_APP));
        assertEquals(java.util.Optional.of(false), again.override("bob", "job", IN_APP));
        assertEquals(List.of(new NotificationPreferenceOverrides.Enrolled(odd, "o@example.com")), again.enrolled());
    }

    @Test
    void anUnreadableFileIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(NotificationPreferenceOverrides.FILE);
        Files.writeString(file, "subjects[2]{id,email:\n  broken");
        NotificationPreferenceOverrides o = NotificationPreferenceOverrides.open(file);
        assertThrows(IllegalStateException.class, () -> o.apply("alice", null, cell("pipeline", IN_APP, false)));
        assertEquals("subjects[2]{id,email:\n  broken", Files.readString(file), "another user's data is not clobbered");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> source(Map<String, Object> row) {
        return (Map<String, Object>) row.get("source");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> editable(Map<String, Object> row) {
        return (Map<String, Object>) row.get("editable");
    }

    private static Map<String, Object> row(List<Map<String, Object>> grid, String category) {
        return grid.stream().filter(r -> category.equals(r.get("category"))).findFirst().orElseThrow();
    }
}
