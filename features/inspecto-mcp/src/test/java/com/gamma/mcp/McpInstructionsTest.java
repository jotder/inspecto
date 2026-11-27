package com.gamma.mcp;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MCP server's {@code instructions} (AGENT-ANSWERS-WITHOUT-SERVER-1): they tell the agent to ask the server for
 * casts and what a draft does, and every tool they name is a real tool — an instruction naming a tool that does not
 * exist sends the agent nowhere.
 */
class McpInstructionsTest {

    @Test
    void theInstructionsSendCastQuestionsToTheServer() {
        String s = McpRoutes.INSTRUCTIONS;
        assertTrue(s.contains("casts"), s);
        assertTrue(s.contains("preview_config"), s);
        assertTrue(s.contains("dry_run_pipeline"), s);
        assertTrue(s.contains("never work it out yourself"), s);
    }

    @Test
    void everyToolTheInstructionsNameExists() {
        Set<String> tools = McpTools.ALL_TOOLS.stream().map(McpTools.Tool::name).collect(Collectors.toSet());
        // snake_case words with at least one underscore are tool names in this text
        Matcher m = Pattern.compile("\\b[a-z]+(?:_[a-z]+)+\\b").matcher(McpRoutes.INSTRUCTIONS);
        int named = 0;
        while (m.find()) {
            named++;
            assertTrue(tools.contains(m.group()), "the instructions name '" + m.group() + "', which is no MCP Tool");
        }
        assertTrue(named >= 5, "expected the instructions to name tools; found " + named);
    }
}
