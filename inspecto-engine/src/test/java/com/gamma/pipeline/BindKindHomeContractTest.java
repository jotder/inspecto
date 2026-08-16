package com.gamma.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bind-kind contract (AUTHOR-1(b) residual): the Angular editor decides whether to render a
 * component picker from {@code bindKindFor(category)} — keyed on a node's <b>category</b> — while the
 * save path decides whether a {@code use:} ref has anywhere to land from
 * {@link PipelineEditable#typesWithUseHome()} — keyed on its <b>type</b>. The two agree today only
 * because {@link NodeCategory#PARSE} happens to hold exactly one type, {@code parser}. Nothing forced
 * that, and when it stopped being true the picker offered options every save refused with
 * {@code UNSUPPORTED_BINDING} — an affordance whose every outcome was a failure.
 *
 * <p>This pins the derivation both sides depend on into one committed artifact, the
 * {@link StepTypesContractTest} idiom: a category is <b>bindable</b> when every builtin node type in it
 * has a {@code use:} home. {@code pipeline-graph.contract.spec.ts} reads the same file and asserts the
 * picker never appears on a category absent from that list.
 *
 * <p>⚠ The relation is deliberately <b>one-way</b>. A home is mandatory for a picker; a picker is not
 * mandatory for a home — {@code SOURCE} would be bindable if {@code adapter} gained one, yet a
 * Connection is not a {@code ComponentType} at all (no {@code GET /components/connection}), so the
 * collector component owns that picker. Requiring the converse would demand a picker for a ref the UI
 * has no registry to populate from.
 *
 * <p>Only builtins are pinned: plugin-contributed types vary by classpath, and a plugin type in a
 * bindable category would silently widen the set. Regenerate deliberately with
 * {@code -Dbind.kinds.write=true}, then check the TS suite still passes.
 */
class BindKindHomeContractTest {

    /** Shared with the TS side, so the editor's picker cannot drift from the save path's homes. */
    private static final String CONTRACT = "inspecto-ui/src/app/inspecto/mock/bind-kinds.contract.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static Path contractPath() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(CONTRACT);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("cannot locate " + CONTRACT + " from " + Path.of("").toAbsolutePath());
    }

    /** A category is bindable when EVERY builtin type in it has a {@code use:} home — one homeless type
     *  is enough to make the picker a dead end for the author who lands on it. */
    private static List<String> bindableCategories() {
        List<String> out = new ArrayList<>();
        for (NodeCategory category : NodeCategory.values()) {
            List<BuiltinNodeType> types = types(category);
            if (!types.isEmpty() && types.stream().allMatch(t -> PipelineEditable.typesWithUseHome().contains(t.type())))
                out.add(category.name());
        }
        return out;
    }

    private static List<BuiltinNodeType> types(NodeCategory category) {
        return java.util.Arrays.stream(BuiltinNodeType.values()).filter(t -> t.category() == category).toList();
    }

    private static Map<String, Object> published() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", java.util.Arrays.stream(NodeCategory.values()).map(Enum::name).toList());
        out.put("bindableCategories", bindableCategories());
        return out;
    }

    @Test
    void theBindableCategoriesMatchTheCommittedContract() throws IOException {
        String actual = JSON.writeValueAsString(published()).replace("\r\n", "\n").trim();

        if (Boolean.getBoolean("bind.kinds.write")) {
            Files.writeString(contractPath(), actual + "\n");
            return;
        }

        String expected = Files.readString(contractPath()).replace("\r\n", "\n").trim();
        assertEquals(expected, actual,
                "the node categories whose every type has a `use:` home and " + CONTRACT + " disagree. "
                        + "If the Java side is right, regenerate with -Dbind.kinds.write=true and check the "
                        + "TS suite still passes; if a NEW bind kind is wanted in the UI, give its types a "
                        + "home in PipelineEditable.USE_HOME first.");
    }

    /** The regression this exists for, stated rather than left to the artifact: the transform and sink
     *  families are NOT bindable, which is why {@code bindKindFor} answers null for them. */
    @Test
    void neitherTransformNorSinkIsBindable() {
        assertFalse(bindableCategories().contains(NodeCategory.TRANSFORM.name()));
        assertFalse(bindableCategories().contains(NodeCategory.SINK.name()));
        for (NodeCategory category : List.of(NodeCategory.TRANSFORM, NodeCategory.SINK))
            for (BuiltinNodeType t : types(category))
                assertFalse(PipelineEditable.typesWithUseHome().contains(t.type()),
                        t.type() + " gained a use: home — the UI picker rule must be revisited, not just this test");
    }

    /** PARSE is bindable only while EVERY type in it is homed. The tripwire has fired four times,
     *  correctly: {@code parser.delimited} (P3a), {@code parser.fixedwidth} (P3b), {@code parser.asn1}
     *  (P3c) and the {@code parser.json}/{@code parser.text_regex} pair (P3d) each arrived WITH a
     *  {@code grammar/} home, so the category stays bindable and this pins the whole family. A parse
     *  type added without a home must flip {@code bindKindFor('PARSE')} to null, and this test is
     *  where that shows up first. */
    @Test
    void parseIsBindableAndEveryParseTypeIsHomed() {
        assertTrue(bindableCategories().contains(NodeCategory.PARSE.name()));
        assertEquals(
                List.of(BuiltinNodeType.PARSER, BuiltinNodeType.PARSER_DELIMITED,
                        BuiltinNodeType.PARSER_FIXEDWIDTH, BuiltinNodeType.PARSER_ASN1,
                        BuiltinNodeType.PARSER_JSON, BuiltinNodeType.PARSER_TEXT_REGEX),
                types(NodeCategory.PARSE));
        for (BuiltinNodeType t : types(NodeCategory.PARSE))
            assertTrue(PipelineEditable.typesWithUseHome().contains(t.type()),
                    t.type() + " lost its use: home — the PARSE picker would offer refused saves");
    }
}
