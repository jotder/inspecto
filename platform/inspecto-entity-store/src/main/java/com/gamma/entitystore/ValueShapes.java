package com.gamma.entitystore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The <b>value shapes</b> an Entity Type may name (ENTITY-TYPE-CATALOG-1 Q-4): what one of its values looks like, so Add data
 * can propose the type from sampled values and the "what does this column hold" picker can mark a type whose shape the
 * column's sample fits. ⛔ <b>A CLOSED vocabulary</b>, never a free regular expression in TOON (a ReDoS and parity risk):
 * <pre>
 *   free          anything (the default: no shape is checked)
 *   e164          a phone number: + and 8 to 15 digits (strong), or 8 to 15 bare digits (weak: digits alone could be anything)
 *   digits(n)     exactly n digits;          digits(a-b)  a to b digits            (1 &lt;= a &lt;= b &lt;= 34) - always WEAK:
 *                 digits alone could be another number of that length
 *   luhn(n)       n digits with a valid Luhn check digit (a card or device serial); luhn(a-b) likewise
 *   iban          an IBAN with a valid mod-97 check (ASCII spaces ignored)
 *   email         one '@' between a non-empty local part and a dotted domain, no whitespace
 *   uuid          8-4-4-4-12 hex digits
 * </pre>
 * Every matcher is ASCII-only and implemented the same way in {@code value-shape.ts}; the shared fixture
 * {@code entity-shape-parity.fixture.json} feeds both sides (the normaliser parity idea).
 */
public final class ValueShapes {

    private ValueShapes() {}

    /** How a value fits a shape. */
    public enum Fit { NONE, WEAK, STRONG }

    /** The shape names, in the order a reader is told them. */
    public static final List<String> NAMES = List.of("free", "e164", "digits", "luhn", "iban", "email", "uuid");

    private static final Pattern SIZED = Pattern.compile("^(digits|luhn)\\((\\d{1,2})(?:-(\\d{1,2}))?\\)$");
    private static final int MAX_DIGITS = 34;

    /** {@code shape} when it is one of the vocabulary, else {@link IllegalArgumentException} naming the vocabulary. */
    public static String validate(String shape) {
        if (shape == null) throw new IllegalArgumentException("a value shape is required");
        switch (shape) {
            case "free", "e164", "iban", "email", "uuid" -> {
                return shape;
            }
            default -> {
                Matcher m = SIZED.matcher(shape);
                if (m.matches()) {
                    int a = Integer.parseInt(m.group(2));
                    int b = m.group(3) == null ? a : Integer.parseInt(m.group(3));
                    if (a >= 1 && a <= b && b <= MAX_DIGITS) return shape;
                }
                throw new IllegalArgumentException("value shape must be one of free, e164, digits(n), digits(a-b), luhn(n), luhn(a-b), "
                        + "iban, email, uuid (1 <= a <= b <= " + MAX_DIGITS + "), got '" + shape + "'");
            }
        }
    }

    /** {@code true} for a shape that checks nothing ({@code null} or {@code free}). */
    public static boolean isFree(String shape) {
        return shape == null || shape.equals("free");
    }

    /**
     * How specific {@code shape} is, higher = narrower: when two types' shapes fit a sample equally, the narrower one is
     * proposed (a Luhn-checked 15-digit serial over 15 plain digits, plain digits over a phone number).
     */
    public static int specificity(String shape) {
        if (isFree(shape)) return 0;
        boolean range = shape.contains("-");   // digits(a-b), luhn(a-b): a set length is narrower than a range
        if (shape.startsWith("luhn(")) return range ? 7 : 8;
        return switch (shape) {
            case "iban" -> 6;
            case "email" -> 5;
            case "uuid" -> 4;
            case "e164" -> 1;
            default -> range ? 2 : 3; // digits(...)
        };
    }

    /** How {@code value} fits {@code shape} (a valid shape; {@code free} fits nothing, it checks nothing). */
    public static Fit fit(String shape, String value) {
        if (isFree(shape) || value == null) return Fit.NONE;
        String v = trimAscii(value);
        switch (shape) {
            case "e164": {
                String s = strip(v, " -().");
                if (s.length() > 1 && s.charAt(0) == '+' && digitsBetween(s.substring(1), 8, 15)) return Fit.STRONG;
                return digitsBetween(s, 8, 15) ? Fit.WEAK : Fit.NONE;
            }
            case "iban": {
                String s = upperAscii(strip(v, " "));
                return isIban(s) ? Fit.STRONG : Fit.NONE;
            }
            case "email":
                return isEmail(v) ? Fit.STRONG : Fit.NONE;
            case "uuid":
                return isUuid(v) ? Fit.STRONG : Fit.NONE;
            default: {
                Matcher m = SIZED.matcher(shape);
                if (!m.matches()) throw new IllegalArgumentException("not a value shape: '" + shape + "'");
                int a = Integer.parseInt(m.group(2));
                int b = m.group(3) == null ? a : Integer.parseInt(m.group(3));
                if (!digitsBetween(v, a, b)) return Fit.NONE;
                if (m.group(1).equals("digits")) return Fit.WEAK;
                return luhn(v) ? Fit.STRONG : Fit.NONE;
            }
        }
    }

    /** {@code true} when {@code value} fits {@code shape} at all (weak or strong). */
    public static boolean fits(String shape, String value) {
        return fit(shape, value) != Fit.NONE;
    }

    /** What values of {@code shape} are, for evidence ("all 12 sampled values are …"); {@code strong} false = the weak reading. */
    public static String describe(String shape, boolean strong) {
        if (isFree(shape)) return "values of any shape";
        switch (shape) {
            case "e164":
                return strong ? "phone numbers written + and 8 to 15 digits" : "8 to 15 digits long, like phone numbers";
            case "iban":
                return "IBANs with a valid check";
            case "email":
                return "e-mail addresses";
            case "uuid":
                return "UUIDs";
            default: {
                Matcher m = SIZED.matcher(shape);
                if (!m.matches()) return shape;
                String n = m.group(3) == null ? m.group(2) + "-digit numbers" : "numbers of " + m.group(2) + " to " + m.group(3) + " digits";
                return m.group(1).equals("luhn") ? n + " with a valid check digit" : n;
            }
        }
    }

    // ── matchers: ASCII only, the same steps as value-shape.ts ─────────────────────────────────────────────

    private static boolean isAsciiSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    static String trimAscii(String s) {
        int a = 0, b = s.length();
        while (a < b && isAsciiSpace(s.charAt(a))) a++;
        while (b > a && isAsciiSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static String strip(String s, String chars) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) < 0) out.append(s.charAt(i));
        return out.toString();
    }

    private static String upperAscii(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean digitsBetween(String s, int min, int max) {
        if (s.length() < min || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++) if (!isDigit(s.charAt(i))) return false;
        return true;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static boolean isIban(String s) {
        if (s.length() < 15 || s.length() > 34) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letter = c >= 'A' && c <= 'Z';
            if (i < 2 ? !letter : i < 4 ? !isDigit(c) : !(letter || isDigit(c))) return false;
        }
        String r = s.substring(4) + s.substring(0, 4);
        int mod = 0;
        for (int i = 0; i < r.length(); i++) {
            char ch = r.charAt(i);
            int v = isDigit(ch) ? ch - '0' : ch - 'A' + 10;
            mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
        }
        return mod == 1;
    }

    private static boolean isEmail(String s) {
        int at = s.indexOf('@');
        if (at <= 0 || at != s.lastIndexOf('@')) return false;
        for (int i = 0; i < s.length(); i++) if (isAsciiSpace(s.charAt(i))) return false;
        String domain = s.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        return dot > 0 && dot < domain.length() - 1;
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) return false;
        for (int i = 0; i < 36; i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
            } else if (!isHex(c)) return false;
        }
        return true;
    }
}
