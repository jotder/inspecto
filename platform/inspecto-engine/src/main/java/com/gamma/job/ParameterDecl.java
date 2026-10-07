package com.gamma.job;

import java.util.List;

/**
 * A Job Type's declaration of one runtime Parameter it needs (R3, §7.1): its {@code name} and
 * {@link ParamType}, whether it is {@code required}, an optional {@code deduce} {@code $}-expression
 * tried when nothing explicit is bound, a literal {@code defaultValue} fallback, and a description.
 * Surfaced verbatim by {@code GET /jobs/types/{id}} so a UI can render an authoring form.
 *
 * <p>Consumed at run time by {@link ParameterResolver} (P3a): {@code deduce} is a {@code $}-expression
 * tried when nothing explicit is bound, {@code defaultValue} the literal fallback after it. A missing
 * {@code required} parameter fails the Run REJECTED before user code runs (§7.2).
 *
 * <p>The remaining components are the <b>rendering + validation contract</b>
 * (job-parameter-contract §7.2): everything the UI's renderer can already do but could not be told to do,
 * so a form is <em>generated</em> from the declaration instead of guessed from the parameter's name. Each
 * defaults to today's behaviour, and the 6-arg constructor below keeps every existing built-in compiling
 * unchanged.
 *
 * @param label       human field label; falls back to the humanised {@code name}
 * @param tier        disclosure tier, decoupled from {@code required}; defaults from {@code required}
 * @param options     allowed values ⇒ renders a select, validated as a choice
 * @param pattern     regex the literal value must fully match
 * @param min         inclusive numeric lower bound ({@code null} = unbounded)
 * @param max         inclusive numeric upper bound ({@code null} = unbounded)
 * @param placeholder field hint
 * @param group       section heading (e.g. {@code Recipients}) — orders the form
 * @param multi       list cardinality: the value is a list of {@code type}, validated per item
 * @param secret      mask on input, and in API reads (masked at the route boundary, never in
 *                    {@code JobConfig.toMap()}, which also feeds bundle export — §7.2)
 * @param expressions whether {@code $}-tokens are accepted here; {@code false} forces a literal
 */
public record ParameterDecl(String name, ParamType type, boolean required,
                            String deduce, String defaultValue, String description,
                            String label, Tier tier, List<String> options, String pattern,
                            Double min, Double max, String placeholder, String group,
                            boolean multi, boolean secret, boolean expressions) {

    /** Disclosure tier — deliberately separate from {@code required}: an optional parameter can still be
     *  prominent, and a rarely-touched one can be tucked away without becoming optional. */
    public enum Tier { REQUIRED, OPTIONAL, ADVANCED }

    public ParameterDecl {
        options = options == null ? List.of() : List.copyOf(options);
        tier = tier != null ? tier : (required ? Tier.REQUIRED : Tier.OPTIONAL);
        // v1 constraint (§7.4): a multi-select has no renderer support and no declared consumer. Rejected
        // here — at construction, the earliest and cheapest point — rather than rendered half-way.
        if (multi && !options.isEmpty())
            throw new IllegalArgumentException(
                    "parameter '" + name + "': options + multi (a multi-select) is not supported yet");
    }

    /** The pre-contract 6-component form: every rendering component at its default. Keeps the built-ins,
     *  and any caller that only needs the original fields, compiling and behaving exactly as before. */
    public ParameterDecl(String name, ParamType type, boolean required,
                         String deduce, String defaultValue, String description) {
        this(name, type, required, deduce, defaultValue, description,
                null, null, List.of(), null, null, null, null, null, false, false, true);
    }

    public static ParameterDecl required(String name, ParamType type, String description) {
        return new ParameterDecl(name, type, true, null, null, description);
    }

    public static ParameterDecl optional(String name, ParamType type, String defaultValue, String description) {
        return new ParameterDecl(name, type, false, null, defaultValue, description);
    }

    /** Fluent start for a declaration using the rendering contract (§7.2). Precedent for builder-on-record
     *  in this codebase: {@code EventQuery}, {@code Event}, {@code ObjectQuery}, {@code OperationalObject}.
     *  It also retires the wart that a decl with {@code deduce} set could only be built through the raw
     *  all-args constructor. */
    public static Builder of(String name, ParamType type) {
        return new Builder(name, type);
    }

    /** Fluent builder; every rendering component defaults to today's behaviour. */
    public static final class Builder {
        private final String name;
        private final ParamType type;
        private boolean required;
        private String deduce, defaultValue, description, label, pattern, placeholder, group;
        private Tier tier;
        private List<String> options = List.of();
        private Double min, max;
        private boolean multi, secret;
        private boolean expressions = true;

        private Builder(String name, ParamType type) {
            this.name = name;
            this.type = type;
        }

        public Builder required()                  { this.required = true; return this; }
        public Builder deduce(String expr)         { this.deduce = expr; return this; }
        public Builder defaultValue(String v)      { this.defaultValue = v; return this; }
        public Builder description(String d)       { this.description = d; return this; }
        public Builder label(String l)             { this.label = l; return this; }
        /** Sets the disclosure tier; {@link Tier#REQUIRED} also marks the parameter required. */
        public Builder tier(Tier t)                { this.tier = t; if (t == Tier.REQUIRED) this.required = true; return this; }
        public Builder options(String... values)   { this.options = List.of(values); return this; }
        public Builder pattern(String regex)       { this.pattern = regex; return this; }
        public Builder min(double n)               { this.min = n; return this; }
        public Builder max(double n)               { this.max = n; return this; }
        public Builder placeholder(String p)       { this.placeholder = p; return this; }
        public Builder group(String g)             { this.group = g; return this; }
        public Builder multi()                     { this.multi = true; return this; }
        public Builder secret()                    { this.secret = true; return this; }
        /** Forces a literal here — no {@code $}-token is accepted (default: expressions allowed). */
        public Builder noExpressions()             { this.expressions = false; return this; }

        public ParameterDecl build() {
            return new ParameterDecl(name, type, required, deduce, defaultValue, description,
                    label, tier, options, pattern, min, max, placeholder, group, multi, secret, expressions);
        }
    }
}
