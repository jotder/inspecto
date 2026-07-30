package com.gamma.asn.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 5 fuzz/property tests (REDESIGN.md §6): random TLV round-trips, and mutated or
 * truncated inputs must error — never mis-parse silently, never crash with anything but
 * {@link BerParseException}, never hang. Seeds are fixed so failures reproduce.
 */
class BerFuzzTest {

    private static final int TREES = 500;

    // ---- generator: random tree → BER bytes, keeping the expected shape ----------------

    private sealed interface Node permits Leaf, Branch {
    }

    private record Leaf(TagClass cls, long tag, byte[] value) implements Node {
    }

    private record Branch(TagClass cls, long tag, boolean indefinite, List<Node> children) implements Node {
    }

    private static Node randomNode(Random rnd, int depth) {
        TagClass cls = TagClass.values()[rnd.nextInt(4)];
        // tag 0 short-form encodes byte 0x00 = EOC; the reader rightly rejects it inside
        // definite constructed values, so the generator starts at 1 like real schemas do
        long tag = switch (rnd.nextInt(3)) {
            case 0 -> 1 + rnd.nextInt(30);            // short form
            case 1 -> 31 + rnd.nextInt(1000);         // long form, 1-2 base-128 digits
            default -> 1L << (7 * (2 + rnd.nextInt(6))); // deep long form up to 56 bits
        };
        if (depth >= 4 || rnd.nextInt(3) > 0) {
            int len = switch (rnd.nextInt(3)) {
                case 0 -> rnd.nextInt(8);
                case 1 -> 120 + rnd.nextInt(16);      // straddles the short/long length edge
                default -> 300 + rnd.nextInt(100);    // 2-byte length
            };
            byte[] value = new byte[len];
            rnd.nextBytes(value);
            return new Leaf(cls, tag, value);
        }
        List<Node> children = new ArrayList<>();
        int n = rnd.nextInt(4);
        for (int i = 0; i < n; i++) {
            children.add(randomNode(rnd, depth + 1));
        }
        return new Branch(cls, tag, rnd.nextBoolean(), children);
    }

    private static void encode(Node node, ByteArrayOutputStream out) {
        TagClass cls;
        long tag;
        boolean constructed = node instanceof Branch;
        if (node instanceof Leaf l) {
            cls = l.cls();
            tag = l.tag();
        } else {
            cls = ((Branch) node).cls();
            tag = ((Branch) node).tag();
        }
        int first = (cls.ordinal() << 6) | (constructed ? 0x20 : 0);
        if (tag < 31) {
            out.write(first | (int) tag);
        } else {
            out.write(first | 0x1F);
            int digits = (63 - Long.numberOfLeadingZeros(tag)) / 7;
            for (int i = digits; i >= 1; i--) {
                out.write(0x80 | (int) ((tag >>> (7 * i)) & 0x7F));
            }
            out.write((int) (tag & 0x7F));
        }
        if (node instanceof Leaf l) {
            writeLength(l.value().length, out);
            out.writeBytes(l.value());
            return;
        }
        Branch b = (Branch) node;
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (Node child : b.children()) {
            encode(child, content);
        }
        if (b.indefinite()) {
            out.write(0x80);
            out.writeBytes(content.toByteArray());
            out.write(0x00);
            out.write(0x00);
        } else {
            writeLength(content.size(), out);
            out.writeBytes(content.toByteArray());
        }
    }

    private static void writeLength(int len, ByteArrayOutputStream out) {
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        }
    }

    private static void assertSameShape(Node expected, Tlv actual, ByteSource src) {
        if (expected instanceof Leaf l) {
            assertEquals(l.cls(), actual.tagClass());
            assertEquals(l.tag(), actual.tagNumber());
            assertTrue(!actual.constructed());
            assertArrayEquals(l.value(), actual.value(src));
            return;
        }
        Branch b = (Branch) expected;
        assertEquals(b.cls(), actual.tagClass());
        assertEquals(b.tag(), actual.tagNumber());
        assertTrue(actual.constructed());
        assertEquals(b.indefinite(), actual.indefinite());
        assertEquals(b.children().size(), actual.children().size());
        for (int i = 0; i < b.children().size(); i++) {
            assertSameShape(b.children().get(i), actual.children().get(i), src);
        }
    }

    // ---- properties ---------------------------------------------------------------------

    @Test
    void randomTreesRoundTrip() {
        Random rnd = new Random(20260729);
        for (int i = 0; i < TREES; i++) {
            Node tree = randomNode(rnd, 0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encode(tree, out);
            byte[] bytes = out.toByteArray();
            ByteSource src = ByteSource.of(bytes);
            Tlv tlv = BerReader.read(src, 0, bytes.length, Strictness.BER);
            assertEquals(bytes.length, tlv.endOffset(), "seed tree " + i);
            assertSameShape(tree, tlv, src);
        }
    }

    @Test
    void everyTruncationErrorsNeverMisparses() {
        Random rnd = new Random(42);
        for (int i = 0; i < 50; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encode(randomNode(rnd, 0), out);
            byte[] bytes = out.toByteArray();
            for (int cut = 0; cut < bytes.length; cut++) {
                byte[] prefix = Arrays.copyOf(bytes, cut);
                final int c = cut;
                assertThrows(BerParseException.class,
                        () -> BerReader.read(ByteSource.of(prefix), 0, prefix.length, Strictness.BER),
                        "tree " + i + " truncated at " + c + " parsed anyway");
            }
        }
    }

    @Test
    void mutatedBytesErrorCleanlyOrParseWithinBounds() {
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            encode(randomNode(rnd, 0), out);
            byte[] bytes = out.toByteArray();
            for (int m = 0; m < 20; m++) {
                byte[] mutated = bytes.clone();
                mutated[rnd.nextInt(mutated.length)] ^= (byte) (1 + rnd.nextInt(255));
                try {
                    Tlv t = BerReader.read(ByteSource.of(mutated), 0, mutated.length, Strictness.BER);
                    assertTrue(t.endOffset() <= mutated.length,
                            "tree " + i + " mutation " + m + " read past the buffer");
                } catch (BerParseException expected) {
                    // clean structured error is the other allowed outcome
                } catch (RuntimeException e) {
                    fail("tree " + i + " mutation " + m + " leaked " + e, e);
                }
            }
        }
    }
}
