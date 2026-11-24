package com.gamma.asn.schema;

import java.util.ArrayList;
import java.util.List;

/**
 * X.680 lexer: identifiers/keywords, numbers, {@code ::=}, punctuation, {@code --} comments
 * (ending at the next {@code --} or end of line), nested {@code /* *&#47;} comments, and
 * quoted value literals. Line breaks are plain whitespace — no one-definition-per-line
 * assumption.
 */
final class Tokenizer {

    private final String text;
    private final boolean lenient;
    private final List<String> warnings;
    private final List<Diagnostic> diagnostics;
    private int pos;
    private int line = 1;
    private int col = 1;

    private Tokenizer(String text, boolean lenient, List<String> warnings, List<Diagnostic> diagnostics) {
        this.text = text;
        this.lenient = lenient;
        this.warnings = warnings;
        this.diagnostics = diagnostics;
    }

    static List<Token> tokenize(String text) {
        return new Tokenizer(text, false, null, null).run();
    }

    static List<Token> tokenizeLenient(String text, List<String> warnings, List<Diagnostic> diagnostics) {
        return new Tokenizer(text, true, warnings, diagnostics).run();
    }

    private void warn(int warnLine, int warnCol, String message) {
        if (warnings != null) {
            warnings.add(message);
        }
        if (diagnostics != null) {
            diagnostics.add(new Diagnostic(warnLine, warnCol, "WARNING", message));
        }
    }

    private List<Token> run() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= text.length()) {
                out.add(new Token(Token.Kind.EOF, "", line, col));
                return out;
            }
            int startLine = line;
            int startCol = col;
            char c = text.charAt(pos);
            if (Character.isLetter(c)) {
                out.add(new Token(Token.Kind.IDENT, readWord(), startLine, startCol));
            } else if (Character.isDigit(c)) {
                out.add(new Token(Token.Kind.NUMBER, readNumber(), startLine, startCol));
            } else if (c == ':' && text.startsWith("::=", pos)) {
                advance(3);
                out.add(new Token(Token.Kind.ASSIGN, "::=", startLine, startCol));
            } else if (c == '.' && text.startsWith("...", pos)) {
                advance(3);
                out.add(new Token(Token.Kind.ELLIPSIS, "...", startLine, startCol));
            } else if (c == '.' && text.startsWith("..", pos)) {
                advance(2);
                out.add(new Token(Token.Kind.RANGE, "..", startLine, startCol));
            } else if (c == '\'' || c == '"') {
                out.add(new Token(Token.Kind.VSTRING, readQuoted(c), startLine, startCol));
            } else {
                Token.Kind kind = switch (c) {
                    case '{' -> Token.Kind.LBRACE;
                    case '}' -> Token.Kind.RBRACE;
                    case '[' -> Token.Kind.LBRACKET;
                    case ']' -> Token.Kind.RBRACKET;
                    case '(' -> Token.Kind.LPAREN;
                    case ')' -> Token.Kind.RPAREN;
                    case ',' -> Token.Kind.COMMA;
                    case ';' -> Token.Kind.SEMICOLON;
                    case '.' -> Token.Kind.DOT;
                    case '-', '|', '<', '>', '!', '^', '*', '@', '&', '=', ':' -> Token.Kind.SYMBOL;
                    default -> {
                        if (lenient) {
                            warn(startLine, startCol, "unexpected character '" + c + "' skipped");
                            advance(1);
                            yield null;
                        }
                        throw new Asn1ParseException(startLine, startCol,
                                "unexpected character '" + c + "'");
                    }
                };
                if (kind != null) {
                    advance(1);
                    out.add(new Token(kind, String.valueOf(c), startLine, startCol));
                }
            }
        }
    }

    private String readWord() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            // '_' is not X.680 but Huawei grammars use it in identifiers (input_called_number)
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' && pos + 1 < text.length()
                    && Character.isLetterOrDigit(text.charAt(pos + 1)) && !text.startsWith("--", pos)) {
                advance(1);
            } else {
                break;
            }
        }
        return text.substring(start, pos);
    }

    private String readNumber() {
        int start = pos;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            advance(1);
        }
        return text.substring(start, pos);
    }

    private String readQuoted(char quote) {
        int startLine = line;
        int startCol = col;
        int start = pos;
        advance(1);
        while (pos < text.length() && text.charAt(pos) != quote) {
            advance(1);
        }
        if (pos >= text.length()) {
            if (lenient) {
                warn(startLine, startCol, "unterminated string literal");
                return text.substring(start, pos);
            }
            throw new Asn1ParseException(startLine, startCol, "unterminated string literal");
        }
        advance(1);
        // 'DEADBEEF'H / '0101'B suffix
        if (pos < text.length() && (text.charAt(pos) == 'H' || text.charAt(pos) == 'B')) {
            advance(1);
        }
        return text.substring(start, pos);
    }

    private boolean isAtLineStart(int p) {
        int cur = p - 1;
        while (cur >= 0) {
            char ch = text.charAt(cur);
            if (ch == '\n' || ch == '\r') {
                return true;
            }
            if (ch != ' ' && ch != '\t') {
                return false;
            }
            cur--;
        }
        return true;
    }

    private void skipWhitespaceAndComments() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c)) {
                advance(1);
            } else if (text.startsWith("--", pos)) {
                boolean lineCommentOnly = lenient && isAtLineStart(pos);
                advance(2);
                while (pos < text.length() && text.charAt(pos) != '\n'
                        && (lineCommentOnly || !text.startsWith("--", pos))) {
                    advance(1);
                }
                if (!lineCommentOnly && text.startsWith("--", pos)) {
                    advance(2);
                }
            } else if (lenient && text.startsWith("//", pos)) {
                advance(2);
                while (pos < text.length() && text.charAt(pos) != '\n') {
                    advance(1);
                }
            } else if (text.startsWith("/*", pos)) {
                int depth = 1;
                advance(2);
                while (pos < text.length() && depth > 0) {
                    if (text.startsWith("/*", pos)) {
                        depth++;
                        advance(2);
                    } else if (text.startsWith("*/", pos)) {
                        depth--;
                        advance(2);
                    } else {
                        advance(1);
                    }
                }
            } else {
                return;
            }
        }
    }

    private void advance(int n) {
        for (int i = 0; i < n; i++) {
            if (text.charAt(pos) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
            pos++;
        }
    }
}
