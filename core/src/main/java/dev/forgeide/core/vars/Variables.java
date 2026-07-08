package dev.forgeide.core.vars;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Syntax of the {@code ${scope.key}} substitution language: finding references in a
 * template and rendering a template against a {@link VariableResolver}. The engine
 * (T06) renders; the loader/validator (T03) only locates references.
 */
public final class Variables {

    private static final Pattern REF =
            Pattern.compile("\\$\\{\\s*([A-Za-z0-9_]+)\\.([A-Za-z0-9_.-]+)\\s*}");

    private Variables() {
    }

    /** All references in {@code text}, in order of appearance (duplicates kept). */
    public static List<VariableReference> references(String text) {
        List<VariableReference> refs = new ArrayList<>();
        if (text == null) {
            return refs;
        }
        Matcher m = REF.matcher(text);
        while (m.find()) {
            refs.add(new VariableReference(m.group(1), m.group(2), m.group()));
        }
        return refs;
    }

    /** True if {@code text} still carries at least one {@code ${...}} reference. */
    public static boolean hasReferences(String text) {
        return text != null && REF.matcher(text).find();
    }

    /**
     * Renders every reference in {@code text} through {@code resolver}.
     *
     * @throws UnresolvedVariableException if the resolver cannot supply a value
     */
    public static String render(String text, VariableResolver resolver) {
        if (text == null) {
            return null;
        }
        Matcher m = REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            VariableReference ref = new VariableReference(m.group(1), m.group(2), m.group());
            String value = resolver.resolve(ref)
                    .orElseThrow(() -> new UnresolvedVariableException(ref));
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
