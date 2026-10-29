package com.gamma.intelligence.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code docs_search} reads only user-facing docs (INTELLIGENCE-GLOSSARY-ANNOTATED-1): the root GLOSSARY / USER_GUIDE /
 * ADVANCED_GUIDE and the product hubs' {@code user-guide*} / {@code admin-guide*} / {@code api*} slots — never a
 * deployment README, {@code archived-documents/}, {@code superpower/} or {@code .claude/}.
 */
class DocsSearchTest {

    @Test
    void aKnownInternalReadmeLineIsNotReturned() {
        // The first line of docs/api/deployment/README.md: the deployment's gateway and identity-provider set-up.
        Path docs = RepoPaths.root().orElseThrow().resolve("docs");
        assertTrue(Files.isRegularFile(docs.resolve("api/deployment/README.md")), "fixture README moved");
        assertTrue(InspectoTools.search(docs, "WSO2 gateway + Keycloak realm blueprints").isEmpty());
        // ...while a glossary line is still found, and found first.
        List<Map<String, Object>> hits = InspectoTools.search(docs, "Hive-style root directory");
        assertFalse(hits.isEmpty());
        assertEquals("GLOSSARY.md", hits.get(0).get("file"));
        for (Map<String, Object> hit : InspectoTools.search(docs, "Keycloak")) {
            assertTrue(InspectoTools.isUserFacingDoc(Path.of((String) hit.get("file"))), String.valueOf(hit));
        }
    }

    @Test
    void onlyUserFacingDocsAreSearched(@TempDir Path docs) throws IOException {
        for (String p : List.of("GLOSSARY.md", "USER_GUIDE.md", "ADVANCED_GUIDE.md",
                "okf/product/module/la-core/user-guide-link-analysis.md", "okf/product/layer/product/admin-guide.md",
                "okf/product/module/access/api-access.md", "okf/product/module/access/api.md",
                // never:
                "api/deployment/README.md", "README.md", "BACKLOG.md", "archived-documents/USER_GUIDE.md",
                "archived-documents/okf/product/x/user-guide.md", "superpower/user-guide-plan.md",
                "okf/product/module/x/superpower/user-guide.md", ".claude/user-guide.md",
                "okf/product/module/la-core/spec-link.md", "okf/product/module/la-core/README.md",
                "okf/product/module/la-core/tests-user-guide.md", "okf/USER_GUIDE.md")) {
            Path f = docs.resolve(p);
            Files.createDirectories(f.getParent());
            Files.writeString(f, "intro\nthe zebra term lives here\n");
        }
        List<Object> files = InspectoTools.search(docs, "ZEBRA").stream().map(h -> h.get("file"))
                .map(f -> (Object) f.toString().replace('\\', '/')).toList();
        assertEquals(List.of("GLOSSARY.md", "USER_GUIDE.md", "ADVANCED_GUIDE.md",
                "okf/product/layer/product/admin-guide.md", "okf/product/module/access/api-access.md",
                "okf/product/module/access/api.md", "okf/product/module/la-core/user-guide-link-analysis.md"), files);
    }
}
