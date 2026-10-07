package com.gamma.parse.testkit;

import com.gamma.config.spec.FieldSpec;
import com.gamma.parse.ParseResult;
import com.gamma.parse.ParserPlugin;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The platform's TCK for a {@link ParserPlugin} (MODULE-REORG-1 P5b): what {@code ParserRoutes.preview} and the Pipeline's
 * {@code parsing.plugin} lookup assume of every parser. {@code ParserRoutes} turns any {@link Exception} into a 422, so a
 * parser may refuse a sample that way; what it may never do is throw an {@link Error} (a stack overflow on a deeply
 * nested sample is a denial of service on the control plane), hang, or return a half-built result.
 *
 * <p>Defects each test catches:
 * <ul>
 *   <li>{@link #idIsALowerCaseToken} / {@link #idIsServedByExactlyOneParser} - the id is the {@code parsing.plugin} key; a
 *       blank or mixed-case id is unreachable, a duplicate is resolved by jar order.</li>
 *   <li>{@link #grammarSchemaIsWellFormed} - a blank or duplicated field path makes the Parse drawer render the same
 *       input twice or not at all.</li>
 *   <li>{@link #theDeclaredIngesterResolves} - a plugin that advertises {@code ingesterClass()} for a class that is
 *       not on the classpath previews fine and then fails every load.</li>
 *   <li>{@link #hostileSamplesAreRefusedOrParsedNeverCrash} - empty, null, zero-filled, 0xFF-filled and 100 000-level
 *       nested samples must come back as a result or an {@link Exception} within 10 s: never an Error, never a hang.</li>
 *   <li>{@link #suggestNeverThrows} - the drawer calls {@code suggest} on every sample the operator drops in.</li>
 * </ul>
 */
public abstract class ParserPluginContract {

    private static final Pattern TOKEN = Pattern.compile("[a-z][a-z0-9_-]*");

    /** The plugin under test. */
    protected abstract ParserPlugin plugin();

    /** Samples a correct parser must survive, beyond the generic ones. */
    protected List<byte[]> extraHostileSamples() {
        return List.of();
    }

    /** Grammar the hostile samples are previewed with (default: none). */
    protected Map<String, Object> grammar() {
        return new LinkedHashMap<>();
    }

    /** Every parser the runtime would see; a seam only so the self-test can plant a duplicate. */
    protected Iterable<ParserPlugin> registered() {
        return ServiceLoader.load(ParserPlugin.class);
    }

    @Test
    void idIsALowerCaseToken() {
        String id = plugin().id();
        assertNotNull(id, "id()");
        assertTrue(TOKEN.matcher(id).matches(), "parser id '" + id + "' must match " + TOKEN);
        String label = plugin().label();
        assertTrue(label != null && !label.isBlank(), "a parser needs a label the Parse drawer can show");
    }

    @Test
    void idIsServedByExactlyOneParser() {
        String id = plugin().id();
        List<String> serving = new ArrayList<>();
        for (ParserPlugin p : registered())
            if (id.equalsIgnoreCase(p.id())) serving.add(p.getClass().getName());
        assertTrue(serving.size() <= 1, "parser id '" + id + "' is served by several plugins: " + serving);
    }

    @Test
    void grammarSchemaIsWellFormed() {
        List<FieldSpec> schema = plugin().grammarSchema();
        assertNotNull(schema, "grammarSchema()");
        Set<String> paths = new HashSet<>();
        for (FieldSpec f : schema) {
            assertFalse(f.path().isBlank(), "a grammar field needs a path");
            assertTrue(paths.add(f.path()), "grammar field '" + f.path() + "' is declared twice");
        }
    }

    @Test
    void theDeclaredIngesterResolves() {
        Optional<String> cls = plugin().ingesterClass();
        assertNotNull(cls, "ingesterClass() must return Optional.empty(), not null");
        cls.ifPresent(name -> assertDoesNotThrow(() -> Class.forName(name, false, plugin().getClass().getClassLoader()),
                "ingesterClass() names '" + name + "', which is not on the classpath"));
    }

    @Test
    void hostileSamplesAreRefusedOrParsedNeverCrash() {
        List<byte[]> samples = new ArrayList<>();
        samples.add(null);
        samples.add(new byte[0]);
        samples.add(new byte[4096]);
        byte[] ff = new byte[65_536];
        java.util.Arrays.fill(ff, (byte) 0xFF);
        samples.add(ff);
        samples.add(repeat("<a>".getBytes(java.nio.charset.StandardCharsets.UTF_8), 100_000));
        samples.add(repeat(new byte[]{0x30, (byte) 0x80}, 100_000));
        samples.addAll(extraHostileSamples());
        for (int i = 0; i < samples.size(); i++) {
            byte[] sample = samples.get(i);
            int n = i;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                try {
                    ParseResult r = plugin().preview(sample, grammar());
                    assertNotNull(r, "sample #" + n + ": preview() returned null - return a result or throw");
                } catch (Exception refused) {
                    // 422 in ParserRoutes: the allowed way to say no
                } catch (AssertionError failed) {
                    throw failed;
                } catch (Error crash) {
                    fail("sample #" + n + ": preview() threw " + crash + " - an Error escapes ParserRoutes' catch and is not a refusal", crash);
                }
            }, "sample #" + n + " hung the parser");
        }
    }

    @Test
    void suggestNeverThrows() {
        for (byte[] s : new byte[][]{new byte[0], new byte[512], "not a document".getBytes(java.nio.charset.StandardCharsets.UTF_8)}) {
            try {
                assertNotNull(plugin().suggest(s), "suggest() returned null (use Map.of())");
            } catch (Throwable t) {
                fail("suggest() threw " + t, t);
            }
        }
    }

    private static byte[] repeat(byte[] unit, int times) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(unit.length * times);
        for (int i = 0; i < times; i++) out.write(unit, 0, unit.length);
        return out.toByteArray();
    }
}
