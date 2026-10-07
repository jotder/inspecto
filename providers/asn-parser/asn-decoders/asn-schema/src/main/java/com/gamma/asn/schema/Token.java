package com.gamma.asn.schema;

public record Token(Token.Kind kind, String text, int line, int col) {

    public enum Kind {
        IDENT,      // identifiers, type references, and all keywords
        NUMBER,
        ASSIGN,     // ::=
        LBRACE, RBRACE,
        LBRACKET, RBRACKET,
        LPAREN, RPAREN,
        COMMA, SEMICOLON, DOT, RANGE, ELLIPSIS,
        SYMBOL,     // -, |, <, >, !, ^, etc. — appear inside constraints and values
        VSTRING,    // '...'B / '...'H / "..." value literals, kept verbatim
        EOF
    }

    public boolean is(Kind k) {
        return kind == k;
    }

    /**
     * Keyword check. Case-insensitive on purpose: the hand-doctored grammars in config/
     * contain lowercased keywords like {@code octet string}.
     */
    public boolean isWord(String word) {
        return kind == Kind.IDENT && text.equalsIgnoreCase(word);
    }
}
