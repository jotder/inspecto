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
 *
 * <p>The artifact carries a second map, {@link PipelineEditable#derivedUseByType()}, pinned <b>verbatim</b>
 * rather than derived. The distinction matters: the mock re-declares {@code DERIVED_USE} as its own
 * literal, so a missing entry there is invisible until a real config trips it — and it drifted that way
 * three times before plain {@code parser} was found missing, which made a legacy
 * {@code processing.ingester} pipeline fail validate with {@code UNKNOWN_USE_KIND}.
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
        // Sorted, not insertion-ordered: DERIVED_USE is a Map.of(), whose iteration order is not stable
        // across JVMs — publishing it raw would rewrite this artifact on unrelated runs.
        out.put("derivedUse", new java.util.TreeMap<>(PipelineEditable.derivedUseByType()));
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

    /** PARSE is bindable only while EVERY type in it is homed. The tripwire has fired five times,
     *  correctly: {@code parser.delimited} (P3a), {@code parser.fixedwidth} (P3b), {@code parser.asn1}
     *  (P3c), the {@code parser.json}/{@code parser.text_regex} pair (P3d slice C) and
     *  {@code parser.plugin} (P3d slice D) each arrived WITH a home, so the category stays bindable and
     *  this pins the whole family. A parse type added without a home must flip
     *  {@code bindKindFor('PARSE')} to null, and this test is where that shows up first. */
    @Test
    void parseIsBindableAndEveryParseTypeIsHomed() {
        assertTrue(bindableCategories().contains(NodeCategory.PARSE.name()));
        assertEquals(
                List.of(BuiltinNodeType.PARSER, BuiltinNodeType.PARSER_DELIMITED,
                        BuiltinNodeType.PARSER_FIXEDWIDTH, BuiltinNodeType.PARSER_ASN1,
                        BuiltinNodeType.PARSER_JSON, BuiltinNodeType.PARSER_TEXT_REGEX,
                        BuiltinNodeType.PARSER_PLUGIN),
                types(NodeCategory.PARSE));
        for (BuiltinNodeType t : types(NodeCategory.PARSE))
            assertTrue(PipelineEditable.typesWithUseHome().contains(t.type()),
                    t.type() + " lost its use: home — the PARSE picker would offer refused saves");
    }

    /**
     * The {@code ingester/} rule stated once, rather than left to three independently-authored map
     * entries. {@link PipelineLift#parserNode} synthesizes {@code ingester/<fqcn>} from the ingester
     * CLASS key on any parser node that has one, and the retype to a subtype is explicit-only — so
     * every parse type that can reach that key must call the ref DERIVED. Getting this wrong is not a
     * cosmetic drift: the ref names no {@code ComponentRegistry} kind, so validate 422s an untouched
     * pipeline with {@code UNKNOWN_USE_KIND}. The three types below arrived in three separate changes
     * and the plain one was missed by both of the others.
     */
    @Test
    void everyParseTypeThatCanReachAnIngesterClassCallsItsRefDerived() {
        List<BuiltinNodeType> reachesIngesterClass = List.of(
                // the legacy `processing.ingester` key, on a node with no `parsing.frontend` literal
                BuiltinNodeType.PARSER,
                // `frontend: asn1` — the config parser synthesizes the Asn1RecordIngester binding
                BuiltinNodeType.PARSER_ASN1,
                // `frontend: plugin` — the same triple under `parsing.plugin`
                BuiltinNodeType.PARSER_PLUGIN);

        for (BuiltinNodeType t : types(NodeCategory.PARSE))
            assertEquals(reachesIngesterClass.contains(t) ? "ingester/" : null,
                    PipelineEditable.derivedUseByType().get(t.type()),
                    t.type() + ": a parse type that can reach an ingester CLASS key must call the lift's "
                            + "synthesized ref DERIVED (the ref names no ComponentRegistry kind, so "
                            + "validate 422s an untouched pipeline with UNKNOWN_USE_KIND); one that "
                            + "cannot must not, or a genuinely unhomed binding would be waved through");
    }
}
