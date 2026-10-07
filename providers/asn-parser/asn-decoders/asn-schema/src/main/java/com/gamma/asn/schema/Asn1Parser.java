package com.gamma.asn.schema;

import com.gamma.asn.core.TagClass;
import com.gamma.asn.schema.ast.BuiltinKind;
import com.gamma.asn.schema.ast.ComponentAst;
import com.gamma.asn.schema.ast.ModuleAst;
import com.gamma.asn.schema.ast.TypeAst;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser for the X.680 subset CDR grammars use (REDESIGN.md §4.3).
 * Anything outside the subset is a loud parse error with line/column — never silent garbage.
 *
 * Documented tolerances for the hand-doctored grammar files in config/:
 * trailing commas before '}', missing commas between components, case-insensitive
 * keywords, OPTIONAL/DEFAULT on CHOICE alternatives.
 *
 * {@link #parseLenient} additionally recovers from unparseable components/assignments by
 * skipping to the next boundary — every skip is reported as a warning with its location,
 * so broken definitions are loud, but one botched line no longer kills the whole grammar.
 */
public final class Asn1Parser {

    private final List<Token> tokens;
    private final List<String> warnings; // null = strict mode
    private int i;

    private Asn1Parser(String text, List<String> warnings) {
        this.tokens = Tokenizer.tokenize(text);
        this.warnings = warnings;
    }

    /** Parses every module in {@code text} (a file may hold more than one). Strict. */
    public static List<ModuleAst> parse(String text) {
        return new Asn1Parser(text, null).modules();
    }

    /** Parse with per-definition error recovery; skipped constructs land in {@code warningsOut}. */
    public static List<ModuleAst> parseLenient(String text, List<String> warningsOut) {
        return new Asn1Parser(text, warningsOut).modules();
    }

    private List<ModuleAst> modules() {
        List<ModuleAst> modules = new ArrayList<>();
        while (!peek().is(Token.Kind.EOF)) {
            modules.add(module());
        }
        if (modules.isEmpty()) {
            throw new Asn1ParseException("no ASN.1 module found");
        }
        return modules;
    }

    // ---------------- module ----------------

    private ModuleAst module() {
        // vendor exports (e.g. Huawei GSN grammars) start straight at DEFINITIONS —
        // tolerate the missing module name
        String name;
        if (peek().isWord("DEFINITIONS")) {
            name = "ANONYMOUS";
        } else {
            name = expectIdent("module name");
            if (peek().is(Token.Kind.LBRACE)) { // module object identifier
                skipBalanced(Token.Kind.LBRACE, Token.Kind.RBRACE);
            }
        }
        expectWord("DEFINITIONS");
        ModuleAst.TagDefault tagDefault = ModuleAst.TagDefault.EXPLICIT_TAGS;
        if (peek().isWord("IMPLICIT") || peek().isWord("EXPLICIT") || peek().isWord("AUTOMATIC")) {
            String word = next().text();
            expectWord("TAGS");
            tagDefault = switch (word) {
                case "IMPLICIT" -> ModuleAst.TagDefault.IMPLICIT_TAGS;
                case "AUTOMATIC" -> ModuleAst.TagDefault.AUTOMATIC_TAGS;
                default -> ModuleAst.TagDefault.EXPLICIT_TAGS;
            };
        }
        if (peek().isWord("EXTENSIBILITY")) {
            next();
            expectWord("IMPLIED");
        }
        expect(Token.Kind.ASSIGN);
        expectWord("BEGIN");

        Map<String, TypeAst> types = new LinkedHashMap<>();
        Map<String, String> imports = new LinkedHashMap<>();
        List<String> exports = new ArrayList<>();

        while (!peek().isWord("END")) {
            if (peek().is(Token.Kind.EOF)) {
                if (warnings != null) { // hand-doctored files may just stop
                    warnings.add("missing END for module " + name);
                    return new ModuleAst(name, tagDefault, types, imports, exports);
                }
                throw err("missing END for module " + name);
            }
            if (peek().isWord("EXPORTS")) {
                next();
                // vendor files write "EXPORTS everything" with no terminating ';' —
                // stop at ';', EOF, or where the first assignment visibly starts
                while (!peek().is(Token.Kind.SEMICOLON)) {
                    if (peek().is(Token.Kind.EOF)
                            || (peek().is(Token.Kind.IDENT) && peek(1).is(Token.Kind.ASSIGN))) {
                        break;
                    }
                    Token t = next();
                    if (t.is(Token.Kind.IDENT) && !t.text().equals("ALL")) {
                        exports.add(t.text());
                    }
                }
                if (peek().is(Token.Kind.SEMICOLON)) {
                    next(); // ;
                }
            } else if (peek().isWord("IMPORTS")) {
                next();
                imports(imports);
            } else if (warnings == null) {
                assignment(types);
            } else {
                try {
                    assignment(types);
                } catch (Asn1ParseException e) {
                    warnings.add("skipped assignment: " + e.getMessage());
                    skipToNextAssignment();
                }
            }
        }
        next(); // END
        return new ModuleAst(name, tagDefault, types, imports, exports);
    }

    private void imports(Map<String, String> imports) {
        List<String> pendingSymbols = new ArrayList<>();
        while (!peek().is(Token.Kind.SEMICOLON)) {
            if (peek().is(Token.Kind.EOF)) {
                throw err("unterminated IMPORTS");
            }
            if (peek().isWord("FROM")) {
                next();
                String moduleName = expectIdent("module name after FROM");
                if (peek().is(Token.Kind.LBRACE)) { // module OID
                    skipBalanced(Token.Kind.LBRACE, Token.Kind.RBRACE);
                }
                for (String sym : pendingSymbols) {
                    imports.put(sym, moduleName);
                }
                pendingSymbols.clear();
            } else if (peek().is(Token.Kind.IDENT)) {
                pendingSymbols.add(next().text());
            } else {
                next(); // commas, stray punctuation
            }
        }
        next(); // ;
    }

    private void assignment(Map<String, TypeAst> types) {
        Token nameTok = peek();
        String name = expectIdent("type or value name");
        if (peek().is(Token.Kind.ASSIGN)) {
            next();
            types.put(name, type());
            return;
        }
        // value assignment: `name Type ::= value` — parsed for structure, value discarded
        type();
        if (!peek().is(Token.Kind.ASSIGN)) {
            throw new Asn1ParseException(nameTok.line(), nameTok.col(),
                    "expected '::=' in assignment of '" + name + "'");
        }
        next();
        skipValue();
    }

    private void skipValue() {
        if (peek().is(Token.Kind.LBRACE)) {
            skipBalanced(Token.Kind.LBRACE, Token.Kind.RBRACE);
            return;
        }
        // simple value: signs/idents/numbers/strings until something that starts a new assignment
        while (true) {
            Token t = peek();
            if (t.is(Token.Kind.EOF) || t.isWord("END")) {
                return;
            }
            if (t.is(Token.Kind.IDENT) && peek(1).is(Token.Kind.ASSIGN)) {
                return; // next type assignment
            }
            if (t.is(Token.Kind.IDENT) && peek(1).is(Token.Kind.IDENT)) {
                return; // next value assignment
            }
            next();
        }
    }

    // ---------------- types ----------------

    private TypeAst type() {
        TypeAst base = taggedOrBareType();
        while (peek().is(Token.Kind.LPAREN)) {
            base = new TypeAst.Constrained(base, captureConstraint());
        }
        return base;
    }

    private TypeAst taggedOrBareType() {
        if (peek().is(Token.Kind.LBRACKET)) {
            next();
            TagClass tagClass = TagClass.CONTEXT;
            if (peek().is(Token.Kind.IDENT)) {
                tagClass = switch (next().text()) {
                    case "UNIVERSAL" -> TagClass.UNIVERSAL;
                    case "APPLICATION" -> TagClass.APPLICATION;
                    case "PRIVATE" -> TagClass.PRIVATE;
                    default -> throw err("unknown tag class");
                };
            }
            long number = Long.parseLong(expect(Token.Kind.NUMBER).text());
            expect(Token.Kind.RBRACKET);
            TypeAst.TagMode mode = TypeAst.TagMode.MODULE_DEFAULT;
            if (peek().isWord("IMPLICIT")) {
                next();
                mode = TypeAst.TagMode.IMPLICIT;
            } else if (peek().isWord("EXPLICIT")) {
                next();
                mode = TypeAst.TagMode.EXPLICIT;
            }
            return new TypeAst.Tagged(tagClass, number, mode, taggedOrBareType());
        }
        return bareType();
    }

    private TypeAst bareType() {
        Token t = peek();
        if (!t.is(Token.Kind.IDENT)) {
            throw err("expected a type, got '" + t.text() + "'");
        }
        switch (t.text().toUpperCase(java.util.Locale.ROOT)) {
            case "SEQUENCE" -> {
                next();
                return sequenceOrSet(true);
            }
            case "SET" -> {
                next();
                return sequenceOrSet(false);
            }
            case "CHOICE" -> {
                next();
                expect(Token.Kind.LBRACE);
                return new TypeAst.ChoiceType(components());
            }
            case "ENUMERATED" -> {
                next();
                if (!peek().is(Token.Kind.LBRACE)) { // vendor dirt: bodyless ENUMERATED
                    return new TypeAst.Builtin(BuiltinKind.ENUMERATED);
                }
                next();
                return new TypeAst.Enumerated(BuiltinKind.ENUMERATED, namedValues());
            }
            case "INTEGER" -> {
                next();
                if (peek().is(Token.Kind.LBRACE)) {
                    next();
                    return new TypeAst.Enumerated(BuiltinKind.INTEGER, namedValues());
                }
                return new TypeAst.Builtin(BuiltinKind.INTEGER);
            }
            case "OCTET" -> {
                next();
                expectWord("STRING");
                return new TypeAst.Builtin(BuiltinKind.OCTET_STRING);
            }
            case "BIT" -> {
                next();
                expectWord("STRING");
                if (peek().is(Token.Kind.LBRACE)) { // named bits, names not needed for decoding
                    skipBalanced(Token.Kind.LBRACE, Token.Kind.RBRACE);
                }
                return new TypeAst.Builtin(BuiltinKind.BIT_STRING);
            }
            case "OBJECT" -> {
                next();
                expectWord("IDENTIFIER");
                return new TypeAst.Builtin(BuiltinKind.OBJECT_IDENTIFIER);
            }
            case "ANY" -> {
                next();
                if (peek().isWord("DEFINED")) {
                    next();
                    expectWord("BY");
                    expectIdent("field name after ANY DEFINED BY");
                }
                return new TypeAst.Builtin(BuiltinKind.ANY);
            }
            case "BOOLEAN" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.BOOLEAN);
            }
            case "NULL" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.NULL);
            }
            case "REAL" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.REAL);
            }
            case "UTF8STRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.UTF8_STRING);
            }
            case "NUMERICSTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.NUMERIC_STRING);
            }
            case "PRINTABLESTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.PRINTABLE_STRING);
            }
            case "TELETEXSTRING", "T61STRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.TELETEX_STRING);
            }
            case "VIDEOTEXSTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.VIDEOTEX_STRING);
            }
            case "IA5STRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.IA5_STRING);
            }
            case "UTCTIME" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.UTC_TIME);
            }
            case "GENERALIZEDTIME" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.GENERALIZED_TIME);
            }
            case "GRAPHICSTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.GRAPHIC_STRING);
            }
            case "VISIBLESTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.VISIBLE_STRING);
            }
            case "GENERALSTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.GENERAL_STRING);
            }
            case "UNIVERSALSTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.UNIVERSAL_STRING);
            }
            case "BMPSTRING" -> {
                next();
                return new TypeAst.Builtin(BuiltinKind.BMP_STRING);
            }
            default -> {
                String name = next().text();
                if (peek().is(Token.Kind.DOT)) { // Module.Type external reference
                    next();
                    name = expectIdent("type after module qualifier");
                }
                if (peek().isWord("STRING")) { // vendor pseudo-type: "HEX STRING" etc.
                    next();
                }
                return new TypeAst.Ref(name);
            }
        }
    }

    /** After the SEQUENCE / SET keyword: `{ components }` or `[(SIZE ...)] OF [name] Type`. */
    private TypeAst sequenceOrSet(boolean isSequence) {
        String sizeConstraint = null;
        if (peek().is(Token.Kind.LPAREN)) {
            sizeConstraint = captureConstraint();
        }
        if (peek().isWord("OF")) {
            next();
            // optional element name: `SEQUENCE OF name Type`
            if (peek().is(Token.Kind.IDENT) && Character.isLowerCase(peek().text().charAt(0))
                    && (peek(1).is(Token.Kind.IDENT) || peek(1).is(Token.Kind.LBRACKET))) {
                next();
            }
            TypeAst element = type();
            TypeAst of = isSequence ? new TypeAst.SequenceOf(element) : new TypeAst.SetOf(element);
            return sizeConstraint == null ? of : new TypeAst.Constrained(of, sizeConstraint);
        }
        expect(Token.Kind.LBRACE);
        List<ComponentAst> comps = components();
        return isSequence ? new TypeAst.SequenceType(comps) : new TypeAst.SetType(comps);
    }

    private List<ComponentAst> components() {
        List<ComponentAst> out = new ArrayList<>();
        while (true) {
            if (peek().is(Token.Kind.RBRACE)) {
                next();
                return out;
            }
            if (peek().is(Token.Kind.EOF)) {
                throw err("unterminated component list");
            }
            if (peek().is(Token.Kind.COMMA)) { // stray/trailing comma tolerance
                next();
                continue;
            }
            if (peek().is(Token.Kind.ELLIPSIS)) { // extension marker
                next();
                if (peek().is(Token.Kind.SYMBOL) && peek().text().equals("!")) {
                    next();
                    next(); // exception spec value
                }
                continue;
            }
            if (peek().isWord("COMPONENTS")) {
                next();
                expectWord("OF");
                out.add(ComponentAst.componentsOf(type()));
                continue;
            }
            if (warnings == null) {
                out.add(component());
            } else {
                try {
                    out.add(component());
                } catch (Asn1ParseException e) {
                    warnings.add("skipped component: " + e.getMessage());
                    skipToNextComponent();
                }
            }
        }
    }

    private ComponentAst component() {
        String name = expectIdent("component name");
        // `Junk name [tag] ...`: an IDENT directly followed by `X [` can only mean the
        // first IDENT is stray (a `--a, --`-style comment pair leaves one behind, e.g.
        // mtnOCC's usedOffers line) — a real untagged component is never followed by '['
        while (warnings != null && peek().is(Token.Kind.IDENT)
                && peek(1).is(Token.Kind.LBRACKET)) {
            warnings.add("dropped stray token '" + name + "' before component '"
                    + peek().text() + "'");
            name = next().text();
        }
        TypeAst compType = type();
        boolean optional = false;
        String defaultValue = null;
        if (peek().isWord("OPTIONAL")) {
            next();
            optional = true;
        } else if (peek().isWord("DEFAULT")) {
            next();
            defaultValue = defaultValueText();
        }
        return ComponentAst.of(name, compType, optional, defaultValue);
    }

    /** Recovery: eat tokens to the next comma at this nesting level, or stop before '}'. */
    private void skipToNextComponent() {
        int depth = 0;
        while (true) {
            Token t = peek();
            if (t.is(Token.Kind.EOF)) {
                return;
            }
            if (depth == 0 && t.is(Token.Kind.RBRACE)) {
                return;
            }
            if (depth == 0 && t.is(Token.Kind.COMMA)) {
                next();
                return;
            }
            if (depth == 0 && t.is(Token.Kind.IDENT) && peek(1).is(Token.Kind.LBRACKET)) {
                return; // an unmistakable component start — don't eat it (aftel tMR)
            }
            if (t.is(Token.Kind.LBRACE) || t.is(Token.Kind.LBRACKET) || t.is(Token.Kind.LPAREN)) {
                depth++;
            } else if (t.is(Token.Kind.RBRACKET) || t.is(Token.Kind.RPAREN)
                    || t.is(Token.Kind.RBRACE)) {
                // the failed parse may have consumed the matching opener (e.g. the '[' of
                // a tag) — an unmatched closer here is garbage to step over, not nesting,
                // or the loop never again sees depth 0 and eats the rest of the module
                depth = Math.max(0, depth - 1);
            }
            next();
        }
    }

    /** Recovery: eat tokens until the next `Name ::=` (or END). */
    private void skipToNextAssignment() {
        while (true) {
            Token t = peek();
            if (t.is(Token.Kind.EOF) || t.isWord("END")) {
                return;
            }
            if (t.is(Token.Kind.IDENT) && peek(1).is(Token.Kind.ASSIGN)) {
                return;
            }
            next();
        }
    }

    private String defaultValueText() {
        StringBuilder sb = new StringBuilder();
        if (peek().is(Token.Kind.LBRACE)) {
            int start = i;
            skipBalanced(Token.Kind.LBRACE, Token.Kind.RBRACE);
            for (int k = start; k < i; k++) {
                sb.append(tokens.get(k).text());
            }
            return sb.toString();
        }
        if (peek().is(Token.Kind.SYMBOL)) { // e.g. negative number
            sb.append(next().text());
        }
        sb.append(next().text());
        return sb.toString();
    }

    private LinkedHashMap<String, Long> namedValues() {
        LinkedHashMap<String, Long> values = new LinkedHashMap<>();
        long nextOrdinal = 0;
        while (!peek().is(Token.Kind.RBRACE)) {
            if (peek().is(Token.Kind.EOF)) {
                throw err("unterminated named value list");
            }
            if (peek().is(Token.Kind.COMMA) || peek().is(Token.Kind.ELLIPSIS)) {
                next();
                continue;
            }
            String name = expectIdent("named value");
            long value = nextOrdinal;
            if (peek().is(Token.Kind.LPAREN)) {
                next();
                boolean negative = false;
                if (peek().is(Token.Kind.SYMBOL) && peek().text().equals("-")) {
                    next();
                    negative = true;
                }
                value = Long.parseLong(expect(Token.Kind.NUMBER).text());
                if (negative) {
                    value = -value;
                }
                expect(Token.Kind.RPAREN);
            }
            values.put(name, value);
            nextOrdinal = value + 1;
        }
        next(); // }
        return values;
    }

    /** Captures a balanced {@code ( ... )} group verbatim (subtype constraint, SIZE, …). */
    private String captureConstraint() {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        do {
            Token t = next();
            if (t.is(Token.Kind.EOF)) {
                throw err("unterminated constraint");
            }
            if (t.is(Token.Kind.LPAREN)) {
                depth++;
            } else if (t.is(Token.Kind.RPAREN)) {
                depth--;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(t.text());
        } while (depth > 0);
        return sb.toString();
    }

    // ---------------- token plumbing ----------------

    private Token peek() {
        return tokens.get(i);
    }

    private Token peek(int ahead) {
        int idx = Math.min(i + ahead, tokens.size() - 1);
        return tokens.get(idx);
    }

    private Token next() {
        Token t = tokens.get(i);
        if (!t.is(Token.Kind.EOF)) {
            i++;
        }
        return t;
    }

    // expect* throw WITHOUT consuming the offending token, so lenient recovery starts
    // exactly at the failure point (consuming it desyncs skipToNextComponent — an eaten
    // '}' or '[' used to make recovery swallow the rest of the module)

    private Token expect(Token.Kind kind) {
        Token t = peek();
        if (!t.is(kind)) {
            throw new Asn1ParseException(t.line(), t.col(),
                    "expected " + kind + ", got '" + t.text() + "'");
        }
        return next();
    }

    private String expectIdent(String what) {
        Token t = peek();
        if (!t.is(Token.Kind.IDENT)) {
            throw new Asn1ParseException(t.line(), t.col(),
                    "expected " + what + ", got '" + t.text() + "'");
        }
        return next().text();
    }

    private void expectWord(String word) {
        Token t = peek();
        if (!t.isWord(word)) {
            throw new Asn1ParseException(t.line(), t.col(),
                    "expected '" + word + "', got '" + t.text() + "'");
        }
        next();
    }

    private void skipBalanced(Token.Kind open, Token.Kind close) {
        Token first = expect(open);
        int depth = 1;
        while (depth > 0) {
            Token t = next();
            if (t.is(Token.Kind.EOF)) {
                throw new Asn1ParseException(first.line(), first.col(), "unbalanced " + open);
            }
            if (t.is(open)) {
                depth++;
            } else if (t.is(close)) {
                depth--;
            }
        }
    }

    private Asn1ParseException err(String message) {
        Token t = peek();
        return new Asn1ParseException(t.line(), t.col(), message);
    }
}
