package com.gamma.asn.transform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser (REDESIGN.md §4.4 — JDK only, Jackson is gone). Produces
 * LinkedHashMap / ArrayList / String / Long / Double / Boolean / null. Insertion order
 * preserved — config key order is semantically relevant to the transformer.
 */
public final class Json {

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos < text.length()) {
            throw p.err("trailing content");
        }
        return v;
    }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> bool();
            case 'n' -> nullValue();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') {
            pos++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw err("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> l = new ArrayList<>();
        ws();
        if (peek() == ']') {
            pos++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw err("expected ',' or ']'");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw err("bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object bool() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw err("bad literal");
    }

    private Object nullValue() {
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw err("bad literal");
    }

    private Object number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String n = text.substring(start, pos);
        if (n.isEmpty()) {
            throw err("unexpected character '" + peek() + "'");
        }
        if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) {
            return Double.parseDouble(n);
        }
        return Long.parseLong(n);
    }

    private void ws() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw err("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            pos--;
            throw err("expected '" + c + "'");
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON error at offset " + pos + ": " + msg);
    }
}
