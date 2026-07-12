package dev.forgeide.core.secret;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Regexp masker (SD §6.2, SDD SR-5) run over trusted engine writes — judge detail/{@code
 * accumulated_errors}, audit payloads, {@code meta.json} — before they touch disk. Two layers:
 * every exact secret value the caller already knows was handed to a phase's {@code env_scope}
 * (a {@code FileSecretStore} resolution), then a handful of typical credential shapes as a
 * second line of defense for values the caller never named. Raw agent logs ({@code
 * stdout.jsonl}/{@code stderr.log}) are untrusted by design (SD §6.2) and never run through
 * this.
 */
public final class SecretMasker {

    public static final String MASK = "***";

    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)(Authorization:\\s*Bearer\\s+)\\S+");
    private static final Pattern TOKEN_ASSIGNMENT =
            Pattern.compile("(?i)\\b(token|api[_-]?key|secret|password)\\s*=\\s*\\S+");
    private static final Pattern PROVIDER_TOKEN =
            Pattern.compile("\\b(?:ghp_|gho_|ghu_|ghs_|ghr_|github_pat_|glpat-)[A-Za-z0-9_-]+\\b");

    private SecretMasker() {
    }

    /**
     * Replaces every occurrence of a known secret value, then anything matching a typical
     * credential pattern, with {@link #MASK}. {@code null}/empty text passes through untouched;
     * blank secret values are skipped rather than matched (an empty-string "secret" would
     * otherwise mask everything).
     */
    public static String mask(String text, Collection<String> knownSecretValues) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String value : knownSecretValues) {
            if (value != null && !value.isBlank()) {
                masked = masked.replace(value, MASK);
            }
        }
        masked = BEARER_TOKEN.matcher(masked).replaceAll("$1" + MASK);
        masked = TOKEN_ASSIGNMENT.matcher(masked).replaceAll("$1=" + MASK);
        masked = PROVIDER_TOKEN.matcher(masked).replaceAll(MASK);
        return masked;
    }
}
