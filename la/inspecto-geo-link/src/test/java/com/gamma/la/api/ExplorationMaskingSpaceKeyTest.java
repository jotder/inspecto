package com.gamma.la.api;

import com.gamma.entitystore.MaskTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BI-QUERY-MASKING-1: the exploration alias of an id is the token under the Space's ONE identity key
 * ({@link MaskTokens#spaceKey}), which BI result masking also uses, so a masked key in a Widget is the same alias as in
 * a Link Analysis read and an Entity List.
 */
class ExplorationMaskingSpaceKeyTest {

    @Test
    void theExplorationAliasIsTheSpaceKeyToken(@TempDir Path root) throws Exception {
        String alias = ExplorationMasking.alias(root, "441700900101");
        assertEquals(MaskTokens.token(MaskTokens.spaceKey(root), "441700900101"), alias);
        assertTrue(alias.startsWith("masked:") && alias.length() == 7 + 16, alias);
        assertTrue(Files.isRegularFile(com.gamma.util.SpaceSecretKeys.keyFile(root, ".risk-score-mask.key")),
                "the Space key lives in the secrets directory beside the root, outside every exported tree");
    }

    @Test
    void anotherSpaceHasAnotherKey(@TempDir Path a, @TempDir Path b) {
        assertTrue(!ExplorationMasking.alias(a, "x").equals(ExplorationMasking.alias(b, "x")), "keys are per Space");
    }
}
