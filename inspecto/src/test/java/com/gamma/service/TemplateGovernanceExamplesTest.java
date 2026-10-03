package com.gamma.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.objects.EscalationRule;
import com.gamma.objects.SlaPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-WORKFLOW-SLA-1 (3): every Space Template ships example SLA policies and Escalation Rules as a labelled,
 * INERT markdown file ({@code config/examples/sla-escalation-examples.md}) — a template can never carry live
 * governance — and every example in it still passes the real parsers, so an example cannot rot into a refusal.
 */
class TemplateGovernanceExamplesTest {

    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void everyTemplateShipsLabelledExamplesThatTheRealParsersAccept() throws Exception {
        Path templates = Path.of("..", "spaces", "_templates").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(templates), "found NO spaces/_templates tree");
        List<Path> dirs;
        try (Stream<Path> s = Files.list(templates)) { dirs = s.filter(Files::isDirectory).sorted().toList(); }
        assertFalse(dirs.isEmpty());
        for (Path t : dirs) {
            Path doc = t.resolve("config/examples/sla-escalation-examples.md");
            assertTrue(Files.isRegularFile(doc), "" + t.getFileName() + " ships no SLA examples");
            String md = Files.readString(doc);
            assertTrue(md.startsWith("# EXAMPLE"), t.getFileName() + ": must be labelled as an example");
            assertTrue(md.contains("not shipped configuration"), "" + t.getFileName());
            Matcher m = JSON_BLOCK.matcher(md);
            int policies = 0, rules = 0;
            while (m.find()) {
                Map<String, Object> body = JSON.readValue(m.group(1), Map.class);
                String id = String.valueOf(body.get("id"));
                if (body.containsKey("targets")) {
                    SlaPolicy.fromComponent(id, body);
                    policies++;
                } else {
                    EscalationRule.fromComponent(id, body);
                    rules++;
                }
            }
            assertEquals(2, policies, t.getFileName() + ": an Incident and a Case policy");
            assertTrue(rules >= 1, "" + t.getFileName());
        }
    }
}
